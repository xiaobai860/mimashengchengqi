package com.lesspass.app

import android.content.Intent
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.kunzisoft.keepass.database.element.entry.EntryKDBX
import com.lesspass.app.data.DatabaseManager
import kotlin.random.Random

/**
 * 密码键盘（系统级 IME，仿 KeePassDX 魔法键盘）。
 * 在其它 App 的输入框中可被调起：既能一键填入本应用密码库中的用户名/密码，
 * 也内置打字键盘（KeePassDX 魔法键盘没有打字功能），并支持在设置中开启键盘乱序。
 */
class MimaKeyboardService : InputMethodService() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var entryRow: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var keyboardLayout: LinearLayout

    override fun onCreateInputView(): View {
        val bg = Color.rgb(28, 27, 31)
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(8, 8, 8, 8)
        }

        entryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = HorizontalScrollView(this).apply { addView(entryRow) }
        rootLayout.addView(scroll)

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        rootLayout.addView(statusText)

        keyboardLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rootLayout.addView(keyboardLayout)

        refreshEntries()
        return rootLayout
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        refreshEntries()
    }

    private fun refreshEntries() {
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

    private fun sendTab() {
        currentInputConnection?.apply {
            sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
            sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB))
        }
    }

    private fun buildKeyboard() {
        keyboardLayout.removeAllViews()
        val shuffle = DatabaseManager.isKeyboardShuffleEnabled(this)
        val letters = ('a'..'z').map { it.toString() }.toMutableList()
        if (shuffle) letters.shuffle(Random)

        val rows = listOf(
            letters.subList(0, 9),
            letters.subList(9, 18),
            letters.subList(18, 26),
        )
        rows.forEach { rowLetters ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowLetters.forEach { ch ->
                row.addView(keyButton(ch, 1f) { commit(ch) })
            }
            keyboardLayout.addView(row)
        }

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(keyButton(getString(R.string.keyboard_space), 3f) { commit(" ") })
        bottom.addView(keyButton("TAB", 1f) { sendTab() })
        bottom.addView(keyButton("⌫", 1f) { delete() })
        bottom.addView(keyButton("⌨", 1f) { hideSelf() })
        keyboardLayout.addView(bottom)
    }

    private fun keyButton(label: String, weight: Float, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, weight
            )
            setOnClickListener { onClick() }
        }
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setOnClickListener { onClick() }
        }
    }

    private fun commit(s: String) {
        currentInputConnection?.commitText(s, 1)
    }

    private fun delete() {
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
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
