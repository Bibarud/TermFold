package com.termfold.app.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.decode.SvgDecoder
import com.termfold.app.ui.theme.Palette

/**
 * Loads registry icons, which are SVGs — a format Android cannot decode natively — through one
 * shared Coil loader with the SVG decoder registered.
 */
object AgentIconLoader {
    @Volatile
    private var loader: ImageLoader? = null

    fun of(context: Context): ImageLoader =
        loader ?: synchronized(this) {
            loader ?: ImageLoader.Builder(context.applicationContext)
                .components { add(SvgDecoder.Factory()) }
                .build()
                .also { loader = it }
        }
}

/**
 * An agent's registry icon, with a monogram tile as the fallback while loading or when the icon
 * cannot be fetched. This is what distinguishes ACP sessions from plain shell sessions.
 */
@Composable
fun AgentIconImage(
    iconUrl: String,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
) {
    if (iconUrl.isBlank()) {
        MonogramTile(name = name, size = size, cornerRadius = cornerRadius, modifier = modifier)
        return
    }
    // Registry icons are monochrome SVGs drawn in black and meant to be tinted by the client, so
    // untinted they vanish on a dark tile. They also run edge to edge, hence the inset.
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(Palette.Field),
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = iconUrl,
            imageLoader = AgentIconLoader.of(LocalContext.current),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Palette.Text),
            modifier = Modifier
                .fillMaxSize()
                .padding(size * 0.2f),
            loading = { Monogram(name) },
            error = { Monogram(name) },
        )
    }
}

@Composable
private fun Monogram(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = name.trim().firstOrNull()?.uppercase() ?: "A",
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            color = Palette.Accent,
        )
    }
}

@Composable
fun MonogramTile(
    name: String,
    size: Dp,
    cornerRadius: Dp = 12.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(Palette.AccentSoft),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().firstOrNull()?.uppercase() ?: "A",
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            color = Palette.Accent,
        )
    }
}
