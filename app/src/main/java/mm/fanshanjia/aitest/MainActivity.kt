package mm.fanshanjia.aitest

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.webkit.WebView
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var isRecording = false
    private var resultCode: Int = 0
    private var resultData: Intent? = null

    private val startForResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            resultCode = result.resultCode
            resultData = result.data
            startScreenRecordingService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        findViewById<WebView>(R.id.web_view).loadUrl("https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4")
        findViewById<Button>(R.id.record_button).setOnClickListener {
            if (isRecording) {
                stopScreenRecording()
            } else {
                ScreenRecordingService.checkPermissionsAndStartRecording(this)
            }
        }
    }



    private fun startScreenRecordingService() {
        try {
            val serviceIntent = Intent(this, ScreenRecordingService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("resultData", resultData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            isRecording = true
            findViewById<Button>(R.id.record_button).text = "停止"
        } catch (e: Exception) {
            Log.e("MainActivity", "启动服务失败: ${e.message}", e)
            Toast.makeText(this, "启动录制服务失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopScreenRecording() {
        val serviceIntent = Intent(this, ScreenRecordingService::class.java)
        stopService(serviceIntent)
        isRecording = false
        findViewById<Button>(R.id.record_button).text = "开始"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ScreenRecordingService.SCREEN_CAPTURE_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            isRecording = true
            findViewById<Button>(R.id.record_button).text = "停止"
            ScreenRecordingService.startService(this, resultCode, data)
        }
    }
}
