package com.mima.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.InputMethodManager
import com.mima.app.data.DatabaseManager

/**
 * 透明中转页：绕过 IMMS「仅焦点窗口应用可唤起输入法选择器」的限制。
 *
 * 后台服务（AutofillService）直接调 showInputMethodPicker 会被系统静默拒绝
 * （Ignoring showInputMethodPickerFromClient：调用者不是焦点窗口）。
 * 本页由 Autofill 检测到第三方密码框时启动：取得窗口焦点后唤起选择器再退出，
 * 选择完成/取消后焦点回到原应用，键盘以所选输入法重新弹出。
 * 依赖 SYSTEM_ALERT_WINDOW（悬浮窗权限）获得后台启动 Activity 的豁免。
 *
 * ⚠️ 系统焦点交接存在延迟（过渡动画期间 IMMS 的焦点记录仍指向原应用），
 * 因此采用「延迟 + 多次重试」策略，直到选择器成功弹出或超时退出。
 */
class SwitchPickerActivity : Activity() {

    companion object {
        private const val TAG = "SwitchPicker"
        private const val MAX_ATTEMPTS = 8
        private const val RETRY_INTERVAL_MS = 300L
    }

    private var handled = false
    private var hasFocusNow = false
    /** 至少调用过一次选择器且已失去焦点：视为选择器正在显示 */
    private var pickerShowing = false
    private var attempts = 0
    private val handler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        // 兜底看门狗：无论卡在哪一步，30 秒后必退出
        handler.postDelayed({ Log.d(TAG, "watchdog finish"); finish() }, 30_000L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.d(TAG, "onWindowFocusChanged hasFocus=$hasFocus pickerShowing=$pickerShowing")
        hasFocusNow = hasFocus
        when {
            hasFocus && !handled -> {
                // 取得焦点：开始唤起选择器（含重试）
                handled = true
                attemptShowPicker()
            }
            !hasFocus && handled && !pickerShowing -> {
                // 焦点被夺走 = 选择器正在显示：保持存活，停止重试，等用户选完
                pickerShowing = true
                retryRunnable?.let { handler.removeCallbacks(it) }
            }
            hasFocus && pickerShowing -> {
                // 焦点回到本页 = 用户已选择/取消选择器：退出回原应用。
                // 若用户确实选了键盘，开启宽限期（设置页可调，默认 5 秒），期间「自动切回」静默，
                // 避免点相邻的非密码框时刚选的键盘立刻被切走。
                ImeAutoSwitch.pickerGraceUntil =
                    android.os.SystemClock.elapsedRealtime() +
                    DatabaseManager.getPickerGraceSeconds(this) * 1000L
                Log.d(TAG, "picker done, finishing (grace seconds=${DatabaseManager.getPickerGraceSeconds(this)})")
                finish()
            }
        }
    }

    private fun attemptShowPicker() {
        if (!hasFocusNow || pickerShowing) return
        attempts++
        Log.d(TAG, "picker attempt #$attempts")
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        if (attempts < MAX_ATTEMPTS) {
            retryRunnable = Runnable { if (!isFinishing && !isDestroyed) attemptShowPicker() }
            handler.postDelayed(retryRunnable!!, RETRY_INTERVAL_MS)
        } else {
            Log.d(TAG, "giving up, finishing")
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        handler.removeCallbacksAndMessages(null)
    }
}
