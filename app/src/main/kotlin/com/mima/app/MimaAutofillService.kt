package com.mima.app

import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import android.content.Context
import com.mima.app.data.DatabaseManager

/**
 * 系统自动填充服务（Autofill）：
 *  1. 填充：解锁后可把密码本条目（用户名+密码）填充到其它应用的登录表单；
 *  2. 全局密码框检测：onFillRequest 是系统认证的「第三方应用出现密码框」信号——
 *     切入模式为 ADB 时自动切到密码键盘（后台直写设置，无界面，不受 IMMS 弹窗焦点限制）；
 *     PICKER 模式在后台仍会被 IMMS 拒绝（系统仅允许焦点应用唤起选择器），故不在此弹窗。
 *
 * 需用户在系统设置中把本应用设为自动填充服务（设置页有入口）。
 * 锁定状态（keyboardEntries 为空）只做检测切换，不提供任何填充数据。
 */
class MimaAutofillService : AutofillService() {

    companion object {
        private const val TAG = "MimaAutofill"
        /** 单次响应最多提供的数据集数量，防止条目过多导致响应过大 */
        private const val MAX_DATASETS = 5

        fun isAutofillEnabled(context: Context): Boolean = try {
            context.getSystemService(AutofillManager::class.java)
                ?.hasEnabledAutofillServices() == true
        } catch (_: Throwable) {
            false
        }
    }

    /** 扫描出的可填充字段 */
    private data class FieldRef(val id: AutofillId, val isPassword: Boolean, val isUsername: Boolean)

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val fillContext = request.fillContexts.lastOrNull()
        val structure = fillContext?.structure
        if (structure == null) {
            callback.onSuccess(null)
            return
        }
        // 排除自身：本应用密码框由 Modifier.autoMimaKeyboard 处理，自填充无意义
        if (structure.activityComponent?.packageName == packageName) {
            callback.onSuccess(null)
            return
        }

        val fields = scanFields(structure)
        val hasPasswordField = fields.any { it.isPassword }
        Log.d(TAG, "fill request: pkg=${structure.activityComponent?.packageName} " +
            "fields=${fields.size} password=$hasPasswordField")

        // 全局密码框检测：按切入模式分发。
        //  - ADB：后台直写默认输入法，无界面即时切换；
        //  - PICKER：后台直接调 showInputMethodPicker 会被 IMMS 以「非焦点窗口应用」拒绝，
        //    故启动透明中转页 SwitchPickerActivity（借助悬浮窗权限的后台启动豁免）短暂取得焦点后唤起选择器。
        if (hasPasswordField && !ImeAutoSwitch.isMimaCurrent(this)) {
            when (DatabaseManager.getSwitchInMode(this)) {
                ImeAutoSwitch.MODE_ADB ->
                    Log.d(TAG, "adb switch ok=${ImeAutoSwitch.switchInViaAdb(this)}")
                ImeAutoSwitch.MODE_PICKER -> try {
                    startActivity(
                        android.content.Intent(this, SwitchPickerActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Throwable) {
                    Log.d(TAG, "trampoline launch failed")
                }
            }
        }

        // 填充数据集：需解锁（keyboardEntries 非空）且有可填字段
        val entries = DatabaseManager.keyboardEntries
        if (entries.isEmpty() || fields.isEmpty()) {
            callback.onSuccess(null)
            return
        }

        val responseBuilder = FillResponse.Builder()
        entries.take(MAX_DATASETS).forEach { e ->
            val title = e.title.ifBlank { e.username.ifBlank { e.url } }
            val presentation = RemoteViews(packageName, R.layout.autofill_dataset).apply {
                setTextViewText(R.id.autofill_title, title)
                setTextViewText(R.id.autofill_subtitle, e.username)
            }
            val dataset = Dataset.Builder(presentation).apply {
                fields.forEach { f ->
                    val value = if (f.isPassword) e.password else e.username
                    if (value.isNotEmpty() || f.isPassword) {
                        setValue(f.id, AutofillValue.forText(value))
                    }
                }
            }.build()
            responseBuilder.addDataset(dataset)
        }
        callback.onSuccess(responseBuilder.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // v1 不支持自动保存：忽略保存请求
        callback.onSuccess()
    }

    /** 遍历所有窗口的所有节点，收集用户名/密码字段 */
    private fun scanFields(structure: AssistStructure): List<FieldRef> {
        val result = mutableListOf<FieldRef>()
        for (w in 0 until structure.windowNodeCount) {
            structure.getWindowNodeAt(w).rootViewNode?.let { walk(it, result) }
        }
        return result
    }

    private fun walk(node: AssistStructure.ViewNode, out: MutableList<FieldRef>) {
        node.autofillId?.let { id ->
            val isPassword = isPasswordField(node)
            val isUsername = !isPassword && isUsernameField(node)
            if (isPassword || isUsername) {
                out.add(FieldRef(id, isPassword, isUsername))
            }
        }
        for (i in 0 until node.childCount) {
            node.getChildAt(i)?.let { walk(it, out) }
        }
    }

    private fun isPasswordField(node: AssistStructure.ViewNode): Boolean {
        node.autofillHints?.forEach { h ->
            if (h.contains("password", ignoreCase = true)) return true
        }
        val cls = node.inputType and InputType.TYPE_MASK_CLASS
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        return (cls == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            )) ||
            (cls == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private fun isUsernameField(node: AssistStructure.ViewNode): Boolean {
        node.autofillHints?.forEach { h ->
            val lower = h.lowercase()
            if (lower.contains("username") || lower.contains("user_name") ||
                lower.contains("email") || lower.contains("login") ||
                lower.contains("phone") || lower.contains("sms")
            ) return true
        }
        // 关键词启发式（hint/已填文本），先排除密码关键词避免误判
        val text = listOfNotNull(node.hint, node.text?.toString())
            .joinToString(" ").lowercase()
        if (text.isEmpty()) return false
        if (text.contains("密码") || text.contains("password")) return false
        return listOf("用户名", "账号", "帐号", "邮箱", "手机", "email", "user", "account", "login")
            .any { text.contains(it) }
    }
}
