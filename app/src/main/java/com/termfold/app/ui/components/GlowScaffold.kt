package com.termfold.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.ShaderBrush
import com.termfold.app.ui.theme.Palette

/**
 * The warm bloom that sits behind the content.
 *
 * Drawn as two radial gradients anchored to the bottom corners rather than a bitmap, so it scales
 * to any screen with no asset and no allocation per frame.
 */
@Composable
fun GlowScaffold(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .drawBehind {
                // Kept low-alpha and anchored to the very bottom edge: the reference design uses a
                // warm bloom that reads as ambient light, not as a coloured panel.
                drawGlow(
                    center = Offset(size.width * 0.88f, size.height * 1.06f),
                    radius = size.width * 0.78f,
                    strength = 0.15f,
                )
                drawGlow(
                    center = Offset(size.width * 0.04f, size.height * 1.08f),
                    radius = size.width * 0.55f,
                    strength = 0.07f,
                )
            },
        content = content,
    )
}

/**
 * A blurred-looking light pool: a radial gradient that fades to fully transparent before the
 * edge of its bounds, which avoids the hard rim a gradient with non-zero end alpha would show.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlow(
    center: Offset,
    radius: Float,
    strength: Float,
) {
    val colors = listOf(
        Palette.Accent.copy(alpha = strength),
        Palette.Accent.copy(alpha = strength * 0.35f),
        Palette.Accent.copy(alpha = 0f),
    )

    val brush = ShaderBrush(
        RadialGradientShader(
            center = center,
            radius = radius,
            colors = colors,
            colorStops = listOf(0f, 0.5f, 1f),
        )
    )

    drawRect(
        brush = brush,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2, radius * 2),
    )
}

