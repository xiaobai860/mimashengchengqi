package com.mima.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Mima 统一形状系统（Material You）。
 *
 * 现状痛点：各页混用 4dp / 8dp / 12dp / 26dp 圆角，风格不一致。
 * 这里收敛为一套语义化形状，全站复用，保证卡片、按钮、对话框、步进器圆角统一。
 */
object MimaShapes {
    /** 卡片 / Surface 分组：16dp */
    val card = RoundedCornerShape(16.dp)
    /** 按钮 / 下拉菜单容器：14dp */
    val button = RoundedCornerShape(14.dp)
    /** 对话框（AlertDialog 走主题 extraLarge，此处供自定义弹窗复用）：28dp */
    val dialog = RoundedCornerShape(28.dp)
    /** 数字步进器边框：14dp */
    val stepper = RoundedCornerShape(14.dp)
    /** 全圆角胶囊（搜索框 / 色板圆点 / 正圆 FAB）：50% */
    val pill = RoundedCornerShape(percent = 50)
    /** 全圆形（空状态插画底、正圆 FAB）：50% 别名 */
    val chip = RoundedCornerShape(percent = 50)
}

/**
 * 接入 MaterialTheme 的默认形状，让 Card / Button / FAB / AlertDialog 等
 * 内置组件自动采用统一圆角（覆盖 M3 默认 12dp 偏方的观感）。
 */
val MimaMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 品牌垂直渐变：用于解锁页 / 生成页顶部色块、空状态插画。
 * 直接使用当前主题色，天然跟随动态取色与预设色板（保持 Material You 一致）。
 */
fun brandGradientBrush(primary: Color, secondary: Color): Brush =
    Brush.verticalGradient(listOf(primary, secondary))
