package com.example.scrollshot.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Environment
import android.util.Log
import android.view.WindowManager
import com.example.scrollshot.CaptureRepository
import com.example.scrollshot.stitch.ImageStitcher
import com.example.scrollshot.stitch.ScrollDetector
import java.io.File

class FrameCaptureManager(
    private val context: Context,
    private val mediaProjection: MediaProjection
) {
    companion object {
        private const val TAG = "FrameCaptureManager"
    }
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handlerThread = HandlerThread("FrameCaptureThread")
    private lateinit var handler: Handler

    private var frameWidth = 0
    private var frameHeight = 0
    private var frameDensity = 0

    private var prevBitmap: Bitmap? = null
    private var stitchCount = 0
    private lateinit var scrollDetector: ScrollDetector
    private lateinit var imageStitcher: ImageStitcher

    private var frameIndex = 0
    /** 每隔 N 帧处理一次，根据分辨率动态设置（高分辨率用更小 N 以免漏检） */
    private var processEveryN = 3

    /** release() 后不再处理任何帧，避免访问已释放的 ImageReader/Image 导致 SIGSEGV */
    @Volatile
    private var released = false

    fun start() {
        Log.d(TAG, "start() begin")
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        frameWidth = bounds.width()
        frameHeight = bounds.height()
        frameDensity = context.resources.displayMetrics.densityDpi
        // 适当降低处理频率，避免同一次手势滚动捕获过多切片
        processEveryN = when {
            frameHeight >= 2400 -> 5   // 高分屏：大约每 5 帧处理一次
            frameHeight >= 1800 -> 5
            else -> 6                  // 普通屏：大约每 6 帧处理一次
        }
        Log.d(TAG, "Window metrics width=$frameWidth height=$frameHeight density=$frameDensity processEveryN=$processEveryN")

        scrollDetector = ScrollDetector(frameWidth, frameHeight)
        imageStitcher = ImageStitcher(frameWidth, frameHeight)

        handlerThread.start()
        handler = Handler(handlerThread.looper)

        imageReader = ImageReader.newInstance(
            frameWidth, frameHeight, PixelFormat.RGBA_8888, 3
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            if (released) return@setOnImageAvailableListener
            Log.d(TAG, "onImageAvailable() called")
            val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
            if (image == null) return@setOnImageAvailableListener
            try {
                if (released) return@setOnImageAvailableListener
                frameIndex++
                if (frameIndex % processEveryN != 0) {
                    Log.d(TAG, "Skipping frame $frameIndex (processEveryN=$processEveryN)")
                    return@setOnImageAvailableListener
                }
                val bitmap = imageToBitmap(image) ?: return@setOnImageAvailableListener
                if (released) {
                    bitmap.recycle()
                    return@setOnImageAvailableListener
                }
                processFrame(bitmap)
            } finally {
                image.close()
            }
        }, handler)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScrollShotDisplay",
            frameWidth, frameHeight, frameDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
        Log.d(TAG, "VirtualDisplay created: $virtualDisplay")
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        if (released) return null
        return try {
            val planes = image.planes
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * frameWidth
            val paddedWidth = frameWidth + rowPadding / pixelStride

            val bmp = Bitmap.createBitmap(paddedWidth, frameHeight, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(planes[0].buffer)

            if (rowPadding != 0) {
                val cropped = Bitmap.createBitmap(bmp, 0, 0, frameWidth, frameHeight)
                bmp.recycle()
                cropped
            } else {
                bmp
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun processFrame(bitmap: Bitmap) {
        Log.d(TAG, "[ScrollShot] processFrame  frameIndex=$frameIndex stitchCount=$stitchCount prev=${prevBitmap != null}")
        val prev = prevBitmap
        if (prev == null) {
            // 第一帧：直接作为基础
            Log.d(TAG, "[ScrollShot] 第一帧作为基础 尺寸=${bitmap.width}x${bitmap.height}")
            imageStitcher.addFirstFrame(bitmap)
            prevBitmap = bitmap
            updateFrameCount()
            return
        }

        val deltaY = scrollDetector.detectScroll(prev, bitmap)
        Log.d(TAG, "[ScrollShot] detectScroll 结果 deltaY=$deltaY")
        if (deltaY != null && deltaY > 0) {
            val added = imageStitcher.addFrame(bitmap, deltaY)
            if (added) {
                stitchCount++
                Log.d(TAG, "[ScrollShot] 已拼接 当前切片数=$stitchCount")
                updateFrameCount()
                prev.recycle()
                prevBitmap = bitmap
            } else {
                Log.w(TAG, "[ScrollShot] addFrame 失败(超限或无效) 本帧丢弃")
                bitmap.recycle()
            }
        } else {
            Log.d(TAG, "[ScrollShot] 未检测到滚动 本帧丢弃")
            bitmap.recycle()
        }
    }

    private fun updateFrameCount() {
        Log.d(TAG, "updateFrameCount() stitchCount=$stitchCount")
        CaptureRepository.updateState(CaptureRepository.State.Capturing(stitchCount))
    }

    fun buildResult(): Bitmap? {
        // 保存每个切片到应用目录，供应用内「查看切片」使用（ResultActivity 内可点「查看切片」进入）
        val debugDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.let { File(it, "ScrollShotDebug") }
        if (debugDir != null) {
            imageStitcher.debugSaveSlicesTo = debugDir
            CaptureRepository.setLastDebugDir(debugDir.absolutePath)
            Log.d(TAG, "[ScrollShot] 切片目录(应用内查看): ${debugDir.absolutePath}")
        }
        val result = imageStitcher.buildResult()
        if (result != null) {
            Log.d(TAG, "[ScrollShot] 长图生成完成 尺寸=${result.width}x${result.height} 切片数=$stitchCount")
        } else {
            Log.d(TAG, "[ScrollShot] 长图生成为 null")
        }
        return result
    }

    fun release() {
        Log.d(TAG, "release()")
        released = true
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        handlerThread.quitSafely()
        prevBitmap?.recycle()
        prevBitmap = null
        imageStitcher.release()
    }
}
