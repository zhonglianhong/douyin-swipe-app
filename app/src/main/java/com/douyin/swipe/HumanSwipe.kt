package com.douyin.swipe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import kotlin.random.Random

/**
 * 人工模拟上滑 — 使用 AccessibilityService.dispatchGesture
 * 轨迹：8~14 路径点 + 随机水平曲弧 + 变速模拟
 */
object HumanSwipe {

    private const val TAG = "HumanSwipe"

    /** 屏幕尺寸，外部设置 */
    var screenWidth: Int = 1080
    var screenHeight: Int = 2400

    /** 上一次上滑完成时间 */
    var lastSwipeEndTime: Long = 0L

    /**
     * 执行一次人工模拟上滑。
     * 使用 dispatchGesture 异步分发，通过回调跟踪结果。
     */
    fun perform(service: AccessibilityService, onResult: ((Boolean) -> Unit)? = null) {
        val baseX = screenWidth / 2
        val baseStartY = (screenHeight * 0.75).toInt()
        val baseEndY = (screenHeight * 0.25).toInt()

        // 随机起终点偏移
        val sx = baseX + Random.nextInt(-30, 31)
        val sy = baseStartY + Random.nextInt(-25, 26)
        val ex = baseX + Random.nextInt(-50, 51)
        val ey = baseEndY + Random.nextInt(-30, 31)

        val n = Random.nextInt(8, 15)
        val totalMs = Random.nextLong(220, 451)

        // 生成 curved 路径
        val bias = Random.nextDouble(-1.0, 1.0)
        val points = mutableListOf<Pair<Float, Float>>()
        for (i in 0..n) {
            val t = i.toFloat() / n
            var curve = (bias * 4 * (t - 0.5) * (t - 0.5) * Random.nextDouble(40.0, 80.0)).toFloat()
            if (Random.nextFloat() < 0.3f) curve = -curve
            val x = sx + (ex - sx) * t + curve + Random.nextInt(-4, 5)
            val y = sy + (ey - sy) * t + Random.nextInt(-3, 4)
            points.add(Pair(x, y))
        }

        // 时间分配 (正弦加速→减速)
        val rawT = (1..n).map { i ->
            val t = i.toFloat() / n
            0.2f + 0.8f * (1 - Math.abs(2 * t - 1)) * Random.nextFloat(0.8f, 1.2f)
        }
        val totalRaw = rawT.sum()
        val timings = rawT.map { maxOf(8, (totalMs * it / totalRaw).toInt()) }
        val duration = timings.sum().toLong()

        // 构建连续 Stroke
        val path = Path()
        path.moveTo(points[0].first, points[0].second)
        for (i in 1..n) {
            path.lineTo(points[i].first, points[i].second)
        }

        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))

        Log.d(TAG, "SWIPE ${points.size}pts ${duration}ms " +
                "start=(${points.first().first.toInt()},${points.first().second.toInt()}) " +
                "end=(${points.last().first.toInt()},${points.last().second.toInt()})")

        service.dispatchGesture(builder.build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "gesture completed")
                lastSwipeEndTime = System.currentTimeMillis()
                onResult?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "gesture cancelled")
                onResult?.invoke(false)
            }
        }, null)
    }
}
