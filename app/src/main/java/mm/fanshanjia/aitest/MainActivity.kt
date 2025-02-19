package mm.fanshanjia.aitest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.webkit.WebView
import android.widget.Button
import androidx.activity.ComponentActivity


class MainActivity : ComponentActivity() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var isRecording = false
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
