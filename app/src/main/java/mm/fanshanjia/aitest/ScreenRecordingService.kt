package mm.fanshanjia.aitest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
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
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import java.io.File
import java.nio.ByteBuffer

class ScreenRecordingService : Service() {
    companion object {
        private const val TAG = "ScreenRecordingService"
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_BITRATE = 5000000 // 5Mbps
        private const val FRAME_RATE = 30
        private const val I_FRAME_INTERVAL = 1
        private const val SPEED_FACTOR = 10L // 0.1倍速
        private const val TIMEOUT_USEC = 10000L
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var surface: Surface? = null
    private var isRecording = false
    private var videoTrackIndex = -1
    private var currentVideoPath: String? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null

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

    private fun setupMediaCodec() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = createInputSurface()
            start()
        }

        // 创建输出文件和MediaMuxer
//        currentVideoPath = "${getExternalFilesDir(null)}/screen_recording_${System.currentTimeMillis()}.mp4"
        currentVideoPath = getOutputFilePath()
        Log.e(TAG, "创建输出文件和MediaMuxer"+currentVideoPath)
        mediaMuxer = MediaMuxer(currentVideoPath!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun getOutputFilePath(): String {
        val timestamp = System.currentTimeMillis()
//        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val file = File(directory, "screen_recording_$timestamp.mp4")
        currentVideoPath = file.absolutePath
        return currentVideoPath.toString()
    }

    private fun setupVirtualDisplay() {
        // 创建并注册回调
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
            VIDEO_WIDTH, VIDEO_HEIGHT, 1,
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