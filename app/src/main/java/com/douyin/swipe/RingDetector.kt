package com.douyin.swipe

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

/**
 * 红包金色环检测引擎 — 完整移植 auto_swipe_v7.py 逻辑
 *
 * 检测流程：
 * 1. 扫描屏幕左侧候选区域，找红包（红色像素占比 > 2%）
 * 2. 在红包中心生成 100×100 紧致 ROI
 * 3. 统计金色环像素，连续 3 帧变化 ≤20 → 触发上滑
 * 4. 红包不完整 → 60 秒冷却，防卡死 80 秒超时兜底
 */
object RingDetector {

    private const val TAG = "RingDetector"

    // ===== 检测参数（与 Python 版完全一致）=====
    private const val STATIC_FRAMES_NEEDED = 3
    private const val RED_INTEGRITY_MIN = 0.50f
    private const val ROI_TOLERANCE = 10f
    private const val ENVELOPE_MIN_CY = 250
    private const val INCOMPLETE_COOLDOWN = 60_000L   // ms
    const val MAX_SWIPE_INTERVAL = 80_000L             // ms

    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** 4 个左侧候选搜索区域（屏幕坐标） */
    private val candidates = listOf(
        Rect(10, 100, 220, 350),
        Rect(10, 300, 220, 600),
        Rect(10, 550, 220, 850),
        Rect(10, 800, 220, 1100),
    )

    // ===== 运行时状态 =====
    var lastSwipeTime: Long = 0L
    private var prevGold: Int? = null
    private var staticCount = 0
    private var lastRoi: Rect? = null
    private var baseRed: Int? = null
    private var pendingMove: Triple<Rect, Int, Rect>? = null
    private var incompleteUntil: Long = 0L

    /** 当前屏幕尺寸，由外部设置 */
    var screenWidth: Int = 1080
    var screenHeight: Int = 2400

    // ===== 公共接口 =====

    /**
     * 处理一帧截屏。返回 true 表示需要上滑。
     */
    fun processFrame(bitmap: Bitmap): Boolean {
        val now = System.currentTimeMillis()

        // --- 防卡死超时兜底 ---
        if (now - lastSwipeTime > MAX_SWIPE_INTERVAL && lastSwipeTime > 0) {
            Log.d(TAG, "[TIMEOUT] ${MAX_SWIPE_INTERVAL}ms → FORCE SWIPE")
            reset()
            return true
        }

        // resize 候选区域到当前屏幕
        val scaleX = bitmap.width.toFloat() / 1080f
        val scaleY = bitmap.height.toFloat() / 2400f

        val found = findEnvelope(bitmap, scaleX, scaleY)
        if (found == null) {
            if (lastRoi != null) Log.d(TAG, "[No envelope]")
            reset()
            return false
        }
        val (foundRoi, currRed) = found

        // --- 位置容差判断 + 移动确认 ---
        if (lastRoi != null) {
            val dist = roiDistance(foundRoi, lastRoi!!)
            if (dist > ROI_TOLERANCE) {
                if (pendingMove != null) {
                    val (prevNewRoi, prevNewRed, prevOldRoi) = pendingMove!!
                    pendingMove = null
                    if (roiDistance(foundRoi, prevNewRoi) <= ROI_TOLERANCE) {
                        baseRed = currRed
                        Log.d(TAG, "[Moved OK] dist=$dist ${prevOldRoi} → $foundRoi baseRed=$baseRed")
                        lastRoi = foundRoi
                        staticCount = 0
                        prevGold = null
                    } else {
                        Log.d(TAG, "[Move unconfirmed] ignoring")
                    }
                    return false
                } else {
                    pendingMove = Triple(foundRoi, currRed, lastRoi!!)
                    Log.d(TAG, "[Move ?] dist=$dist ${lastRoi} → $foundRoi")
                    return false
                }
            } else {
                pendingMove = null
                lastRoi = foundRoi
            }
        } else {
            lastRoi = foundRoi
            baseRed = currRed
            Log.d(TAG, "[Init] roi=$foundRoi baseRed=$baseRed")
        }

        // --- 红包完整性检查 ---
        if (baseRed != null && currRed < baseRed!! * RED_INTEGRITY_MIN) {
            val remaining = incompleteUntil - now
            if (remaining <= 0) {
                incompleteUntil = now + INCOMPLETE_COOLDOWN
                Log.d(TAG, "[INCOMPLETE] red=$currRed < baseRed*$RED_INTEGRITY_MIN → COOLDOWN ${INCOMPLETE_COOLDOWN}ms")
            } else {
                Log.d(TAG, "[INCOMPLETE cooldown] ${remaining / 1000}s remaining")
            }
            staticCount = 0
            prevGold = null
            pendingMove = null
            return false
        }

        // --- 冷却期内禁止上滑 ---
        if (now < incompleteUntil) {
            val remaining = (incompleteUntil - now) / 1000
            Log.d(TAG, "[COOLDOWN] ${remaining}s left → no swipe")
            // 继续计数但不触发
        }

        // --- 金色环像素统计 ---
        val ringRoi = Rect(
            foundRoi.left - 10, foundRoi.top - 10,
            foundRoi.right + 10, foundRoi.bottom + 10
        )
        val goldCount = countGoldPixels(bitmap, ringRoi, scaleX, scaleY)
        Log.d(TAG, "gold=$goldCount roi=$foundRoi")

        val stable = prevGold != null && goldCountStable(prevGold!!, goldCount)

        if (stable) {
            staticCount++
            if (staticCount >= STATIC_FRAMES_NEEDED) {
                if (now < incompleteUntil) {
                    val remaining = (incompleteUntil - now) / 1000
                    Log.d(TAG, "[${staticCount}/${STATIC_FRAMES_NEEDED}] COOLDOWN active (${remaining}s) → no swipe")
                } else {
                    Log.d(TAG, "[${staticCount}/${STATIC_FRAMES_NEEDED}] gold=$goldCount → SWIPE!")
                    lastSwipeTime = now
                    reset()
                    return true
                }
            } else {
                Log.d(TAG, "[${staticCount}/${STATIC_FRAMES_NEEDED}] gold=$goldCount")
            }
        } else {
            if (prevGold != null) {
                Log.d(TAG, "[Ring changing] gold ${prevGold} → $goldCount diff=${Math.abs(goldCount - prevGold!!)}")
            }
            staticCount = 0
        }

        prevGold = goldCount
        return false
    }

    fun reset() {
        prevGold = null
        staticCount = 0
        lastRoi = null
        baseRed = null
        pendingMove = null
    }

    fun fullReset() {
        reset()
        incompleteUntil = 0L
    }

    // ===== 内部检测函数 =====

    /**
     * 在候选区域中查找红包。返回 (ROI, 红色像素数)。
     */
    private fun findEnvelope(bitmap: Bitmap, scaleX: Float, scaleY: Float): Pair<Rect, Int>? {
        for (cand in candidates) {
            val left = (cand.left * scaleX).toInt().coerceIn(0, bitmap.width - 1)
            val top = (cand.top * scaleY).toInt().coerceIn(0, bitmap.height - 1)
            val right = (cand.right * scaleX).toInt().coerceIn(left + 1, bitmap.width)
            val bottom = (cand.bottom * scaleY).toInt().coerceIn(top + 1, bitmap.height)

            val crop = try {
                Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            } catch (e: Exception) {
                continue
            }

            val w = crop.width
            val h = crop.height

            // 20×20 单元格扫描红色像素
            val cellW = w / 20
            val cellH = h / 20
            if (cellW < 1 || cellH < 1) { crop.recycle(); continue }

            var bestRatio = 0.0f
            var bestCx = 0
            var bestCy = 0
            var bestRedCount = 0

            for (cy in 0 until 20) {
                for (cx in 0 until 20) {
                    var redCount = 0
                    val totalPixels = cellW * cellH
                    val x0 = cx * cellW
                    val y0 = cy * cellH
                    for (py in y0 until (y0 + cellH).coerceAtMost(h)) {
                        for (px in x0 until (x0 + cellW).coerceAtMost(w)) {
                            val pixel = crop.getPixel(px, py)
                            val r = Color.red(pixel)
                            val g = Color.green(pixel)
                            val b = Color.blue(pixel)
                            if (r > 180 && g < 70 && b < 70) {
                                redCount++
                            }
                        }
                    }
                    val ratio = redCount.toFloat() / totalPixels
                    if (ratio > bestRatio) {
                        bestRatio = ratio
                        bestCx = x0 + cellW / 2
                        bestCy = y0 + cellH / 2
                        bestRedCount = redCount
                    }
                }
            }

            crop.recycle()

            if (bestRatio < 0.02f) continue

            // 转为屏幕绝对坐标
            val absCy = top + bestCy
            if (absCy < (ENVELOPE_MIN_CY * scaleY).toInt()) {
                Log.d(TAG, "  skip: cy=$absCy < minCy=${(ENVELOPE_MIN_CY * scaleY).toInt()}")
                continue
            }

            // 生成 100×100 紧致 ROI
            val roiHalf = (50 * scaleX).toInt().coerceAtLeast(25)
            val absCx = left + bestCx
            val rx1 = (absCx - roiHalf).coerceAtLeast(0)
            val ry1 = (absCy - roiHalf).coerceAtLeast(0)
            val rx2 = (absCx + roiHalf).coerceAtMost(bitmap.width)
            val ry2 = (absCy + roiHalf).coerceAtMost(bitmap.height)

            return Pair(Rect(rx1, ry1, rx2, ry2), bestRedCount)
        }
        return null
    }

    /** 统计 ROI 内金色像素数 */
    private fun countGoldPixels(bitmap: Bitmap, roi: Rect, scaleX: Float, scaleY: Float): Int {
        val rx1 = roi.left.coerceIn(0, bitmap.width - 1)
        val ry1 = roi.top.coerceIn(0, bitmap.height - 1)
        val rx2 = roi.right.coerceIn(rx1 + 1, bitmap.width)
        val ry2 = roi.bottom.coerceIn(ry1 + 1, bitmap.height)

        var count = 0
        val step = 2 // 隔像素采样，提升性能
        for (y in ry1 until ry2 step step) {
            for (x in rx1 until rx2 step step) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                if (r > 160 && g > 100 && b < 80) {
                    count++
                }
            }
        }
        return count * (step * step) // 还原采样
    }

    private fun goldCountStable(prev: Int, curr: Int): Boolean {
        return Math.abs(curr - prev) <= 20
    }

    private fun roiDistance(a: Rect, b: Rect): Float {
        val ax = (a.left + a.right) / 2f
        val ay = (a.top + a.bottom) / 2f
        val bx = (b.left + b.right) / 2f
        val by = (b.top + b.bottom) / 2f
        return Math.sqrt(((ax - bx) * (ax - bx) + (ay - by) * (ay - by)).toDouble()).toFloat()
    }
}
