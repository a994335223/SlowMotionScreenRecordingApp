package mm.fanshanjia.aitest

import android.app.Application

class MyApplication : Application() {
    lateinit var screenRecordingService: ScreenRecordingService

    override fun onCreate() {
        super.onCreate()
        screenRecordingService = ScreenRecordingService()
    }
} 