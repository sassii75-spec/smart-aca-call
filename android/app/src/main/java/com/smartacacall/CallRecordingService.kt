package com.smartacacall

import android.app.*
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
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var academyId: String = "sa_academy"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        academyId = intent?.getStringExtra("ACADEMY_ID") ?: "sa_academy"
        
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Smart Call Agent 실행 중")
            .setContentText("통화 녹음 파일을 감지하고 있습니다.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(1, notification)
        startWatching()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        fileObservers.forEach { it.stopWatching() }
        fileObservers.clear()
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

    private fun startWatching() {
        Log.d(TAG, "Starting MediaStore ContentObserver for Android 11+ compatibility")
        contentObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                super.onChange(selfChange, uri)
                Log.d(TAG, "MediaStore changed: $uri")
                checkLatestAudioFile()
            }
        }
        
        try {
            contentResolver.registerContentObserver(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                contentObserver!!
            )
            Log.d(TAG, "Successfully registered MediaStore observer.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register ContentObserver", e)
        }
    }

    private fun checkLatestAudioFile() {
        val projection = arrayOf(
            android.provider.MediaStore.Audio.Media.DATA, 
            android.provider.MediaStore.Audio.Media.DATE_ADDED
        )
        val sortOrder = "${android.provider.MediaStore.Audio.Media.DATE_ADDED} DESC"
        
        try {
            contentResolver.query(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val dataIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATA)
                val dateAddedIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATE_ADDED)
                
                var count = 0
                val sharedPrefs = getSharedPreferences("SmartCallPrefs", MODE_PRIVATE)
                val uploadedFiles = HashSet(sharedPrefs.getStringSet("uploaded_files", emptySet()) ?: emptySet())
                val currentTimeSeconds = System.currentTimeMillis() / 1000
                
                while (cursor.moveToNext() && count < 10) {
                    count++
                    val filePath = cursor.getString(dataIndex) ?: continue
                    val dateAdded = cursor.getLong(dateAddedIndex)
                    
                    // 1. 최근 10분(600초) 이내에 추가된 파일인지 확인 (바로 끝난 녹음 감지용)
                    val isRecent = (currentTimeSeconds - dateAdded) < 600
                    
                    // 2. 이미 업로드된 파일이 아닌지 확인
                    val isAlreadyUploaded = uploadedFiles.contains(filePath)
                    
                    if (isRecent && !isAlreadyUploaded) {
                        // 3. 파일명 매칭 검사
                        if (filePath.contains("Call", ignoreCase = true) || 
                            filePath.contains("Recordings", ignoreCase = true) || 
                            filePath.contains("통화", ignoreCase = true) || 
                            filePath.contains("녹음", ignoreCase = true) || 
                            filePath.contains("Voice", ignoreCase = true) || 
                            filePath.contains("Audio", ignoreCase = true)) {
                            
                            Log.d(TAG, "Found new recording matching filter: $filePath (Added ${currentTimeSeconds - dateAdded}s ago)")
                            
                            // 중복 업로드 방지를 위해 즉시 추가 후 저장
                            uploadedFiles.add(filePath)
                            sharedPrefs.edit().putStringSet("uploaded_files", uploadedFiles).apply()
                            
                            // 파일 쓰기가 완료될 시간을 주기 위해 3초 대기 후 업로드
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                uploadFile(filePath)
                            }, 3000)
                            
                            // 한 번의 감지 이벤트에서는 가장 최근의 1개 파일만 업로드하도록 탈출
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking MediaStore", e)
        }
    }

    private fun uploadFile(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) return

        // 파일명 한글 깨짐 및 특수문자, 극단적인 길이로 인한 서버 파싱 에러 방지를 위해 업로드 시 영문 안전한 파일명으로 변환
        val extension = file.name.substringAfterLast('.', "m4a")
        val safeFileName = "recording_${System.currentTimeMillis()}.$extension"

        Log.d(TAG, "Uploading file: $filePath as $safeFileName to academy: $academyId")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("academyId", academyId)
            .addFormDataPart(
                "file",
                safeFileName,
                file.asRequestBody("audio/*".toMediaTypeOrNull())
            )
            .build()

        val request = Request.Builder()
            .url("https://smart-call-ai-gamma.vercel.app/api/analyze")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Upload failed", e)
            }

            override fun onResponse(call: Call, response: Response) {
                Log.d(TAG, "Upload success: ${response.code}")
                response.close()
            }
        })
    }
}
