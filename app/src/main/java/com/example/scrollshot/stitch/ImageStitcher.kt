package com.galenzhao.scrollshot.stitch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import java.io.File
import java.io.FileOutputStream

/**
 * 图像拼接器：按「整帧重叠覆盖」的方式生成长截图。
 *
 * - 第一帧整帧作为长图顶部。
 * - 后续每一帧存整帧，通过 deltaY 计算在长图中的纵向偏移 topY；
 *   整帧画在 topY，重叠区域由新帧覆盖上一帧底部（含底部 bar），不覆盖长图更上方。
 * - 可通过 [debugSaveSlicesTo] 将每个切片保存到指定目录，供应用内「查看切片」界面使用。
 */
class ImageStitcher(
    private val frameWidth: Int,
    private val frameHeight: Int
) {

    companion object {
        private const val MAX_TOTAL_HEIGHT = 16000 // 单张最大高度（px）
    }

    private val frames = mutableListOf<Bitmap>()
    private val deltas = mutableListOf<Int>() // deltas[i] = 第 i+1 帧相对第 i 帧的 deltaY
    private var totalScroll = 0

    /** 若设置，buildResult 时会把每个切片（整帧）保存到此目录，供应用内查看：slice_001.png, slice_002.png, ... result.png */
    var debugSaveSlicesTo: File? = null

    fun addFirstFrame(bitmap: Bitmap) {
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        frames.add(copy)
        totalScroll = 0
    }

    /**
     * 添加新的一帧（整帧）。
     * @param currBitmap 当前帧（整帧）
     * @param deltaY 本帧与上一帧之间内容上移的像素数（重合高度）。
     */
    fun addFrame(currBitmap: Bitmap, deltaY: Int): Boolean {
        if (frames.isEmpty()) {
            addFirstFrame(currBitmap)
            return true
        }
        if (deltaY <= 0) return false

        val clampedDelta = deltaY.coerceAtMost(frameHeight)
        val resultHeight = frameHeight + totalScroll + clampedDelta
        if (resultHeight > MAX_TOTAL_HEIGHT) return false

        val copy = currBitmap.copy(Bitmap.Config.ARGB_8888, false)
        frames.add(copy)
        deltas.add(clampedDelta)
        totalScroll += clampedDelta
        return true
    }

    /**
     * 按重合位置覆盖合成：第一帧画在顶部，后续每帧画在 topY，覆盖上一帧底部。
     */
    fun buildResult(): Bitmap? {
        if (frames.isEmpty()) return null

        val resultHeight = (frameHeight + totalScroll).coerceAtMost(MAX_TOTAL_HEIGHT)
        val result = Bitmap.createBitmap(frameWidth, resultHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val debugDir = debugSaveSlicesTo
        if (debugDir != null) debugDir.mkdirs()

        // 第一帧整帧画在顶部
        canvas.drawBitmap(frames[0], 0f, 0f, null)
        if (debugDir != null) saveBitmap(frames[0], File(debugDir, "slice_001.png"))

        var topY = 0
        for (i in 1 until frames.size) {
            topY += deltas[i - 1]
            if (topY >= resultHeight) break

            val frame = frames[i]
            val drawHeight = (frame.height.coerceAtMost(frameHeight)).coerceAtMost(resultHeight - topY)
            if (drawHeight <= 0) continue

            canvas.drawBitmap(
                frame,
                Rect(0, 0, frameWidth, drawHeight),
                Rect(0, topY, frameWidth, topY + drawHeight),
                null
            )
            if (debugDir != null) saveBitmap(frame, File(debugDir, "slice_%03d.png".format(i + 1)))
        }

        if (debugDir != null) saveBitmap(result, File(debugDir, "result.png"))

        frames.forEach { it.recycle() }
        frames.clear()
        deltas.clear()
        totalScroll = 0

        return result
    }

    private fun saveBitmap(bitmap: Bitmap, file: File) {
        try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) { }
    }

    fun release() {
        frames.forEach { it.recycle() }
        frames.clear()
        deltas.clear()
        totalScroll = 0
    }
}
