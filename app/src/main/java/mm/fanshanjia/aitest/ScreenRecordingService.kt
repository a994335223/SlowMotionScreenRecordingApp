package mm.fanshanjia.aitest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer
import android.Manifest
import android.app.Activity
import android.content.Context

class ScreenRecordingService : Service() {
    companion object {
        private const val TAG = "ScreenRecordingService"  // 日志标签
        private const val SPEED_FACTOR = 10L // 速度因子（0.1倍速）
        private const val TIMEOUT_USEC = 10000L  // 超时时间（微秒）
        const val SCREEN_CAPTURE_REQUEST_CODE = 1001

        fun checkPermissionsAndStartRecording(activity: Activity) {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    if (!hasRequiredPermissions(activity)) {
                        requestPermissions(activity)
                        return
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                        requestManageStoragePermission(activity)
                        return
                    }
                    startScreenCapture(activity)
                }
                else -> {
                    if (hasRequiredPermissions(activity)) {
                        startScreenCapture(activity)
                    } else {
                        requestPermissions(activity)
                    }
                }
            }
        }

        private fun hasRequiredPermissions(context: Context): Boolean {
            return getRequiredPermissions(context).all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }

        private fun getRequiredPermissions(context: Context): Array<String> {
            return when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.POST_NOTIFICATIONS,
                        Manifest.permission.FOREGROUND_SERVICE,
                        Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION
                    )
                }
                // ... 其他版本的权限定义 ...
                else -> {
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.FOREGROUND_SERVICE
                    )
                }
            }
        }

        private fun requestPermissions(activity: Activity) {
            ActivityCompat.requestPermissions(
                activity,
                getRequiredPermissions(activity),
                SCREEN_CAPTURE_REQUEST_CODE
            )
        }

        private fun requestManageStoragePermission(activity: Activity) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    activity.startActivity(intent)
                }
            }
        }

        private fun startScreenCapture(activity: Activity) {
            val mediaProjectionManager = activity.getSystemService(MediaProjectionManager::class.java)
            activity.startActivityForResult(
                mediaProjectionManager.createScreenCaptureIntent(),
                SCREEN_CAPTURE_REQUEST_CODE
            )
        }

        fun startService(context: Context, resultCode: Int, data: Intent) {
            val serviceIntent = Intent(context, ScreenRecordingService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("resultData", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    // 动态参数
    private var videoWidth = 1280    // 视频宽度
    private var videoHeight = 720    // 视频高度
    private var videoBitRate = 0     // 视频比特率
    private var frameRate = 30       // 帧率
    private var screenDensity = 1    // 屏幕密度

    private var mediaProjection: MediaProjection? = null  // 媒体投影，用于屏幕捕获
    private var virtualDisplay: VirtualDisplay? = null    // 虚拟显示器
    private var mediaCodec: MediaCodec? = null           // 媒体编码器
    private var mediaMuxer: MediaMuxer? = null          // 媒体混合器
    private var surface: Surface? = null                 // 录制表面
    private var isRecording = false                      // 录制状态标志
    private var videoTrackIndex = -1                     // 视频轨道索引
    private var currentVideoPath: String? = null         // 当前视频保存路径
    private var mediaProjectionCallback: MediaProjection.Callback? = null  // 媒体投影回调

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            createNotificationChannel()
            val notification = createNotification()
            startForeground(1, notification)

            val resultCode = intent?.getIntExtra("resultCode", -6) ?: -6
            val resultData = intent?.getParcelableExtra<Intent>("resultData")

            if (resultCode != -6&& resultData != null) {
                val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
                startRecording()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Service start error", e)
            stopSelf()
        }
        return START_STICKY
    }

    private fun startRecording() {
        try {
            setupMediaCodec()
            setupVirtualDisplay()
            startEncodingThread()
            isRecording = true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            stopSelf()
        }
    }

    private fun initRecordingParams() {
        // 获取屏幕参数
        val windowManager = getSystemService(WindowManager::class.java)
        val display = windowManager.defaultDisplay
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)

        // 设置屏幕密度
        screenDensity = metrics.densityDpi

        // 设置视频分辨率（根据屏幕分辨率调整，但限制最大值）
        videoWidth = metrics.widthPixels
        videoHeight = metrics.heightPixels

        // 如果分辨率太高，按比例缩小
        val maxDimension = 1920 // 最大支持1920x1080
        if (videoWidth > maxDimension || videoHeight > maxDimension) {
            val ratio = videoWidth.toFloat() / videoHeight.toFloat()
            if (videoWidth > videoHeight) {
                videoWidth = maxDimension
                videoHeight = (maxDimension / ratio).toInt()
            } else {
                videoHeight = maxDimension
                videoWidth = (maxDimension * ratio).toInt()
            }
        }

        // 设置比特率（根据分辨率动态计算）
        videoBitRate = calculateBitRate(videoWidth, videoHeight)

        // 获取最优帧率
        frameRate = getOptimalFrameRate()

        Log.i(TAG, """
            录制参数：
            分辨率: ${videoWidth}x${videoHeight}
            比特率: $videoBitRate
            帧率: $frameRate
            屏幕密度: $screenDensity
        """.trimIndent())
    }

    private fun calculateBitRate(width: Int, height: Int): Int {
        // 基础比特率计算公式：width * height * frameRate * 0.07 (系数可调)
        val baseBitRate = (width * height * frameRate * 0.07).toInt()
        
        // 限制最小和最大比特率
        val minBitRate = 1_000_000  // 1 Mbps
        val maxBitRate = 10_000_000 // 10 Mbps
        
        return baseBitRate.coerceIn(minBitRate, maxBitRate)
    }

    private fun getOptimalFrameRate(): Int {
        try {
            // 获取支持的帧率范围
            val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            
            // 选择最适合的帧率
            return fpsRanges?.maxByOrNull { it.upper }?.upper ?: 30
        } catch (e: Exception) {
            Log.e(TAG, "获取最优帧率失败，使用默认值30", e)
            return 30
        }
    }

    private fun setupMediaCodec() {
        // 初始化录制参数
        initRecordingParams()

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            
            // 设置编码器性能模式
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_QUALITY, 100)
                setInteger(MediaFormat.KEY_PRIORITY, 0)  // 实时编码优先级
                setInteger(MediaFormat.KEY_LATENCY, 0)   // 低延迟模式
            }
        }

        // 选择最优编码器
        val codecName = selectCodec(MediaFormat.MIMETYPE_VIDEO_AVC)
        if (codecName != null) {
            mediaCodec = MediaCodec.createByCodecName(codecName)
        } else {
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        }

        mediaCodec?.apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = createInputSurface()
            start()
        }

        // 创建输出文件和MediaMuxer
        currentVideoPath = getOutputFilePath()
        Log.e(TAG, "创建输出文件和MediaMuxer"+currentVideoPath)
        mediaMuxer = MediaMuxer(currentVideoPath!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun selectCodec(mimeType: String): String? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val codecInfo = codecList.codecInfos
        
        // 查找支持指定MIME类型的编码器
        return codecInfo
            .filter { it.isEncoder && it.supportedTypes.contains(mimeType) }
            .maxByOrNull { getCodecPriority(it) }
            ?.name
    }

    private fun getCodecPriority(codecInfo: MediaCodecInfo): Int {
        // 优先选择硬件编码器
        return when {
            codecInfo.name.startsWith("c2.android") -> 3    // 新版硬件编码器
            codecInfo.name.startsWith("OMX.google") -> 1    // 软件编码器
            codecInfo.name.startsWith("OMX.") -> 2          // 旧版硬件编码器
            else -> 0
        }
    }

    private fun getOutputFilePath(): String {
        val timestamp = System.currentTimeMillis()
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val file = File(directory, "screen_recording_$timestamp.mp4")
        currentVideoPath = file.absolutePath
        return currentVideoPath.toString()
    }

    private fun setupVirtualDisplay() {
        // 创建媒体投影回调
        mediaProjectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped")
                stopRecording()
            }
        }
        
        // 注册回调
        mediaProjection?.registerCallback(mediaProjectionCallback!!, null)

        // 创建虚拟显示
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenRecorder",
            videoWidth, videoHeight, screenDensity,  // 使用实际的屏幕密度
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )
    }

    private fun startEncodingThread() {
        Thread {
            try {
                encodeAndMux()
            } catch (e: Exception) {
                Log.e(TAG, "Encoding error", e)
            }
        }.start()
    }

    private fun encodeAndMux() {
        val bufferInfo = MediaCodec.BufferInfo()
        var muxerStarted = false

        try {
            while (isRecording) {
                if (!isRecording) break

                val outputBufferId = mediaCodec?.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
                when (outputBufferId) {
                    null -> {
                        Log.e(TAG, "MediaCodec is null or released")
                        break
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {} // -1, Try again
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { // -2
                        if (!muxerStarted) {
                            val format = mediaCodec?.getOutputFormat()
                            videoTrackIndex = mediaMuxer?.addTrack(format!!) ?: -1
                            mediaMuxer?.start()
                            muxerStarted = true
                        }
                    }
                    else -> {
                        if (outputBufferId >= 0) {  // 确保 outputBufferId 是有效的正数
                            val encodedBuffer = mediaCodec?.getOutputBuffer(outputBufferId)
                            if (encodedBuffer != null) {
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    bufferInfo.size = 0
                                }

                                if (bufferInfo.size != 0) {
                                    if (muxerStarted) {
                                        // 调整时间戳实现0.1倍速
                                        bufferInfo.presentationTimeUs *= SPEED_FACTOR
                                        mediaMuxer?.writeSampleData(videoTrackIndex, encodedBuffer, bufferInfo)
                                    }
                                }

                                try {
                                    mediaCodec?.releaseOutputBuffer(outputBufferId, false)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error releasing output buffer", e)
                                }

                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    break
                                }
                            }
                        } else {
                            Log.w(TAG, "Unexpected negative output buffer ID: $outputBufferId")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encoding error", e)
        } finally {
            try {
                releaseEncoder()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing resources", e)
            }
        }
    }

    private fun releaseEncoder() {
        try {
            isRecording = false
            
            // 按顺序释放资源
            virtualDisplay?.release()
            virtualDisplay = null

            mediaCodec?.signalEndOfInputStream()
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null

            surface?.release()
            surface = null

            if (videoTrackIndex != -1) {
                try {
                    mediaMuxer?.stop()
                    mediaMuxer?.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping mediaMuxer", e)
                }
            }
            mediaMuxer = null

            // 取消注册回调
            mediaProjectionCallback?.let {
                mediaProjection?.unregisterCallback(it)
            }
            mediaProjectionCallback = null
            
            mediaProjection?.stop()
            mediaProjection = null

            // 添加到媒体库
            addVideoToMediaStore()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing encoder", e)
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "screen_recording_channel",
                "Screen Recording",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 
            0, 
            intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, "screen_recording_channel")
            .setContentTitle("屏幕录制中")
            .setContentText("正在录制屏幕...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun addVideoToMediaStore() {
        currentVideoPath?.let { path ->
            try {
                val file = File(path)
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                    put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                    put(MediaStore.Video.Media.SIZE, file.length())

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Android 10 及以上使用相对路径
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    } else {
                        // Android 9 及以下使用完整文件路径
                        put(MediaStore.Video.Media.DATA, file.absolutePath)
                    }
                }

                // 插入媒体库
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10 及以上需要标记文件完成
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    uri?.let { contentResolver.update(it, values, null, null) }
                }

                // 发送广播通知媒体库更新
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                        data = Uri.fromFile(file)
                    }
                    sendBroadcast(intent)
                }

                Log.i(TAG, "视频已添加到媒体库")

                // 在 addVideoToMediaStore 方法最后添加
                Handler(Looper.getMainLooper()).postDelayed({
                    // 检查文件是否已添加到媒体库
                    val cursor = contentResolver.query(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        null,
                        MediaStore.Video.Media.DISPLAY_NAME + "=?",
                        arrayOf(file.name),
                        null
                    )
                    cursor?.use {
                        if (it.moveToFirst()) {
                            Log.i(TAG, "确认视频已成功添加到媒体库")
                        } else {
                            Log.e(TAG, "视频可能未成功添加到媒体库")
                        }
                    }
                }, 1000)
            } catch (e: Exception) {
                Log.e(TAG, "添加视频到媒体库失败", e)
            }
        }
    }
}