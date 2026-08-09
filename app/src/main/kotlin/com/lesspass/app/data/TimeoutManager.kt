package com.lesspass.app.data

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * 会话超时管理器 — 借鉴 KeePassDX 的 timeout 模块。
 * 在应用无操作一段时间后自动锁定数据库。
 */
class TimeoutManager(
    private val databaseManager: DatabaseManager,
    private var timeoutMs: Long = 5 * 60 * 1000L, // 默认 5 分钟
    private val onLock: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var lastActivityTime = System.currentTimeMillis()
    private var isRunning = false
    private var checkInterval = 10 * 1000L // 每 10 秒检查一次

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (!databaseManager.unlocked) {
                stop()
                return
            }
            val idleTime = System.currentTimeMillis() - lastActivityTime
            if (idleTime >= timeoutMs) {
                databaseManager.lock()
                onLock()
                stop()
            } else {
                handler.postDelayed(this, checkInterval)
            }
        }
    }

    /**
     * 启动超时监控
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        lastActivityTime = System.currentTimeMillis()
        handler.postDelayed(checkRunnable, checkInterval)
    }

    /**
     * 停止超时监控
     */
    fun stop() {
        isRunning = false
        handler.removeCallbacks(checkRunnable)
    }

    /**
     * 记录用户活动，重置计时器
     */
    fun onUserActivity() {
        lastActivityTime = System.currentTimeMillis()
    }

    /**
     * 设置超时时间
     */
    fun setTimeout(timeoutMs: Long) {
        this.timeoutMs = timeoutMs
    }

    /**
     * 获取剩余超时时间（毫秒）
     */
    fun getRemainingTime(): Long {
        return maxOf(0, timeoutMs - (System.currentTimeMillis() - lastActivityTime))
    }

    /**
     * 是否正在监控
     */
    val running: Boolean get() = isRunning
}
