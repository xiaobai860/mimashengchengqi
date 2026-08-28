package com.lesspass.app.ui.theme

import android.content.Context
import android.content.SharedPreferences

/**
 * 主题偏好持久化（SharedPreferences）。
 * 存储：主题模式（跟随系统/亮/暗）、动态取色开关、预设种子色索引。
 * 独立于业务数据（密码本/设置），避免清除数据时误删外观偏好。
 */
object ThemePrefs {
    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_MODE = "theme_mode"          // 0=SYSTEM, 1=LIGHT, 2=DARK
    private const val KEY_DYNAMIC = "theme_dynamic"    // true=动态取色
    private const val KEY_SEED = "theme_seed_index"    // PresetSeed.ordinal

    data class State(
        val mode: ThemeMode = ThemeMode.SYSTEM,
        val dynamicColor: Boolean = true,
        val seed: PresetSeed = PresetSeed.DEFAULT,
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): State {
        val p = prefs(context)
        return State(
            mode = ThemeMode.entries.getOrElse(p.getInt(KEY_MODE, 0)) { ThemeMode.SYSTEM },
            dynamicColor = p.getBoolean(KEY_DYNAMIC, true),
            seed = PresetSeed.fromIndex(p.getInt(KEY_SEED, PresetSeed.DEFAULT.ordinal)),
        )
    }

    fun save(context: Context, state: State) {
        prefs(context).edit()
            .putInt(KEY_MODE, state.mode.ordinal)
            .putBoolean(KEY_DYNAMIC, state.dynamicColor)
            .putInt(KEY_SEED, state.seed.ordinal)
            .apply()
    }
}
