package com.douyin.swipe

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 主界面 — 启动/停止 + 权限引导
 *
 * 第一次使用流程：
 * 1. 授予悬浮窗权限 (SYSTEM_ALERT_WINDOW)
 * 2. 开启无障碍服务 (设置 → 无障碍 → 抖音红包助手)
 * 3. 返回 App，点击"开始"
 * 4. 授予屏幕录制权限 (系统弹窗)
 * 5. 自动开始检测
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1001
    }

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button

    private var swipeService: SwipeService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // AccessibilityService 的 binder 不直接暴露
            serviceBound = true
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            swipeService = null
            updateUI()
        }
    }

    // 通过 SwipeService 的静态字段传递 MediaProjection
    private var pendingMediaProjection = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)

        startBtn.setOnClickListener { onStartClick() }
        stopBtn.setOnClickListener { onStopClick() }

        // 检查无障碍服务状态
        updateUI()
    }

    private fun onStartClick() {
        // 1. 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        // 2. 检查无障碍服务
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        // 3. 请求 MediaProjection 权限
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        pendingMediaProjection = true
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
    }

    private fun onStopClick() {
        try {
            // 通过 binding 停止服务
            unbindService(serviceConnection)
        } catch (_: Exception) {}
        serviceBound = false

        // 发送停止指令到服务
        val intent = Intent(this, SwipeService::class.java)
        stopService(intent)

        statusText.text = "已停止"
        startBtn.isEnabled = true
        stopBtn.isEnabled = false
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == Activity.RESULT_OK && data != null) {
            pendingMediaProjection = false

            // 传递 MediaProjection 到服务
            SwipeService.mediaProjectionIntent = data
            SwipeService.resultCode = resultCode

            // 绑定并启动服务
            val intent = Intent(this, SwipeService::class.java)
            try {
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            } catch (_: Exception) {}

            // 稍微延迟让服务初始化
            window.decorView.postDelayed({
                startCaptureInService()
            }, 500)

            statusText.text = "正在启动..."
            startBtn.isEnabled = false
            stopBtn.isEnabled = true
        } else if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode != Activity.RESULT_OK) {
            pendingMediaProjection = false
            Toast.makeText(this, "需要屏幕录制权限才能工作", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCaptureInService() {
        // 通过发送 Broadcast 或直接操作服务
        // 由于 AccessibilityService 的特殊性，这里用延迟调用
        try {
            val intent = Intent(this, SwipeService::class.java)
            intent.action = "START_CAPTURE"
            startService(intent)
        } catch (_: Exception) {}

        statusText.text = "监控中..."
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // 处理从服务发来的 start capture 请求
        if (intent?.action == "START_CAPTURE" && serviceBound) {
            startCaptureInService()
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val serviceName = "$packageName/.SwipeService"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(serviceName)
    }

    private fun updateUI() {
        val accEnabled = isAccessibilityEnabled()
        statusText.text = when {
            !accEnabled -> "无障碍服务未开启"
            serviceBound -> "监控中..."
            else -> "就绪 — 点击开始"
        }
        startBtn.isEnabled = accEnabled && !serviceBound
        stopBtn.isEnabled = serviceBound
    }

    override fun onResume() {
        super.onResume()
        updateUI()

        // 如果刚从设置返回，且 Accessibility 已开启、有 pending 的 MediaProjection
        if (pendingMediaProjection && isAccessibilityEnabled()) {
            onStartClick()
        }
    }

    override fun onDestroy() {
        if (serviceBound) {
            try {
                unbindService(serviceConnection)
            } catch (_: Exception) {}
        }
        super.onDestroy()
    }
}
