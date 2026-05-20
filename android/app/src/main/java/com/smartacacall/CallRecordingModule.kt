package com.smartacacall

import android.content.Intent
import android.os.Build
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

class CallRecordingModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String {
        return "CallRecordingAgent"
    }

    @ReactMethod
    fun startAgent(academyId: String) {
        val intent = Intent(reactContext, CallRecordingService::class.java)
        intent.putExtra("ACADEMY_ID", academyId)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            reactContext.startForegroundService(intent)
        } else {
            reactContext.startService(intent)
        }
    }

    @ReactMethod
    fun stopAgent() {
        val intent = Intent(reactContext, CallRecordingService::class.java)
        reactContext.stopService(intent)
    }
}
