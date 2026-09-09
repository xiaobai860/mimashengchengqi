package com.lesspass.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import com.lesspass.app.data.DatabaseManager

/**
 * 密码框聚焦时自动切换到本应用密码键盘（MimaKeyboardService），失焦后切回原键盘。
 *
 * 原理：Android 没有公开 API 让应用直接切换当前输入法，但持有 WRITE_SECURE_SETTINGS
 * （adb 授权的 signature 权限）后可以改写 Settings.Secure.DEFAULT_INPUT_METHOD：
 *  - 聚焦密码框：记住当前默认输入法 → 写入 Mima 键盘 → 收起再唤起软键盘让新输入法生效；
 *  - 失焦：把默认输入法写回原值。若聚焦期间用户手动切走（默认输入法已不是 Mima），尊重用户选择不回切。
 *
 * 使用：给密码输入框加 [autoMimaKeyboard]，例如
 *   OutlinedTextField(..., modifier = Modifier.fillMaxWidth().autoMimaKeyboard())
 */
object ImeAutoSwitch {

    private val handler = Handler(Looper.getMainLooper())

    /** 进入密码框之前保存的默认输入法，用于失焦后切回 */
    @Volatile
    private var previousIme: String? = null

    /** 当前聚焦中的密码框数量：密码框之间移动（如密码/确认密码）不做来回切换 */
    private var focusDepth = 0

    /** 是否已通过 adb 授权 WRITE_SECURE_SETTINGS */
    fun hasWriteSecureSettings(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** 密码键盘是否已在系统中启用；启用则返回其 ime id（形如 com.lesspass.app/.MimaKeyboardService） */
    fun mimaImeId(context: Context): String? = try {
        context.getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList
            ?.firstOrNull { it.packageName == context.packageName }
            ?.id
    } catch (_: Throwable) {
        null
    }

    /** 是否具备自动切换的全部前置条件（开关开 + 已授权 + 键盘已启用） */
    fun isReady(context: Context): Boolean =
        DatabaseManager.isAutoSwitchImeEnabled(context) &&
            hasWriteSecureSettings(context) &&
            mimaImeId(context) != null

    private fun currentIme(context: Context): String? =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)

    private fun setIme(context: Context, id: String) {
        try {
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
                id
            )
        } catch (_: SecurityException) {
        } catch (_: Throwable) {
        }
    }

    /** 密码框获得焦点：切到 Mima 键盘（深度计数避免密码框间移动来回切） */
    fun onPasswordFocusGained(context: Context, view: View) {
        focusDepth++
        if (focusDepth > 1) return
        if (!DatabaseManager.isAutoSwitchImeEnabled(context)) return
        if (!hasWriteSecureSettings(context)) return
        val mima = mimaImeId(context) ?: return
        val current = currentIme(context) ?: return
        if (current == mima) return
        previousIme = current
        setIme(context, mima)
        // 旧键盘可能已随焦点弹出：先收起再延迟唤起，让新的默认输入法（Mima）生效。
        // 两次延迟唤起兜底对话框窗口尚未就绪的场景；showSoftInput 幂等，重复调用无害。
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        listOf(150L, 400L).forEach { delay ->
            handler.postDelayed({ imm.showSoftInput(view, 0) }, delay)
        }
    }

    /** 密码框失去焦点：切回进入前保存的输入法；若用户已手动切走则尊重其选择 */
    fun onPasswordFocusLost(context: Context) {
        focusDepth = maxOf(0, focusDepth - 1)
        if (focusDepth > 0) return
        if (!hasWriteSecureSettings(context)) return
        val mima = mimaImeId(context) ?: return
        val prev = previousIme ?: return
        previousIme = null
        // 聚焦期间用户手动切到了别的键盘（默认输入法已不是 Mima）：不回切
        if (currentIme(context) != mima) return
        setIme(context, prev)
    }

    /** 兜底复位（应用锁定等场景调用，避免计数残留导致后续不切换） */
    fun reset() {
        focusDepth = 0
        previousIme = null
    }
}

/**
 * 密码输入框专用：聚焦时自动切到密码键盘，失焦后切回原键盘。
 * 用法：modifier = Modifier.fillMaxWidth().autoMimaKeyboard()
 */
fun Modifier.autoMimaKeyboard(): Modifier = composed {
    val context = LocalContext.current
    val view = LocalView.current
    this.onFocusChanged { state ->
        if (state.isFocused) {
            ImeAutoSwitch.onPasswordFocusGained(context, view)
        } else if (!state.hasFocus) {
            ImeAutoSwitch.onPasswordFocusLost(context)
        }
    }
}
