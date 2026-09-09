package com.lesspass.app

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lesspass.app.data.DatabaseManager
import kotlin.random.Random

/**
 * 密码键盘（系统级 IME）。
 * 布局参照主流输入法：
 *  - 字母页：顶部常驻数字行 + QWERTY + Shift 三态（⇧ 单次 / ⇪ 锁定）+ 退格长按连删；
 *  - 符号页（?123）：数字行 + 常用/扩展符号（=\< 互切）；
 *  - 数字页（123）：1-9 + 0 拨号盘式大按键，便于快速输入数字；
 *  - 三页通过底行模式键互切；底行固定：模式键 / 空格 / TAB / 切换输入法；
 *  - 设置中可开启「键盘乱序」（随机打乱英文字母键位与字母页顶部数字键位）；
 *  - 全面屏手势条避让：按 navigationBars inset 给键盘底部垫高；
 *  - 键盘窗口 FLAG_SECURE，禁止截屏/录屏。
 * 顶部横滑条目芯片：点击即填 用户名 + TAB + 密码。
 */
class MimaKeyboardService : InputMethodService() {

    private companion object {
        const val MODE_LETTERS = 0
        const val MODE_SYMBOLS = 1
        const val MODE_SYMBOLS_EXTRA = 2
        const val MODE_NUMPAD = 3

        const val SHIFT_OFF = 0
        const val SHIFT_ON = 1      // 单次大写：输出一个字母后回落
        const val SHIFT_LOCKED = 2  // 锁定大写

        val KEY_BG = Color.rgb(52, 54, 60)          // 普通键
        val FN_BG = Color.rgb(38, 40, 46)           // 功能键
        val ACTIVE_BG = Color.rgb(79, 91, 213)      // 激活态（shift/模式）
        const val KEY_RADIUS_DP = 10f
    }

    private lateinit var rootLayout: LinearLayout
    private lateinit var entryRow: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var keyboardLayout: LinearLayout

    private var mode = MODE_LETTERS
    private var shiftState = SHIFT_OFF
    private var shuffledLetters: List<String> = ('a'..'z').map { it.toString() }
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
            setTextColor(Color.rgb(160, 162, 170))
            textSize = 11f
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        rootLayout.addView(statusText)

        keyboardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        rootLayout.addView(keyboardLayout)

        // 全面屏手势条避让：把导航栏/挖孔 inset 加到底部 padding，避免最下排按键被手势条压住
        val basePadding = intArrayOf(dp(6), dp(6), dp(6), dp(8))
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val nav = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(basePadding[0], basePadding[1], basePadding[2], basePadding[3] + nav.bottom)
            WindowInsetsCompat.CONSUMED
        }

        refreshEntries()
        return rootLayout
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

    // ---------- 顶部条目芯片 ----------

    private fun refreshEntries() {
        val shuffle = DatabaseManager.isKeyboardShuffleEnabled(this)
        val letters = ('a'..'z').map { it.toString() }
        shuffledLetters = if (shuffle) letters.shuffled(Random) else letters
        shuffledDigits = (0..9).map { it.toString() }.let { if (shuffle) it.shuffled(Random) else it }

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

    // ---------- 键盘布局 ----------

    private fun buildKeyboard() {
        keyboardLayout.removeAllViews()
        when (mode) {
            MODE_LETTERS -> buildLetterRows()
            MODE_SYMBOLS -> buildSymbolRows()
            MODE_SYMBOLS_EXTRA -> buildSymbolExtraRows()
            MODE_NUMPAD -> buildNumpadRows()
        }
        keyboardLayout.addView(buildBottomRow())
    }

    /** 字母页：顶部数字行 + QWERTY 三行 */
    private fun buildLetterRows() {
        addRow(shuffledDigits)
        val l = shuffledLetters
        addRow(l.subList(0, 9))
        addRow(l.subList(9, 18))

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(shiftButton())
        l.subList(18, 26).forEach { ch -> row3.addView(letterButton(ch, 1f)) }
        row3.addView(backspaceButton(1.5f))
        keyboardLayout.addView(row3)
    }

    /** 符号页 ?123：数字行 + 常用符号两行 */
    private fun buildSymbolRows() {
        addRow(shuffledDigits)
        addRow(listOf("@", "#", "¥", "&", "*", "-", "+", "(", ")", "/"))

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(modeButton("=\\<", FN_BG) { mode = MODE_SYMBOLS_EXTRA; buildKeyboard() })
        listOf("'", "\"", ":", ";", "!", "?").forEach { row3.addView(keyButton(it, 1f, KEY_BG, keyNormalPad()) { commit(it) }) }
        row3.addView(backspaceButton(1.5f))
        keyboardLayout.addView(row3)
    }

    /** 扩展符号页 =\< */
    private fun buildSymbolExtraRows() {
        addRow(listOf("~", "`", "|", "^", "{", "}", "[", "]", "\\", "_"))
        addRow(listOf("$", "€", "£", "¥", "°", "•", "=", "+", "<", ">"))

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(modeButton("?123", FN_BG) { mode = MODE_SYMBOLS; buildKeyboard() })
        listOf(",", ".", ";", ":", "\"", "?").forEach { row3.addView(keyButton(it, 1f, KEY_BG, keyNormalPad()) { commit(it) }) }
        row3.addView(backspaceButton(1.5f))
        keyboardLayout.addView(row3)
    }

    /** 数字页：1-9 + 0 拨号盘式大按键 */
    private fun buildNumpadRows() {
        val n = (1..9).map { it.toString() }
        for (r in 0..2) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            n.subList(r * 3, r * 3 + 3).forEach { row.addView(numpadButton(it)) }
            keyboardLayout.addView(row)
        }
        val row4 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row4.addView(modeButton("=\\<", FN_BG, keyNumpadPad()) { mode = MODE_SYMBOLS; buildKeyboard() })
        row4.addView(numpadButton("0"))
        row4.addView(backspaceButton(1f, big = true))
        keyboardLayout.addView(row4)
    }

    /** 底行：两个模式键 + 空格 + TAB + 切换输入法（三页互达） */
    private fun buildBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val (a, b) = when (mode) {
            MODE_LETTERS -> "?123" to "123"
            MODE_NUMPAD -> "ABC" to "?123"
            else -> "ABC" to "123"
        }
        row.addView(modeButton(a, FN_BG) { switchMode(a) })
        row.addView(modeButton(b, FN_BG) { switchMode(b) })
        row.addView(keyButton(getString(R.string.keyboard_space), 4f, KEY_BG, keySmallPad()) { commit(" ") })
        row.addView(keyButton("TAB", 1.5f, FN_BG, keySmallPad()) { sendTab() })
        row.addView(keyButton("⌨", 1.5f, FN_BG, keySmallPad()) { hideSelf() })
        return row
    }

    private fun switchMode(target: String) {
        mode = when (target) {
            "?123" -> MODE_SYMBOLS
            "123" -> MODE_NUMPAD
            else -> MODE_LETTERS
        }
        shiftState = SHIFT_OFF
        buildKeyboard()
    }

    private fun addRow(keys: List<String>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keys.forEach { row.addView(keyButton(it, 1f, KEY_BG, keyNormalPad()) { onKey(it) }) }
        keyboardLayout.addView(row)
    }

    private fun shiftButton(): Button {
        val b = keyButton(
            if (shiftState == SHIFT_LOCKED) "⇪" else "⇧",
            1.5f, FN_BG, keyNormalPad()
        ) {
            shiftState = when (shiftState) {
                SHIFT_OFF -> SHIFT_ON
                SHIFT_ON -> SHIFT_LOCKED
                else -> SHIFT_OFF
            }
            buildKeyboard()
        }
        return if (shiftState != SHIFT_OFF) styleActive(b) else b
    }

    private fun letterButton(ch: String, weight: Float): Button {
        val label = if (shiftState == SHIFT_OFF) ch else ch.uppercase()
        return keyButton(label, weight, KEY_BG, keyNormalPad()) { onKey(ch) }
    }

    /** 数字页大按键 */
    private fun numpadButton(digit: String): Button =
        keyButton(digit, 1f, KEY_BG, keyNumpadPad()) { commit(digit) }.apply { textSize = 22f }

    private fun modeButton(label: String, bg: Int, pad: Int = keySmallPad(), onClick: () -> Unit): Button =
        keyButton(label, 1.5f, bg, pad) { onClick() }

    /** 字母页走 Shift 状态，符号/数字页直接输出 */
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

    private fun backspaceButton(weight: Float, big: Boolean = false): Button {
        val pad = if (big) keyNumpadPad() else keySmallPad()
        val b = Button(this).apply {
            text = "⌫"
            setTextColor(Color.WHITE)
            textSize = if (big) 22f else 16f
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, dp(pad), 0, dp(pad))
            background = keyBackground(FN_BG)
            layoutParams = rowParams(weight)
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

    private fun styleActive(b: Button): Button {
        b.background = keyBackground(ACTIVE_BG)
        return b
    }

    private fun keyBackground(bg: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(bg)
            cornerRadius = KEY_RADIUS_DP * resources.displayMetrics.density
        }

    private fun keyNumpadPad() = 20   // 数字大键：上下内边距 dp
    private fun keyNormalPad() = 10   // 普通键
    private fun keySmallPad() = 8     // 底行/功能键

    private fun keyButton(label: String, weight: Float, bg: Int, padDp: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 16f
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, dp(padDp), 0, dp(padDp))
            background = keyBackground(bg)
            layoutParams = rowParams(weight)
            setOnClickListener { onClick() }
        }
    }

    private fun rowParams(weight: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
            val m = dp(3)
            setMargins(m, m, m, m)
        }

    private fun actionButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = keyBackground(ACTIVE_BG)
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
