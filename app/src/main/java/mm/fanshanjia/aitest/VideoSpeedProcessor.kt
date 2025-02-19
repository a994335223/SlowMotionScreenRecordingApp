package mm.fanshanjia.aitest

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

class VideoSpeedProcessor {
    companion object {
        private const val TAG = "VideoSpeedProcessor"
    }

    fun processVideo(inputPath: String, outputPath: String, speedFactor: Float = 0.1f) {
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(inputPath)

            // 创建MediaMuxer
            val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            // 处理视频轨道
            val videoIndex = selectTrack(extractor, "video/")
            if (videoIndex < 0) {
                Log.e(TAG, "No video track found")
                return
            }
            
            extractor.selectTrack(videoIndex)
            val format = extractor.getTrackFormat(videoIndex)
            val muxerTrackIndex = muxer.addTrack(format)
            
            // 开始处理
            muxer.start()
            
            val bufferSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            val buffer = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()
            
            // 调整时间戳来改变播放速度
            var lastPresentationTimeUs = 0L
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                
                bufferInfo.size = sampleSize
                bufferInfo.offset = 0
                bufferInfo.flags = extractor.sampleFlags
                
                // 修改时间戳来实现慢速播放
                bufferInfo.presentationTimeUs = (extractor.sampleTime / speedFactor).toLong()
                
                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                extractor.advance()
            }
            
            // 清理资源
            muxer.stop()
            muxer.release()
            extractor.release()
            
            // 删除原始文件
            File(inputPath).delete()
            // 重命名处理后的文件
            File(outputPath).renameTo(File(inputPath))
            
            Log.i(TAG, "视频速度处理完成")
        } catch (e: Exception) {
            Log.e(TAG, "处理视频时出错", e)
        }
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith(mimePrefix) == true) {
                return i
            }
        }
        return -1
    }
} 