package com.lesspass.app

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

/**
 * 空状态插画：历史记录（时钟 + 历史环形箭头）。
 * 直接使用 MaterialTheme.colorScheme，保证与 M3 主题配色一致且深浅色自适应，
 * 不再依赖 Android 平台主题属性（原先的 colorBackground 填充会导致主体与背景同色而“看不清”）。
 */
@Composable
fun HistoryEmptyIllustration(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val R = size.minDimension / 2f

        // 柔和的品牌背景圆
        drawCircle(color = scheme.primaryContainer, radius = R * 0.94f, center = Offset(cx, cy))

        // 表盘
        val faceR = R * 0.66f
        drawCircle(color = scheme.surfaceVariant, radius = faceR, center = Offset(cx, cy))
        drawCircle(
            color = scheme.primary,
            radius = faceR,
            center = Offset(cx, cy),
            style = Stroke(width = R * 0.075f)
        )

        // 历史环形箭头（围绕表盘外缘）
        val arrowR = faceR + R * 0.16f
        val startDeg = 35f
        val sweepDeg = 250f
        drawArc(
            color = scheme.primary,
            startAngle = startDeg,
            sweepAngle = sweepDeg,
            useCenter = false,
            topLeft = Offset(cx - arrowR, cy - arrowR),
            size = Size(arrowR * 2f, arrowR * 2f),
            style = Stroke(width = R * 0.075f, cap = StrokeCap.Round)
        )
        // 箭头头部
        val endRad = Math.toRadians((startDeg + sweepDeg).toDouble())
        val ex = cx + arrowR * cos(endRad).toFloat()
        val ey = cy + arrowR * sin(endRad).toFloat()
        val tx = -sin(endRad).toFloat()
        val ty = cos(endRad).toFloat()
        val head = R * 0.17f
        val perpX = ty
        val perpY = -tx
        val p1 = Offset(ex - tx * head + perpX * head * 0.55f, ey - ty * head + perpY * head * 0.55f)
        val p2 = Offset(ex - tx * head - perpX * head * 0.55f, ey - ty * head - perpY * head * 0.55f)
        val headPath = Path().apply {
            moveTo(ex, ey)
            lineTo(p1.x, p1.y)
            lineTo(p2.x, p2.y)
            close()
        }
        drawPath(headPath, color = scheme.primary)

        // 指针（用 onSurface 保证在 surfaceVariant 表盘上有足够对比）
        val hourLen = faceR * 0.5f
        val minLen = faceR * 0.72f
        drawLine(
            color = scheme.onSurface,
            start = Offset(cx, cy),
            end = Offset(cx, cy - hourLen),
            strokeWidth = R * 0.06f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = scheme.onSurface,
            start = Offset(cx, cy),
            end = Offset(cx + minLen * 0.7f, cy + minLen * 0.25f),
            strokeWidth = R * 0.06f,
            cap = StrokeCap.Round
        )
        drawCircle(color = scheme.primary, radius = R * 0.08f, center = Offset(cx, cy))
    }
}

/**
 * 空状态插画：密码本（保险库/密码箱）。
 */
@Composable
fun VaultEmptyIllustration(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val R = size.minDimension / 2f

        // 柔和的品牌背景圆
        drawCircle(color = scheme.primaryContainer, radius = R * 0.94f, center = Offset(cx, cy))

        // 保险库主体
        val bodyW = R * 1.1f
        val bodyH = R * 0.95f
        val left = cx - bodyW / 2f
        val top = cy - bodyH / 2f
        val radius = CornerRadius(R * 0.16f, R * 0.16f)
        drawRoundRect(
            color = scheme.surfaceVariant,
            topLeft = Offset(left, top),
            size = Size(bodyW, bodyH),
            cornerRadius = radius
        )
        drawRoundRect(
            color = scheme.primary,
            topLeft = Offset(left, top),
            size = Size(bodyW, bodyH),
            cornerRadius = radius,
            style = Stroke(width = R * 0.07f)
        )

        // 侧边把手
        val handleW = R * 0.12f
        drawRoundRect(
            color = scheme.primary,
            topLeft = Offset(left + bodyW - handleW * 0.4f, cy - R * 0.18f),
            size = Size(handleW, R * 0.36f),
            cornerRadius = CornerRadius(R * 0.05f, R * 0.05f)
        )

        // 转盘 + 钥匙孔
        val dialR = R * 0.3f
        val dx = cx - bodyW * 0.05f
        drawCircle(color = scheme.primary, radius = dialR, center = Offset(dx, cy))
        drawCircle(color = scheme.surfaceVariant, radius = dialR * 0.55f, center = Offset(dx, cy))
        drawCircle(color = scheme.primary, radius = dialR * 0.16f, center = Offset(dx, cy - dialR * 0.12f))
        drawRoundRect(
            color = scheme.primary,
            topLeft = Offset(dx - dialR * 0.08f, cy - dialR * 0.05f),
            size = Size(dialR * 0.16f, dialR * 0.32f),
            cornerRadius = CornerRadius(dialR * 0.04f, dialR * 0.04f)
        )

        // 铆钉
        drawCircle(color = scheme.outline, radius = R * 0.03f, center = Offset(left + R * 0.12f, top + R * 0.16f))
        drawCircle(color = scheme.outline, radius = R * 0.03f, center = Offset(left + R * 0.12f, top + bodyH - R * 0.16f))
    }
}
