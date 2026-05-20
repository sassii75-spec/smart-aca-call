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

    private fun startWatching() {
        // 삼성 갤럭시 및 일반 안드로이드 녹음 폴더 경로들
        val paths = listOf(
            Environment.getExternalStorageDirectory().absolutePath + "/Recordings/Call",
            Environment.getExternalStorageDirectory().absolutePath + "/Music/Call",
            Environment.getExternalStorageDirectory().absolutePath + "/Call"
        )

        for (path in paths) {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                Log.d(TAG, "Watching directory: $path")
                val observer = object : FileObserver(path, CLOSE_WRITE) {
                    override fun onEvent(event: Int, file: String?) {
                        if (file != null && (file.endsWith(".m4a") || file.endsWith(".mp3") || file.endsWith(".amr"))) {
                            val fullPath = "$path/$file"
                            Log.d(TAG, "New recording detected: $fullPath")
                            uploadFile(fullPath)
                        }
                    }
                }
                observer.startWatching()
                fileObservers.add(observer)
            }
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
            .url("https://smart-call-ai.vercel.app/api/analyze")
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
