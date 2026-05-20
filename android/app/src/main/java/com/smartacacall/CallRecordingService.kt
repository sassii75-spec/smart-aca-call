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
        val projection = arrayOf(android.provider.MediaStore.Audio.Media.DATA, android.provider.MediaStore.Audio.Media.DATE_ADDED)
        val sortOrder = "${android.provider.MediaStore.Audio.Media.DATE_ADDED} DESC"
        
        try {
            contentResolver.query(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dataIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATA)
                    val filePath = cursor.getString(dataIndex)
                    
                    if (filePath != null && filePath != lastUploadedFile) {
                        if (filePath.contains("Call", ignoreCase = true) || filePath.contains("Recordings", ignoreCase = true)) {
                            Log.d(TAG, "Found new recording via MediaStore: $filePath")
                            lastUploadedFile = filePath
                            
                            // 파일 쓰기가 완료될 시간을 주기 위해 3초 대기 후 업로드
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                uploadFile(filePath)
                            }, 3000)
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

        Log.d(TAG, "Uploading file: $filePath to academy: $academyId")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("academyId", academyId)
            .addFormDataPart(
                "file",
                file.name,
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
