package com.galenzhao.scrollshot.stitch

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.roundToInt

/**
 * 滚动检测器：通过模板匹配计算两帧之间的垂直偏移量（deltaY）。
 * 下采样 + SAD（Sum of Absolute Differences）搜索，性能友好。
 *
 * 所有可调参数均根据 [frameWidth]、[frameHeight] 动态计算，无需手改常量。
 */
class ScrollDetector(private val frameWidth: Int, private val frameHeight: Int) {

    companion object {
        private const val TAG = "ScrollShot_ScrollDetect"
    }

    /** 根据当前分辨率算出的检测参数，构造时一次性计算 */
    private val config = ScrollDetectConfig.fromResolution(frameWidth, frameHeight)

    private val sw = (frameWidth * config.scale).toInt().coerceAtLeast(1)
    private val sh = (frameHeight * config.scale).toInt().coerceAtLeast(1)
    private val templateY = (sh * config.templateTopRatio).toInt()

    /**
     * 检测 prevBitmap -> currBitmap 之间内容向上滚动的像素数。
     * 返回值 > 0 表示发生了有效滚动（内容上移了 deltaY 个原始像素）。
     * 返回 null 表示未检测到有效滚动。
     */
    fun detectScroll(prevBitmap: Bitmap, currBitmap: Bitmap): Int? {
        val scaledPrev = Bitmap.createScaledBitmap(prevBitmap, sw, sh, false)
        val scaledCurr = Bitmap.createScaledBitmap(currBitmap, sw, sh, false)

        return try {
            val templateActualH = config.templateHeightScaled.coerceAtMost(sh - templateY)
            if (templateActualH <= 0) {
                Log.d(TAG, "no_scroll: templateActualH<=0 (sh=$sh templateY=$templateY)")
                return null
            }

            val templatePixels = IntArray(sw * templateActualH)
            scaledPrev.getPixels(templatePixels, 0, sw, 0, templateY, sw, templateActualH)

            val searchMaxY = (sh * config.searchTopRatio).toInt() - templateActualH
            if (searchMaxY <= 0) {
                Log.d(TAG, "no_scroll: searchMaxY<=0 (sh=$sh searchTopRatio=${config.searchTopRatio})")
                return null
            }

            var bestY = templateY
            var minSAD = Long.MAX_VALUE

            val candidatePixels = IntArray(sw * templateActualH)
            for (sy in 0..searchMaxY) {
                scaledCurr.getPixels(candidatePixels, 0, sw, 0, sy, sw, templateActualH)
                val sad = computeSAD(templatePixels, candidatePixels)
                if (sad < minSAD) {
                    minSAD = sad
                    bestY = sy
                }
            }

            val sadPerPixel = minSAD / (sw.toLong() * templateActualH)
            if (sadPerPixel > config.maxSadPerPixel) {
                Log.d(TAG, "no_scroll: SAD too high sadPerPixel=$sadPerPixel max=${config.maxSadPerPixel} bestY=$bestY")
                return null
            }

            val scaledDelta = templateY - bestY
            if (scaledDelta <= 0) {
                Log.d(TAG, "no_scroll: scaledDelta<=0 (templateY=$templateY bestY=$bestY)")
                return null
            }

            var delta = (scaledDelta / config.scale).roundToInt()
            // 基于完整分辨率在粗略结果附近做一小段精修，减少由于下采样与取整带来的 1～2px 误差
            delta = refineDeltaOnOriginal(prevBitmap, currBitmap, delta)
            if (delta < config.minScrollPx) {
                Log.d(TAG, "no_scroll: delta too small delta=$delta minScrollPx=${config.minScrollPx}")
                return null
            }
            Log.d(TAG, "scroll: deltaY=$delta (scaledDelta=$scaledDelta bestY=$bestY templateY=$templateY sadPerPx=$sadPerPixel)")
            delta
        } finally {
            scaledPrev.recycle()
            scaledCurr.recycle()
        }
    }

    /**
     * 在原始分辨率上对 delta 做细化搜索。
     *
     * 做法：在粗略 delta 附近的一个很小范围内（±3 像素）用 SAD 比较一块小区域，
     * 选出误差最小的 delta，基本可以把累计的 1～2px 取整误差消掉。
     */
    private fun refineDeltaOnOriginal(
        prevBitmap: Bitmap,
        currBitmap: Bitmap,
        coarseDelta: Int,
        radius: Int = 3
    ): Int {
        if (coarseDelta <= 0 || radius <= 0) return coarseDelta

        val w = minOf(prevBitmap.width, currBitmap.width)
        val h = minOf(prevBitmap.height, currBitmap.height)
        if (w <= 0 || h <= 0) return coarseDelta

        val deltaMin = maxOf(1, coarseDelta - radius)
        val deltaMax = minOf(coarseDelta + radius, h - 1)
        if (deltaMax <= deltaMin) return coarseDelta

        // 选取靠近屏幕中下部的一块区域做对齐，避开顶部状态栏等易变化区域
        val baseY = (h * 0.65f).toInt().coerceIn(0, h - 1)
        val sampleHeight = minOf(80, h - baseY - 1).coerceAtLeast(40)
        if (sampleHeight <= 0) return coarseDelta

        // 宽度上按步长采样，避免整幅遍历造成不必要开销
        val stepX = (w / 64).coerceAtLeast(1)

        var bestDelta = coarseDelta
        var minSad = Long.MAX_VALUE

        for (delta in deltaMin..deltaMax) {
            var sad = 0L
            val yEnd = baseY + sampleHeight
            for (y in baseY until yEnd) {
                val cy = y - delta
                if (cy < 0) break
                for (x in 0 until w step stepX) {
                    val p1 = prevBitmap.getPixel(x, y)
                    val p2 = currBitmap.getPixel(x, cy)

                    val r1 = (p1 shr 16) and 0xFF
                    val g1 = (p1 shr 8) and 0xFF
                    val b1 = p1 and 0xFF
                    val r2 = (p2 shr 16) and 0xFF
                    val g2 = (p2 shr 8) and 0xFF
                    val b2 = p2 and 0xFF

                    sad += kotlin.math.abs(r1 - r2) +
                            kotlin.math.abs(g1 - g2) +
                            kotlin.math.abs(b1 - b2)
                }
            }
            if (sad < minSad) {
                minSad = sad
                bestDelta = delta
            }
        }

        if (bestDelta != coarseDelta) {
            Log.d(TAG, "refine: coarse=$coarseDelta refined=$bestDelta h=$h w=$w")
        }
        return bestDelta
    }

    private fun computeSAD(a: IntArray, b: IntArray): Long {
        var sad = 0L
        for (i in a.indices) {
            val ar = (a[i] shr 16) and 0xFF
            val ag = (a[i] shr 8) and 0xFF
            val ab = a[i] and 0xFF
            val br = (b[i] shr 16) and 0xFF
            val bg = (b[i] shr 8) and 0xFF
            val bb = b[i] and 0xFF
            sad += kotlin.math.abs(ar - br) + kotlin.math.abs(ag - bg) + kotlin.math.abs(ab - bb)
        }
        return sad
    }

    /**
     * 根据分辨率动态生成的滚动检测参数。
     * 高分辨率：更大最小滚动、更大匹配容差、略大模板；低分辨率：更灵敏、更严匹配。
     */
    private data class ScrollDetectConfig(
        val scale: Float,
        val templateHeightScaled: Int,
        val templateTopRatio: Float,
        val searchTopRatio: Float,
        val minScrollPx: Int,
        val maxSadPerPixel: Long
    ) {
        companion object {
            /** 参考高度：约 1080p 竖屏，用于线性插值 */
            private const val REF_HEIGHT = 1920
            private const val REF_WIDTH = 1080

            fun fromResolution(width: Int, height: Int): ScrollDetectConfig {
                val pixels = width * height
                val refPixels = REF_WIDTH * REF_HEIGHT
                val resolutionFactor = (pixels.toFloat() / refPixels).coerceIn(0.5f, 3f)

                // 下采样比例：分辨率越高可略降以省算力，保持至少 0.15
                val scale = when {
                    height >= 2400 -> 0.15f
                    height >= 1800 -> 0.18f
                    else -> 0.2f
                }

                // 模板高度（下采样后）：高分辨率用稍大模板更稳
                val templateHeightScaled = (25 + (height / 400).coerceAtMost(20)).coerceIn(20, 50)

                // 模板取景位置：前一帧 30%～35% 高度处
                val templateTopRatio = 0.30f + (height - REF_HEIGHT).coerceIn(-500, 500) / 10000f

                // 在当前帧的搜索范围：上半部分 50%～60%
                val searchTopRatio = 0.52f + (height - REF_HEIGHT).coerceIn(-500, 500) / 5000f

                // 最小有效滚动（px）：按高度比例，高屏忽略小抖动
                val minScrollPx = (height / 60).coerceAtLeast(
                    (12 + resolutionFactor * 8).toInt().coerceAtLeast(12)
                )

                // SAD 容差：分辨率高、像素多，适当放宽
                val maxSadPerPixel = (50L + (height / 40).coerceIn(0, 50)).toLong()

                return ScrollDetectConfig(
                    scale = scale,
                    templateHeightScaled = templateHeightScaled,
                    templateTopRatio = templateTopRatio.coerceIn(0.2f, 0.45f),
                    searchTopRatio = searchTopRatio.coerceIn(0.45f, 0.65f),
                    minScrollPx = minScrollPx,
                    maxSadPerPixel = maxSadPerPixel
                )
            }
        }
    }
}
