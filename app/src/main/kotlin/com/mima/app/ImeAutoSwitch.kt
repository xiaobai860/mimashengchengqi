package com.mima.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import com.mima.app.data.DatabaseManager

/**
 * 密码框聚焦时自动「切入」密码键盘（MimaKeyboardService）。
 * 「切出」（非密码框唤起键盘时切回上一个输入法）由 MimaKeyboardService 的
 * onStartInput 实现（IME 自主切走无需任何权限），见 isSwitchOutEnabled。
 *
 * 切入模式（互斥，存于 app_prefs 的 switch_in_mode）：
 *  - [MODE_ADB]：持有 WRITE_SECURE_SETTINGS（adb 授权）后改写
 *    Settings.Secure.DEFAULT_INPUT_METHOD 直接切换；
 *  - [MODE_PICKER]：当前键盘不是 Mima 时弹出系统输入法选择器，用户手动选一次；
 *  - [MODE_OFF]：不自动切入。
 *
 * 使用：给密码输入框加 [autoMimaKeyboard]，例如
 *   OutlinedTextField(..., modifier = Modifier.fillMaxWidth().autoMimaKeyboard())
 */
object ImeAutoSwitch {

    const val MODE_OFF = "off"
    const val MODE_ADB = "adb"
    const val MODE_PICKER = "picker"

    /** 选择器切入后的宽限期：期间「自动切回」不生效，让用户用刚选的键盘填完当前表单。
     *  截止时刻（elapsedRealtime 时钟），时长由设置页的宽限秒数决定 */
    @Volatile
    var pickerGraceUntil: Long = 0L

    /** 是否处于选择器切入宽限期内（期间自动切回静默） */
    fun isWithinPickerGrace(): Boolean =
        android.os.SystemClock.elapsedRealtime() < pickerGraceUntil

    private val handler = Handler(Looper.getMainLooper())

    private const val TAG = "ImeAutoSwitch"

    /** 是否已通过 adb 授权 WRITE_SECURE_SETTINGS */
    fun hasWriteSecureSettings(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** 密码键盘是否已在系统中启用；启用则返回其 ime id（形如 com.mima.app/.MimaKeyboardService） */
    fun mimaImeId(context: Context): String? = try {
        context.getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList
            ?.firstOrNull { it.packageName == context.packageName }
            ?.id
    } catch (_: Throwable) {
        null
    }

    /** 当前默认输入法是否已是密码键盘（已是则无需切入） */
    fun isMimaCurrent(context: Context): Boolean {
        val mima = mimaImeId(context) ?: return false
        return currentIme(context) == mima
    }

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

    /** ADB 模式核心动作：直写默认输入法为 Mima（后台可调用，无界面、不受 IMMS 弹窗限制）。返回是否成功 */
    fun switchInViaAdb(context: Context): Boolean {
        if (!hasWriteSecureSettings(context)) return false
        val mima = mimaImeId(context) ?: return false
        return try {
            Settings.Secure.putString(
                context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, mima
            )
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    /** 密码框获得焦点入口：按当前切入模式分发 */
    fun onPasswordFocusGained(context: Context, view: View) {
        val mode = DatabaseManager.getSwitchInMode(context)
        Log.d(TAG, "focus gained, mode=$mode")
        when (mode) {
            MODE_ADB -> switchInViaAdb(context, view)
            MODE_PICKER -> pickerIfNeeded(context)
        }
    }

    /** ADB 模式：写默认输入法为 Mima，并收起再延迟唤起软键盘让新输入法生效 */
    private fun switchInViaAdb(context: Context, view: View) {
        if (!hasWriteSecureSettings(context)) {
            Log.d(TAG, "adb skip: no WRITE_SECURE_SETTINGS")
            return
        }
        val mima = mimaImeId(context) ?: run {
            Log.d(TAG, "adb skip: mima ime not enabled")
            return
        }
        val current = currentIme(context) ?: run {
            Log.d(TAG, "adb skip: default ime null")
            return
        }
        if (current == mima) {
            Log.d(TAG, "adb skip: already mima")
            return
        }
        Log.d(TAG, "adb switch: $current -> $mima")
        setIme(context, mima)
        // 旧键盘可能已随焦点弹出：先收起再延迟唤起，让新的默认输入法（Mima）生效。
        // 两次延迟唤起兜底对话框窗口尚未就绪的场景；showSoftInput 幂等，重复调用无害。
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        listOf(150L, 400L).forEach { delay ->
            handler.postDelayed({ imm.showSoftInput(view, 0) }, delay)
        }
    }

    /** 选择器模式：仅当前键盘不是 Mima 时弹出系统输入法选择器（用户取消则本次不切入） */
    private fun pickerIfNeeded(context: Context) {
        val mima = mimaImeId(context) ?: return
        if (currentIme(context) == mima) return
        context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }
}

/**
 * 密码输入框专用：聚焦时按设置自动切入密码键盘（ADB 直切 / 弹窗选择器）。
 * 用法：modifier = Modifier.fillMaxWidth().autoMimaKeyboard()
 */
fun Modifier.autoMimaKeyboard(): Modifier = composed {
    val context = LocalContext.current
    val view = LocalView.current
    this.onFocusChanged { state ->
        if (state.isFocused) {
            ImeAutoSwitch.onPasswordFocusGained(context, view)
        }
    }
}
