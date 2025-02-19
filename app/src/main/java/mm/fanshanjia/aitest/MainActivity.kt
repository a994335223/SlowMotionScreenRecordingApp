package mm.fanshanjia.aitest

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var isRecording = false
    private var resultCode: Int = 0
    private var resultData: Intent? = null

    private val permissions = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {  // Android 14
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.FOREGROUND_SERVICE,
                Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION
            )
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {  // Android 13
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.FOREGROUND_SERVICE
            )
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {  // Android 12
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.FOREGROUND_SERVICE
            )
        }
        else -> {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.FOREGROUND_SERVICE
            )
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        when {
            permissions.all { it.value } -> {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager() -> {
                        requestManageStoragePermission()
                    }
                    else -> {
                        startScreenCapture()
                    }
                }
            }
            else -> {
                Toast.makeText(this, "需要所有权限才能进行屏幕录制", Toast.LENGTH_SHORT).show()
                // 显示权限说明对话框
                showPermissionExplanationDialog()
            }
        }
    }

    private val manageStorageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            startScreenCapture()
        } else {
            Toast.makeText(this, "需要存储管理权限才能进行屏幕录制", Toast.LENGTH_SHORT).show()
        }
    }

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
                checkPermissionsAndStartRecording()
            }
        }
    }

    private fun checkPermissionsAndStartRecording() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (!hasRequiredPermissions()) {
                    permissionLauncher.launch(permissions)
                    return
                }
                // 检查特殊权限
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    requestManageStoragePermission()
                    return
                }
                startScreenCapture()
            }
            else -> {
                if (hasRequiredPermissions()) {
                    startScreenCapture()
                } else {
                    permissionLauncher.launch(permissions)
                }
            }
        }
    }

    private fun requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            manageStorageLauncher.launch(intent)
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val hasBasicPermissions = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        
        // 记录日志
        permissions.forEach { permission ->
            val isGranted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            Log.d("Permission", "$permission: ${if (isGranted) "已授予" else "未授予"}")
        }
        
        return hasBasicPermissions
    }

    private fun startScreenCapture() {
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        startForResult.launch(intent)
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

    private fun showPermissionExplanationDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要权限")
            .setMessage("录制屏幕需要以下权限：\n" +
                    "1. 录音权限：用于录制声音\n" +
                    "2. 存储权限：用于保存录制的视频\n" +
                    "3. 前台服务权限：用于保持录制在后台运行\n" +
                    "请在设置中开启这些权限。")
            .setPositiveButton("去设置") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = android.net.Uri.fromParts("package", packageName, null)
        startActivity(intent)
    }
}
