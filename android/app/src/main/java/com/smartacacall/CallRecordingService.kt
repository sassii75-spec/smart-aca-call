package com.smartacacall

import android.app.*
import android.content.ContentUris
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class CallRecordingService : Service() {

    private val TAG = "CallRecordingService"
    private val CHANNEL_ID = "SmartCallAgentChannel"
    private var fileObservers = mutableListOf<FileObserver>()
    private var pollingTimer: java.util.Timer? = null
    
    // 메모리 상에서 현재 진행 중인 업로드 파일들을 추적하여 중복 업로드 방지
    private val currentlyUploadingPaths = HashSet<String>()

    data class UploadTask(
        val contentUri: android.net.Uri,
        val displayName: String,
        val originalPath: String
    )

    private val uploadQueue = java.util.concurrent.ConcurrentLinkedQueue<UploadTask>()
    @Volatile
    private var isUploading = false

    private fun enqueueUpload(contentUri: android.net.Uri, displayName: String, originalPath: String) {
        val task = UploadTask(contentUri, displayName, originalPath)
        
        // 큐에 이미 등록된 동일 경로인지 검사 (중복 방지)
        val isAlreadyQueued = uploadQueue.any { it.originalPath == originalPath }
        if (isAlreadyQueued) {
            Log.d(TAG, "[QUEUE] File already in upload queue: $originalPath")
            return
        }
        
        currentlyUploadingPaths.add(originalPath)
        uploadQueue.add(task)
        Log.d(TAG, "[QUEUE] Enqueued new upload task: $displayName. Queue size: ${uploadQueue.size}")
        
        triggerNextUpload()
    }

    private synchronized fun triggerNextUpload() {
        if (isUploading) {
            Log.d(TAG, "[QUEUE] An upload is already active. Waiting for completion...")
            return
        }
        
        val nextTask = uploadQueue.poll()
        if (nextTask == null) {
            Log.d(TAG, "[QUEUE] No more tasks in queue. Going idle.")
            isUploading = false
            return
        }
        
        isUploading = true
        Log.d(TAG, "[QUEUE] Processing next task: ${nextTask.displayName}. Remaining queue size: ${uploadQueue.size}")
        
        performUpload(nextTask)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(100, TimeUnit.SECONDS)
        .writeTimeout(100, TimeUnit.SECONDS)
        .readTimeout(100, TimeUnit.SECONDS)
        .build()

    private var academyId: String = "sa_academy"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        academyId = intent?.getStringExtra("ACADEMY_ID") ?: "sa_academy"
        Log.d(TAG, "[AGENT_START] onStartCommand started. Academy ID: $academyId")
        
        // 이전 빌드에서 타임아웃 실패 등으로 인해 전송 안 된 채 이미 업로드 완료 처리되었던 기록을 초기화하여
        // v1.0.3 버전 설치 즉시 어제오늘 테스트 통화 내역들이 바로 재수집되어 전송될 수 있도록 초기화
        val sharedPrefs = getSharedPreferences("SmartCallPrefs", MODE_PRIVATE)
        sharedPrefs.edit().clear().apply()
        Log.d(TAG, "[AGENT_START] Cleared old uploaded_files cache in SharedPreferences to trigger backlogs capture.")
        
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Smart Call Agent 실행 중")
            .setContentText("통화 녹음 파일을 감지하고 있습니다.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        // Android 14+ (API 34+) foreground service type requirement
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Log.d(TAG, "[AGENT_START] Running startForeground with FOREGROUND_SERVICE_TYPE_SPECIAL_USE for API 34+")
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.d(TAG, "[AGENT_START] Running startForeground with FOREGROUND_SERVICE_TYPE_DATA_SYNC for API Q+")
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            Log.d(TAG, "[AGENT_START] Running standard startForeground for API < Q")
            startForeground(1, notification)
        }
        
        startWatching()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        
        pollingTimer?.let {
            try {
                it.cancel()
                Log.d(TAG, "[WATCHER] Polling timer cancelled successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "[WATCHER] Error cancelling polling timer", e)
            }
        }
        pollingTimer = null

        fileObservers.forEach { 
            try {
                it.stopWatching()
                Log.d(TAG, "[WATCHER] Stopped FileObserver.")
            } catch (e: Exception) {
                Log.e(TAG, "[WATCHER] Error stopping FileObserver", e)
            }
        }
        fileObservers.clear()
        
        contentObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
                Log.d(TAG, "[WATCHER] Unregistered ContentObserver.")
            } catch (e: Exception) {
                Log.e(TAG, "[WATCHER] Error unregistering ContentObserver", e)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Smart Call Agent",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private var contentObserver: android.database.ContentObserver? = null
    private var lastUploadedFile: String? = null

    private fun createFileObserver(path: String): FileObserver? {
        val dir = File(path)
        if (!dir.exists()) {
            Log.d(TAG, "[FILE_OBSERVER] Directory does not exist, attempting to create: $path")
            try {
                dir.mkdirs()
            } catch (e: Exception) {
                Log.e(TAG, "[FILE_OBSERVER] Failed to create directory: $path", e)
            }
        }
        
        Log.d(TAG, "[FILE_OBSERVER] Initializing FileObserver for path: $path")
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, FileObserver.CLOSE_WRITE) {
                override fun onEvent(event: Int, fileName: String?) {
                    if (fileName != null && (event and FileObserver.CLOSE_WRITE) != 0) {
                        handleFileCreated(path, fileName)
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(path, FileObserver.CLOSE_WRITE) {
                override fun onEvent(event: Int, fileName: String?) {
                    if (fileName != null && (event and FileObserver.CLOSE_WRITE) != 0) {
                        handleFileCreated(path, fileName)
                    }
                }
            }
        }
    }

    private fun isTargetCallRecording(filePath: String, displayName: String): Boolean {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val isAudio = extension in arrayOf("m4a", "mp3", "amr", "3gp", "wav", "ogg", "aac", "flac")
        if (!isAudio) return false

        // 1. 경로 검사 (기본 통화 녹음 저장 디렉토리인 경우 무조건 참)
        val isCallFolder = filePath.contains("/Call", ignoreCase = true) || 
                           filePath.contains("/Recordings", ignoreCase = true)
        if (isCallFolder) {
            Log.d(TAG, "[FILTER] Path matched call recordings folder: $filePath")
            return true
        }

        // 2. 키워드 검사 (기존 규칙 유지)
        val hasKeyword = displayName.contains("Call", ignoreCase = true) ||
                         displayName.contains("Record", ignoreCase = true) ||
                         displayName.contains("통화", ignoreCase = true) ||
                         displayName.contains("녹음", ignoreCase = true) ||
                         displayName.contains("Voice", ignoreCase = true) ||
                         displayName.contains("Audio", ignoreCase = true)
        if (hasKeyword) {
            Log.d(TAG, "[FILTER] Display name matched keyword: $displayName")
            return true
        }

        // 3. 패턴 추론 (날짜 및 숫자 형태인 경우 통화 녹음으로 간주 및 허용)
        val nameWithoutExt = displayName.substringBeforeLast('.')
        val isNumericPattern = nameWithoutExt.matches(Regex("^[0-9_+\\-]+$"))
        val isDatePattern = nameWithoutExt.matches(Regex(".*\\d{6,8}_\\d{4,6}.*"))

        if (isNumericPattern || isDatePattern) {
            Log.d(TAG, "[FILTER] Pattern deduction success. Numeric: $isNumericPattern, DatePattern: $isDatePattern for $displayName")
            return true
        }

        Log.d(TAG, "[FILTER] File skipped. No criteria matched for: $displayName (Path: $filePath)")
        return false
    }

    private fun handleFileCreated(directory: String, fileName: String) {
        val fullPath = File(directory, fileName).absolutePath
        Log.d(TAG, "[FILE_OBSERVER] Event CLOSE_WRITE captured. Detected new physical file: $fullPath")
        
        if (!isTargetCallRecording(fullPath, fileName)) {
            return
        }
        
        val sharedPrefs = getSharedPreferences("SmartCallPrefs", MODE_PRIVATE)
        val uploadedFiles = HashSet(sharedPrefs.getStringSet("uploaded_files", emptySet()) ?: emptySet())
        
        if (uploadedFiles.contains(fullPath) || currentlyUploadingPaths.contains(fullPath)) {
            Log.d(TAG, "[FILE_OBSERVER] File already processed or currently uploading: $fullPath")
            return
        }
        
        Log.d(TAG, "[FILE_OBSERVER] Found target recording: $fileName. Requesting immediate MediaScanner force-scan...")
        
        // Mark as currently uploading in memory to prevent duplicate requests
        currentlyUploadingPaths.add(fullPath)
        
        // Force scan physical file immediately to generate Content Uri
        android.media.MediaScannerConnection.scanFile(
            this,
            arrayOf(fullPath),
            null
        ) { scanPath, uri ->
            Log.d(TAG, "[MEDIA_SCANNER] Scan completed for path: $scanPath -> Content Uri: $uri")
            if (uri != null) {
                Log.d(TAG, "[MEDIA_SCANNER] Triggering direct upload with generated Uri.")
                enqueueUpload(uri, fileName, scanPath)
            } else {
                Log.e(TAG, "[MEDIA_SCANNER] CRITICAL: Generated Uri is null for $scanPath!")
                currentlyUploadingPaths.remove(fullPath) // Remove on failure to allow retry
            }
        }
    }

    private fun startPolling() {
        Log.d(TAG, "[POLLING] Initializing background query poller (15s interval)...")
        pollingTimer = java.util.Timer()
        pollingTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                try {
                    Log.d(TAG, "[POLLING] Executing periodic MediaStore validation query...")
                    checkLatestAudioFile()
                } catch (e: Exception) {
                    Log.e(TAG, "[POLLING] Periodic validation failed", e)
                }
            }
        }, 5000, 15000) // 5 seconds initial delay, 15 seconds period
    }

    private fun startWatching() {
        Log.d(TAG, "[WATCHER] Starting FileObservers for real-time local file system events")
        
        val pathsToWatch = arrayOf(
            File(Environment.getExternalStorageDirectory(), "Call").absolutePath,
            File(Environment.getExternalStorageDirectory(), "Recordings/Call").absolutePath,
            File(Environment.getExternalStorageDirectory(), "Recordings").absolutePath
        )
        
        for (path in pathsToWatch) {
            try {
                val observer = createFileObserver(path)
                if (observer != null) {
                    observer.startWatching()
                    fileObservers.add(observer)
                    Log.d(TAG, "[WATCHER] Successfully started watching folder: $path")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[WATCHER] Failed to start FileObserver for path: $path", e)
            }
        }

        Log.d(TAG, "[WATCHER] Starting MediaStore ContentObserver for Android 10+ compatibility as fallback")
        contentObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                super.onChange(selfChange, uri)
                Log.d(TAG, "[WATCHER] MediaStore changed event received (fallback). Uri: $uri")
                checkLatestAudioFile()
            }
        }
        
        try {
            contentResolver.registerContentObserver(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                contentObserver!!
            )
            Log.d(TAG, "[WATCHER] Successfully registered ContentObserver for EXTERNAL_CONTENT_URI")
        } catch (e: Exception) {
            Log.e(TAG, "[WATCHER] CRITICAL ERROR: Failed to register ContentObserver", e)
        }

        // Start periodic background scanner polling as second safety tier
        startPolling()
    }

    private fun checkLatestAudioFile() {
        Log.d(TAG, "[SCANNER] Commencing scan of latest MediaStore audio files...")
        val projection = arrayOf(
            android.provider.MediaStore.Audio.Media._ID,
            android.provider.MediaStore.Audio.Media.DATA, 
            android.provider.MediaStore.Audio.Media.DATE_ADDED,
            android.provider.MediaStore.Audio.Media.DISPLAY_NAME
        )
        // Sort by Database auto-increment ID DESC to guarantee physical insertion order sorting
        val sortOrder = "${android.provider.MediaStore.Audio.Media._ID} DESC"
        
        try {
            contentResolver.query(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID)
                val dataIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATA)
                val dateAddedIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATE_ADDED)
                val nameIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DISPLAY_NAME)
                
                var count = 0
                val sharedPrefs = getSharedPreferences("SmartCallPrefs", MODE_PRIVATE)
                val uploadedFiles = HashSet(sharedPrefs.getStringSet("uploaded_files", emptySet()) ?: emptySet())
                val currentTimeSeconds = System.currentTimeMillis() / 1000
                
                Log.d(TAG, "[SCANNER] Scanned records count: ${cursor.count}. Checking top 10 files...")
                
                while (cursor.moveToNext() && count < 10) {
                    count++
                    val id = cursor.getLong(idIndex)
                    val filePath = cursor.getString(dataIndex) ?: "unknown_path"
                    val dateAdded = cursor.getLong(dateAddedIndex)
                    val displayName = cursor.getString(nameIndex) ?: "recording.m4a"
                    
                    // Normalize dates (seconds representation)
                    var fileDate = dateAdded
                    if (fileDate > 999999999999L) { // Safe guard: handle millisecond timestamps
                        fileDate /= 1000
                    }
                    
                    val ageSeconds = currentTimeSeconds - fileDate
                    // Substantially relax timing filter to 24 hours to handle latency/clock drift, and use Math.abs
                    val isRecent = Math.abs(ageSeconds) < 86400
                    val isAlreadyUploaded = uploadedFiles.contains(filePath)
                    val isCurrentlyUploading = currentlyUploadingPaths.contains(filePath)
                    
                    Log.d(TAG, "[SCANNER] Item #$count -> ID: $id, Name: $displayName, Path: $filePath, Age: ${ageSeconds}s, Recent: $isRecent, AlreadyUploaded: $isAlreadyUploaded, CurrentlyUploading: $isCurrentlyUploading")
                    
                    if (isRecent && !isAlreadyUploaded && !isCurrentlyUploading) {
                        if (isTargetCallRecording(filePath, displayName)) {
                            Log.d(TAG, "[SCANNER] [MATCH_SUCCESS] Found new target call recording: $displayName (Path: $filePath)")
                            
                            val contentUri = android.content.ContentUris.withAppendedId(
                                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                id
                            )
                            
                            // Mark as currently uploading in memory
                            currentlyUploadingPaths.add(filePath)
                            
                            // Wait 2 seconds to ensure file write is fully completed, then copy and upload
                            Log.d(TAG, "[SCANNER] Waiting 2 seconds for file handles to close before processing upload...")
                            val finalFilePath = filePath
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                enqueueUpload(contentUri, displayName, finalFilePath)
                            }, 2000)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[SCANNER] CRITICAL ERROR during MediaStore query scan", e)
        }
    }

    private fun performUpload(task: UploadTask) {
        val contentUri = task.contentUri
        val displayName = task.displayName
        val originalPath = task.originalPath

        val extension = displayName.substringAfterLast('.', "m4a")
        val safeFileName = "recording_${System.currentTimeMillis()}.$extension"

        Log.d(TAG, "[UPLOAD] Preparing Scoped Storage bypass copy. URI: $contentUri, Name: $displayName, SafeName: $safeFileName")

        // 1. Copy Content URI to a secure cache file to bypass direct file access restrictions
        val tempFile = File(cacheDir, safeFileName)
        try {
            Log.d(TAG, "[UPLOAD] Copying stream from ContentResolver to local cache file: ${tempFile.absolutePath}")
            contentResolver.openInputStream(contentUri)?.use { inputStream ->
                tempFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            Log.d(TAG, "[UPLOAD] Copy complete. Cache file size: ${tempFile.length()} bytes")
            if (tempFile.length() == 0L) {
                Log.e(TAG, "[UPLOAD] WARNING: Copied cache file size is 0 bytes! Scoped Storage may be blocking this content Uri.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[UPLOAD] CRITICAL ERROR copying audio stream to cache", e)
            currentlyUploadingPaths.remove(originalPath)
            
            // Proceed to next upload
            isUploading = false
            triggerNextUpload()
            return
        }

        // 2. Build multi-part network request
        Log.d(TAG, "[UPLOAD] Preparing OkHttp request. Academy: $academyId, Server Endpoint: https://smart-call-ai-gamma.vercel.app/api/analyze")
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("academyId", academyId)
            .addFormDataPart(
                "file",
                safeFileName,
                tempFile.asRequestBody("audio/*".toMediaTypeOrNull())
            )
            .build()

        val request = Request.Builder()
            .url("https://smart-call-ai-gamma.vercel.app/api/analyze")
            .post(requestBody)
            .build()

        // 3. Dispatch Async Network Upload
        Log.d(TAG, "[UPLOAD] Sending HTTP multipart post request async...")
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "[UPLOAD] NETWORK FAILURE: Upload failed for $safeFileName", e)
                
                // Remove from in-memory uploading set so background poller can retry it later
                currentlyUploadingPaths.remove(originalPath)
                Log.d(TAG, "[UPLOAD] Removed $originalPath from currentlyUploadingPaths due to network failure.")
                
                try {
                    val deleted = tempFile.delete()
                    Log.d(TAG, "[UPLOAD] Cache cleanup: Deleted temp cache file: $deleted")
                } catch (ex: Exception) {
                    Log.w(TAG, "[UPLOAD] Failed to clean up temp file", ex)
                }

                // Proceed to next upload
                isUploading = false
                triggerNextUpload()
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val isSuccess = response.isSuccessful
                Log.d(TAG, "[UPLOAD] SERVER RESPONSE -> Code: $code, Success: $isSuccess")
                
                // Remove from in-memory uploading set
                currentlyUploadingPaths.remove(originalPath)
                
                if (isSuccess) {
                    // Save to SharedPreferences ONLY when server confirms successful receive and process
                    val sharedPrefs = getSharedPreferences("SmartCallPrefs", MODE_PRIVATE)
                    val uploadedFiles = HashSet(sharedPrefs.getStringSet("uploaded_files", emptySet()) ?: emptySet())
                    uploadedFiles.add(originalPath)
                    sharedPrefs.edit().putStringSet("uploaded_files", uploadedFiles).apply()
                    Log.d(TAG, "[UPLOAD] Successfully saved $originalPath to SharedPreferences.")
                } else {
                    Log.e(TAG, "[UPLOAD] SERVER ERROR: Response was not successful ($code). File will be allowed to retry.")
                }

                try {
                    val responseBody = response.body?.string() ?: ""
                    Log.d(TAG, "[UPLOAD] SERVER RESPONSE BODY: $responseBody")
                } catch (bodyEx: Exception) {
                    Log.w(TAG, "[UPLOAD] Failed to read response body string", bodyEx)
                } finally {
                    response.close()
                }
                
                try {
                    val deleted = tempFile.delete()
                    Log.d(TAG, "[UPLOAD] Cache cleanup: Deleted temp cache file: $deleted")
                } catch (ex: Exception) {
                    Log.w(TAG, "[UPLOAD] Failed to clean up temp file", ex)
                }

                // Proceed to next upload
                isUploading = false
                triggerNextUpload()
            }
        })
    }
}
