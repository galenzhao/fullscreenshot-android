package com.galenzhao.scrollshot.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Canvas
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Environment
import android.util.Log
import android.view.WindowManager
import com.galenzhao.scrollshot.CaptureRepository
import com.galenzhao.scrollshot.stitch.ImageStitcher
import com.galenzhao.scrollshot.stitch.ScrollDetector
import java.io.File

class FrameCaptureManager(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    /** 顶部要去掉的高度（px）。为 null 时使用系统状态栏高度；若目标 App 顶部还有浮动按钮等，可传入总高度（状态栏+浮动区域）。 */
    private val topCropHeightPx: Int? = null
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

    /** 有效内容区域高度（去掉顶部裁剪后），用于滚动检测与拼接 */
    private var contentHeight = 0
    /** 第一帧被裁掉的顶部条，用于在最终长图前面再贴回去 */
    private var firstTopStrip: Bitmap? = null

    private var prevBitmap: Bitmap? = null
    private var stitchCount = 0
    private lateinit var scrollDetector: ScrollDetector
    private lateinit var imageStitcher: ImageStitcher

    private val statusBarHeight: Int by lazy {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
    }

    /** 实际用于裁剪的顶部高度：用户指定则用指定值，否则用系统状态栏高度 */
    private val effectiveTopCropHeight: Int
        get() = topCropHeightPx ?: statusBarHeight

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

        // 计算参与滚动检测与拼接的内容高度：整体高度减去顶部裁剪高度
        val cropTop = effectiveTopCropHeight
        contentHeight = if (cropTop > 0 && frameHeight > cropTop) {
            frameHeight - cropTop
        } else {
            frameHeight
        }

        // 适当降低处理频率，避免同一次手势滚动捕获过多切片
        processEveryN = when {
            frameHeight >= 2400 -> 5   // 高分屏：大约每 5 帧处理一次
            frameHeight >= 1800 -> 5
            else -> 6                  // 普通屏：大约每 6 帧处理一次
        }
        Log.d(TAG, "Window metrics width=$frameWidth height=$frameHeight contentHeight=$contentHeight density=$frameDensity processEveryN=$processEveryN cropTop=$cropTop")

        // 滚动检测与拼接都基于「裁剪后」的内容区域高度进行，保证尺寸一致
        scrollDetector = ScrollDetector(frameWidth, contentHeight)
        imageStitcher = ImageStitcher(frameWidth, contentHeight)

        handlerThread.start()
        handler = Handler(handlerThread.looper)

        imageReader = ImageReader.newInstance(
            frameWidth, frameHeight, PixelFormat.RGBA_8888, 3
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            if (released) return@setOnImageAvailableListener
            val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
            if (image == null) return@setOnImageAvailableListener
            try {
                if (released) return@setOnImageAvailableListener
                frameIndex++
                if (frameIndex % processEveryN != 0) {
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
            // 第一帧：若配置了顶部裁剪，则拆成「顶部条 + 内容区域」：
            // - 顶部条 firstTopStrip：只保留一次，用于最终长图前面再贴回去
            // - 内容区域：参与滚动检测与后续拼接
            val cropTop = effectiveTopCropHeight
            if (cropTop > 0 && bitmap.height > cropTop && contentHeight == bitmap.height - cropTop) {
                try {
                    firstTopStrip = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, cropTop)
                    val content = Bitmap.createBitmap(bitmap, 0, cropTop, bitmap.width, contentHeight)
                    Log.d(TAG, "[ScrollShot] 第一帧拆分完成 full=${bitmap.width}x${bitmap.height} topStripH=$cropTop contentH=$contentHeight")
                    bitmap.recycle()
                    imageStitcher.addFirstFrame(content)
                    prevBitmap = content
                } catch (e: IllegalArgumentException) {
                    // 裁剪异常时退回整帧逻辑（但此时 contentHeight 与实际高度可能不符，理论上不应出现）
                    Log.w(TAG, "[ScrollShot] 第一帧裁剪失败，退回整帧参与拼接: ${e.message}")
                    imageStitcher.addFirstFrame(bitmap)
                    prevBitmap = bitmap
                }
            } else {
                Log.d(TAG, "[ScrollShot] 未启用顶部裁剪或尺寸不匹配，整帧作为基础 尺寸=${bitmap.width}x${bitmap.height}")
                imageStitcher.addFirstFrame(bitmap)
                prevBitmap = bitmap
            }
            updateFrameCount()
            return
        }

        // 非第一帧：若启用了顶部裁剪，则只截取「内容区域」参与滚动检测与拼接，
        // 确保 prev / current 在高度上完全一致（contentHeight）。
        val cropTop = if (contentHeight < frameHeight) effectiveTopCropHeight else 0
        val currentBitmap = if (cropTop > 0 && bitmap.height >= cropTop + contentHeight) {
            try {
                val content = Bitmap.createBitmap(bitmap, 0, cropTop, bitmap.width, contentHeight)
                bitmap.recycle()
                content
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "[ScrollShot] 非第一帧裁剪失败，退回使用整帧: ${e.message}")
                bitmap
            }
        } else {
            bitmap
        }

        val deltaY = scrollDetector.detectScroll(prev, currentBitmap)
        Log.d(TAG, "[ScrollShot] detectScroll 结果 deltaY=$deltaY")
        if (released) {
            currentBitmap.recycle()
            return
        }
        if (deltaY != null && deltaY > 0) {
            val added = imageStitcher.addFrame(currentBitmap, deltaY)
            if (added) {
                stitchCount++
                Log.d(TAG, "[ScrollShot] 已拼接 当前切片数=$stitchCount")
                updateFrameCount()
                prev.recycle()
                prevBitmap = currentBitmap
            } else {
                Log.w(TAG, "[ScrollShot] addFrame 失败(超限或无效) 本帧丢弃")
                currentBitmap.recycle()
            }
        } else {
            Log.d(TAG, "[ScrollShot] 未检测到滚动 本帧丢弃")
            currentBitmap.recycle()
        }
    }

    private fun updateFrameCount() {
        Log.d(TAG, "updateFrameCount() stitchCount=$stitchCount")
        CaptureRepository.updateState(CaptureRepository.State.Capturing(stitchCount))
    }

    fun buildResult(): Bitmap? {
        // 使用固定的调试目录，并在每次生成结果前清空旧的切片，避免图片无限累积
        val debugDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?.let { base ->
                val rootDir = File(base, "ScrollShotDebug")
                if (rootDir.exists()) {
                    rootDir.deleteRecursively()
                }
                rootDir.mkdirs()
                rootDir
            }
        if (debugDir != null) {
            imageStitcher.debugSaveSlicesTo = debugDir
            CaptureRepository.setLastDebugDir(debugDir.absolutePath)
            Log.d(TAG, "[ScrollShot] 切片目录(应用内查看): ${debugDir.absolutePath}")
        }
        val stitchedContent = imageStitcher.buildResult()
        if (stitchedContent == null) {
            Log.d(TAG, "[ScrollShot] 长图生成为 null")
            return null
        }

        // 如果有第一帧被裁掉的顶部条，则在结果前面再贴回去，得到最终长图
        val topStrip = firstTopStrip
        val finalBitmap = if (topStrip != null && topStrip.width == stitchedContent.width) {
            val finalHeight = topStrip.height + stitchedContent.height
            val result = Bitmap.createBitmap(stitchedContent.width, finalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.drawBitmap(topStrip, 0f, 0f, null)
            canvas.drawBitmap(stitchedContent, 0f, topStrip.height.toFloat(), null)
            Log.d(TAG, "[ScrollShot] 长图生成完成(含顶部条) 尺寸=${result.width}x${result.height} 切片数=$stitchCount")
            // 贴完后即可回收中间图，释放内存
            stitchedContent.recycle()
            result
        } else {
            Log.d(TAG, "[ScrollShot] 长图生成完成(无单独顶部条) 尺寸=${stitchedContent.width}x${stitchedContent.height} 切片数=$stitchCount")
            stitchedContent
        }

        return finalBitmap
    }

    fun release() {
        Log.d(TAG, "release()")
        stopCaptureOnly()
        imageStitcher.release()
        firstTopStrip?.recycle()
        firstTopStrip = null
    }

    /**
     * 仅停止屏幕捕获（VirtualDisplay / ImageReader / 线程），保留已采集的帧用于后续拼接。
     * 用于“用户点了停止后，立刻结束录屏指示，但仍可基于已有帧生成长图”。
     */
    fun stopCaptureOnly() {
        Log.d(TAG, "stopCaptureOnly()")
        released = true
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        // Recycle prevBitmap on the capture thread so we don't recycle it while
        // an in-flight processFrame() is still using it in ScrollDetector.detectScroll().
        handler.post {
            prevBitmap?.recycle()
            prevBitmap = null
            handlerThread.quitSafely()
        }
    }
}
