package com.lesspass.app

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lesspass.app.data.DatabaseManager
import com.lesspass.app.ui.theme.keyboardPaletteFor
import kotlin.random.Random

/**
 * 密码键盘（系统级 IME）。
 * 布局：
 *  - 字母页：标准 QWERTY 键位 + Shift 三态（⇧ 单次 / ⇪ 锁定，全部字母行联动）+ 退格长按连删；
 *  - 符号页（?123）：覆盖常用密码特殊字符集，无冷门符号；
 *  - 数字页（123）：1-0 拨号盘式大按键（乱序时 10 个数字全随机，0 可能在底行），⌫ 在底行；
 *  - 三页通过底行模式键互切；
 *  - 所有页面统一 3 行键区 + 1 行底行、统一行高，页面切换时键盘总高度不变；
 *  - 设置中可开启「键盘乱序」（随机打乱英文字母键位与字母页顶部数字键位）；
 *  - 全面屏手势条避让：按 navigationBars inset 给键盘底部垫高；
 *  - 键盘窗口 FLAG_SECURE，禁止截屏/录屏。
 * 顶部横滑条目芯片：点击即填 用户名 + TAB + 密码。
 */
class MimaKeyboardService : InputMethodService() {

    private companion object {
        const val MODE_LETTERS = 0
        const val MODE_SYMBOLS = 1
        const val MODE_NUMPAD = 3

        const val SHIFT_OFF = 0
        const val SHIFT_ON = 1      // 单次大写：输出一个字母后回落
        const val SHIFT_LOCKED = 2  // 锁定大写

        const val KEY_RADIUS_DP = 10f

        const val ROW_H_DP = 46      // 键区统一行高（三页一致）
        const val BOTTOM_H_DP = 44   // 底行高度
    }

    private lateinit var rootLayout: LinearLayout
    private lateinit var entryRow: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var keyboardLayout: LinearLayout

    // 主题联动配色（每次键盘弹出时从应用主题/动态取色重建），字段名与原常量对应
    private var keyBg = Color.rgb(52, 54, 60)
    private var fnBg = Color.rgb(38, 40, 46)
    private var activeBg = Color.rgb(79, 91, 213)
    private var keyboardBg = Color.rgb(28, 27, 31)
    private var keyText = Color.WHITE
    private var fnText = Color.WHITE
    private var activeText = Color.WHITE
    private var statusColor = Color.rgb(160, 162, 170)
    private var inverseBg = Color.WHITE
    private var inverseText = Color.rgb(28, 27, 31)

    private var mode = MODE_LETTERS
    private var shiftState = SHIFT_OFF

    /** 标准 QWERTY 键位（10/9/7 三行），乱序=在其基础上洗牌 */
    private val qwertyBase: List<String> = "qwertyuiopasdfghjklzxcvbnm".map { it.toString() }
    private var shuffledLetters: List<String> = qwertyBase
    private var shuffledDigits: List<String> = (0..9).map { it.toString() }

    private val repeatHandler = Handler(Looper.getMainLooper())
    private val repeatDelete = object : Runnable {
        override fun run() {
            backspace()
            repeatHandler.postDelayed(this, 50)
        }
    }

    override fun onCreateInputView(): View {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(28, 27, 31))
        }

        entryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = HorizontalScrollView(this).apply { addView(entryRow) }
        rootLayout.addView(scroll)

        statusText = TextView(this).apply {
            setTextColor(statusColor)
            textSize = 11f
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        rootLayout.addView(statusText)

        keyboardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        rootLayout.addView(keyboardLayout)

        // 全面屏手势条避让：取导航栏/可点区域/挖孔的最大 inset 加到底部 padding，
        // 避免最下排按键与手势条或系统输入法切换栏重叠
        val basePadding = intArrayOf(dp(6), dp(6), dp(6), dp(8))
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val tap = insets.getInsets(WindowInsetsCompat.Type.tappableElement())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val bottom = maxOf(nav.bottom, tap.bottom, cutout.bottom)
            v.setPadding(basePadding[0], basePadding[1], basePadding[2], basePadding[3] + bottom)
            WindowInsetsCompat.CONSUMED
        }

        refreshEntries()
        return rootLayout
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        // 自动切回（全局，设置可关）：键盘在非密码输入框上唤起时，自主切回原键盘。
        // 切回目标：上一个输入法（switchToPreviousInputMethod）或设置中指定的输入法
        // （switchToInputMethod，同为系统赋予 IME 的能力，无需任何权限）。
        // 选择器切入宽限期内不切回（用户刚手动选完键盘，让其填完当前表单）。
        if (DatabaseManager.isSwitchOutEnabled(this) && !isPasswordInput(info) &&
            !ImeAutoSwitch.isWithinPickerGrace()
        ) {
            val target = DatabaseManager.getSwitchOutTarget(this)
            if (target != DatabaseManager.SWITCH_OUT_TARGET_PREVIOUS && isImeEnabled(target)) {
                switchInputMethod(target)
            } else {
                hideSelf() // 上一个键盘；或指定输入法不可用（被禁用/卸载）时退回上一个
            }
        }
    }

    /** 指定 ime id 是否为当前已启用的输入法 */
    private fun isImeEnabled(id: String): Boolean = try {
        getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList?.any { it.id == id } == true
    } catch (_: Throwable) {
        false
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        mode = MODE_LETTERS
        shiftState = SHIFT_OFF
        // 防截屏/录屏：键盘窗口设为 FLAG_SECURE，截屏与录屏中显示黑屏，保障密码安全
        window?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        refreshEntries()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        window?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        super.onFinishInputView(finishingInput)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 密码类输入框判定：文本密码 / 可见密码 / Web 密码 / 数字密码 */
    private fun isPasswordInput(info: EditorInfo?): Boolean {
        if (info == null) return false
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return when (cls) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER ->
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    // ---------- 顶部条目芯片 ----------

    private fun refreshEntries() {
        // 每次键盘弹出重建主题配色（跟随应用动态取色/预设色板/亮暗模式）
        keyboardPaletteFor(this).let { p ->
            keyboardBg = p.background
            keyBg = p.key
            fnBg = p.fn
            activeBg = p.active
            keyText = p.keyText
            fnText = p.fnText
            activeText = p.activeText
            statusColor = p.statusText
            inverseBg = p.inverseBg
            inverseText = p.inverseText
            rootLayout.setBackgroundColor(keyboardBg)
            statusText.setTextColor(statusColor)
        }

        val shuffle = DatabaseManager.isKeyboardShuffleEnabled(this)
        // 乱序：每次键盘弹出都在 QWERTY 基础上重新洗牌（字母 26 键 + 数字 10 键随机位置）
        shuffledLetters = if (shuffle) qwertyBase.shuffled(Random) else qwertyBase
        // 数字 1-0 全部参与乱序：前 9 个进三行大键，第 10 个落在底行中列
        shuffledDigits = ((1..9).map { it.toString() } + "0").let { if (shuffle) it.shuffled(Random) else it }

        entryRow.removeAllViews()
        val entries = DatabaseManager.keyboardEntries
        if (entries.isEmpty()) {
            statusText.text = getString(R.string.keyboard_locked_hint)
            entryRow.addView(actionButton(getString(R.string.keyboard_open_app)) { openApp() })
        } else {
            statusText.text = getString(R.string.keyboard_entries_hint)
            entries.forEach { e ->
                val label = if (e.title.isNotBlank()) e.title
                else e.username.ifBlank { getString(R.string.keyboard_no_title) }
                entryRow.addView(actionButton(label) { fillEntry(e) })
            }
        }
        buildKeyboard()
    }

    private fun fillEntry(e: DatabaseManager.KeyboardEntrySnapshot) {
        val ic = currentInputConnection ?: return
        if (e.username.isNotBlank()) {
            ic.commitText(e.username, 1)
            sendTab()
        }
        if (e.password.isNotBlank()) {
            ic.commitText(e.password, 1)
        }
    }

    // ---------- 键盘布局（每页固定 4 行键区 + 底行，行高统一） ----------

    private fun buildKeyboard() {
        keyboardLayout.removeAllViews()
        when (mode) {
            MODE_LETTERS -> buildLetterRows()
            MODE_SYMBOLS -> buildSymbolRows()
            MODE_NUMPAD -> buildNumpadRows()
        }
        keyboardLayout.addView(buildBottomRow())
    }

    /** 字母页：QWERTY 三行（10/9/7）。所有键宽一致（以 10 键行为基准，9 键行两侧补 0.5 空白） */
    private fun buildLetterRows() {
        addLetterRow(shuffledLetters.subList(0, 10))
        addLetterRow(shuffledLetters.subList(10, 19), lead = 0.5f, trail = 0.5f)

        val row3 = newRow()
        row3.addView(shiftButton())
        shuffledLetters.subList(19, 26).forEach { row3.addView(letterButton(it, 1f)) }
        row3.addView(backspaceButton(1.5f))
        keyboardLayout.addView(row3)
    }

    /** 符号页 ?123：常用密码符号三行，无冷门符号，键宽与字母键盘一致 */
    private fun buildSymbolRows() {
        addSymbolRow(listOf("@", "#", "$", "%", "&", "*", "-", "+", "(", ")"))
        val quote = '"'.toString()
        addSymbolRow(listOf("!", "?", "^", "~", "`", "|", "\u005C", "_", "="), lead = 0.5f, trail = 0.5f)

        val row3 = newRow()
        row3.addView(spacer(1.5f))
        listOf("[", "]", "'", quote, ":", ",", ".").forEach { row3.addView(symbolButton(it)) }
        row3.addView(backspaceButton(1.5f))
        keyboardLayout.addView(row3)
    }

    /** 数字页：1-9 拨号盘式大按键（3 行），0 与退格在底行 */
    private fun buildNumpadRows() {
        for (r in 0..2) {
            val row = newRow()
            shuffledDigits.subList(r * 3, r * 3 + 3).forEach { row.addView(numpadButton(it)) }
            keyboardLayout.addView(row)
        }
    }

    /** 底行：字母/符号页 = 模式键 + 空格 + TAB + 切换输入法；数字页 = 模式键 + 0 + 退格。
     *  符号切换 "?123" 用功能键色，数字切换 "1 2 3" 用高亮色，文本+配色双重区分避免歧义。 */
    private fun buildBottomRow(): LinearLayout {
        val row = newRow()
        if (mode == MODE_NUMPAD) {
            // 三列结构与上方数字大键行完全一致（每列 weight 1），保证 0 与 2/5/8 严格对齐：
            // 左列 = ABC + ?123 并排，中列 = 0，右列 = 退格
            val left = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                // 高度填满整行（内部按钮自带 3dp 垂直 margin 与 0/⌫ 对齐），
                // 否则固定行高会容纳不下内部按钮的 margin，导致按钮偏下被裁切
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
                ).apply { setMargins(dp(3), 0, dp(3), 0) }
            }
            left.addView(modeButton("ABC", fnBg, BOTTOM_H_DP) { switchMode("ABC") })
            left.addView(modeButton("?123", fnBg, BOTTOM_H_DP) { switchMode("?123") })
            row.addView(left)
            row.addView(
                keyButton(shuffledDigits[9], 1f, keyBg, BOTTOM_H_DP) { commit(shuffledDigits[9]) }
                    .apply { textSize = 20f }
            )
            row.addView(backspaceButton(1f, big = true, heightDp = BOTTOM_H_DP))
            return row
        }
        val (a, b, bBg) = when (mode) {
            MODE_LETTERS -> Triple("?123", "1 2 3", activeBg)
            else -> Triple("ABC", "1 2 3", activeBg)
        }
        row.addView(modeButton(a, fnBg, BOTTOM_H_DP) { switchMode(a) })
        row.addView(modeButton(b, bBg, BOTTOM_H_DP) { switchMode(b) })
        row.addView(keyButton(getString(R.string.keyboard_space), 4f, keyBg, BOTTOM_H_DP) { commit(" ") })
        row.addView(keyButton("TAB", 1.5f, fnBg, BOTTOM_H_DP) { sendTab() })
        row.addView(keyButton("⌨", 1.5f, fnBg, BOTTOM_H_DP) { hideSelf() })
        return row
    }

    private fun switchMode(target: String) {
        mode = when (target) {
            "?123" -> MODE_SYMBOLS
            "123", "1 2 3" -> MODE_NUMPAD
            else -> MODE_LETTERS
        }
        shiftState = SHIFT_OFF
        buildKeyboard()
    }

    private fun newRow(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // 关闭基线对齐：否则同行按钮字号不同（如数字页 . 0 ⌫）会上下错位
            isBaselineAligned = false
        }

    /** 行内空白占位（保持各页键宽一致，宽度为行宽的 1/weight 基准份） */
    private fun spacer(weight: Float): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, weight)
    }

    /** 字母行：键面随 Shift 联动显示大小写；lead/trail 为两端空白（保持键宽 = 行宽/10） */
    private fun addLetterRow(keys: List<String>, lead: Float = 0f, trail: Float = 0f) {
        val row = newRow()
        if (lead > 0f) row.addView(spacer(lead))
        keys.forEach { row.addView(letterButton(it, 1f)) }
        if (trail > 0f) row.addView(spacer(trail))
        keyboardLayout.addView(row)
    }

    private fun addSymbolRow(keys: List<String>, lead: Float = 0f, trail: Float = 0f) {
        val row = newRow()
        if (lead > 0f) row.addView(spacer(lead))
        keys.forEach { row.addView(symbolButton(it)) }
        if (trail > 0f) row.addView(spacer(trail))
        keyboardLayout.addView(row)
    }

    /** Shift 键三态视觉：关闭=深灰灰字 / 单次=靛蓝白字 / 锁定=白底深色加粗（高对比反转，不改键位尺寸） */
    private fun shiftButton(): Button {
        return keyButton(
            if (shiftState == SHIFT_LOCKED) "⇪" else "⇧",
            1.5f,
            if (shiftState == SHIFT_OFF) fnBg else activeBg,
            ROW_H_DP
        ) {
            shiftState = when (shiftState) {
                SHIFT_OFF -> SHIFT_ON
                SHIFT_ON -> SHIFT_LOCKED
                else -> SHIFT_OFF
            }
            buildKeyboard()
        }.apply {
            textSize = 24f
            when (shiftState) {
                SHIFT_OFF -> setTextColor(fnText)
                SHIFT_ON -> setTextColor(activeText)
                else -> {
                    // 锁定：反色底（暗色主题≈白底、亮色主题≈深底），与单次/关闭明显区分
                    background = keyBackground(inverseBg)
                    setTextColor(inverseText)
                    paint.isFakeBoldText = true
                }
            }
        }
    }

    private fun letterButton(ch: String, weight: Float): Button {
        val label = if (shiftState == SHIFT_OFF) ch else ch.uppercase()
        return keyButton(label, weight, keyBg, ROW_H_DP) { onKey(ch) }.apply { textSize = 22f }
    }

    private fun symbolButton(s: String): Button =
        keyButton(s, 1f, keyBg, ROW_H_DP) { commit(s) }

    /** 数字页大按键（与其它页同行高，字号更大） */
    private fun numpadButton(digit: String): Button =
        keyButton(digit, 1f, keyBg, ROW_H_DP) { commit(digit) }.apply { textSize = 22f }

    private fun modeButton(label: String, bg: Int, heightDp: Int = ROW_H_DP, onClick: () -> Unit): Button =
        keyButton(label, 1.5f, bg, heightDp) { onClick() }

    /** 字母页走 Shift 状态，其它页直接输出 */
    private fun onKey(s: String) {
        if (mode == MODE_LETTERS) {
            val out = if (shiftState == SHIFT_OFF) s else s.uppercase()
            commit(out)
            if (shiftState == SHIFT_ON) {
                shiftState = SHIFT_OFF
                buildKeyboard()
            }
        } else {
            commit(s)
        }
    }

    private fun backspaceButton(weight: Float, big: Boolean = false, heightDp: Int = ROW_H_DP): Button {
        val b = Button(this).apply {
            text = "⌫"
            setTextColor(fnText)
            textSize = if (big) 22f else 16f
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            background = keyBackground(fnBg)
            layoutParams = keyParams(weight, heightDp)
        }
        // 按下即删 1 次；按住 400ms 后以 50ms 间隔连删
        b.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    b.isPressed = true
                    backspace()
                    repeatHandler.postDelayed(repeatDelete, 400)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    b.isPressed = false
                    repeatHandler.removeCallbacks(repeatDelete)
                    true
                }
                else -> false
            }
        }
        return b
    }

    // ---------- 键样式 ----------

    private fun keyBackground(bg: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(bg)
            cornerRadius = KEY_RADIUS_DP * resources.displayMetrics.density
        }

    private fun keyButton(label: String, weight: Float, bg: Int, heightDp: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(keyText)
            textSize = 16f
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            background = keyBackground(bg)
            layoutParams = keyParams(weight, heightDp)
            setOnClickListener { onClick() }
        }
    }

    /** 统一键位尺寸：固定高度 + 均分宽度，保证各页行高一致 */
    private fun keyParams(weight: Float, heightDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(heightDp), weight).apply {
            val m = dp(3)
            setMargins(m, m, m, m)
        }

    private fun actionButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(activeText)
            textSize = 13f
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = keyBackground(activeBg)
            setOnClickListener { onClick() }
        }
    }

    // ---------- 输入动作 ----------

    private fun commit(s: String) {
        currentInputConnection?.commitText(s, 1)
    }

    private fun backspace() {
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    private fun sendTab() {
        currentInputConnection?.apply {
            sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
            sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB))
        }
    }

    private fun hideSelf() {
        try {
            switchToPreviousInputMethod()
        } catch (_: Throwable) {
            requestHideSelf(0)
        }
    }

    private fun openApp() {
        packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { startActivity(it) }
    }
}
