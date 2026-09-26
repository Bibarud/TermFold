package com.termfold.app.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.termfold.app.R
import com.termfold.app.files.FileActions
import com.termfold.app.ui.components.BareIconButton
import com.termfold.app.ui.theme.Palette
import com.termfold.app.ui.theme.TermFoldIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_SCALE = 6f

private sealed interface Picture {
    data object Loading : Picture
    data class Ready(val bitmap: ImageBitmap, val width: Int, val height: Int) : Picture
    data object Failed : Picture
}

/**
 * A picture from the Linux side: pinch or double-tap to zoom, drag to pan, and hand it on:
 * copy it to the clipboard (to paste into an agent or another app), share, open with, or save
 * it to the device.
 */
@Composable
internal fun ImageViewer(file: File, onClose: () -> Unit, modifier: Modifier = Modifier, readOnly: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picture by remember(file) { mutableStateOf<Picture>(Picture.Loading) }
    var message by remember { mutableStateOf<String?>(null) }
    val scale = remember(file) { Animatable(1f) }
    val offset = remember(file) { Animatable(Offset.Zero, Offset.VectorConverter) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(file) {
        picture = withContext(Dispatchers.IO) { decode(file) }
    }
    LaunchedEffect(message) {
        if (message != null) {
            delay(2500)
            message = null
        }
    }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = box.width * (s - 1f) / 2f
        val maxY = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(FileActions.mimeOf(file))) { target: Uri? ->
        if (target != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { FileActions.exportTo(context, file, target) }.isSuccess }
            message = if (ok) context.getString(R.string.fm_saved, file.name) else context.getString(R.string.files_unreadable)
        }
    }

    Column(modifier.background(Palette.Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BareIconButton(icon = TermFoldIcons.Close, contentDescription = stringResource(R.string.action_close), onClick = onClose)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val info = (picture as? Picture.Ready)?.let { "${it.width} × ${it.height} · ${humanSize(file.length())}" }
                Text(
                    info ?: humanSize(file.length()),
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextFaint,
                    maxLines = 1,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.BorderSoft))

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .onSizeChanged { box = it }
                .pointerInput(file) {
                    detectTapGestures(
                        onDoubleTap = { tap ->
                            scope.launch {
                                if (scale.value > 1.05f) {
                                    launch { scale.animateTo(1f, spring()) }
                                    offset.animateTo(Offset.Zero, spring())
                                } else {
                                    val target = 2.5f
                                    val centre = Offset(box.width / 2f, box.height / 2f)
                                    launch { scale.animateTo(target, spring()) }
                                    offset.animateTo(clamp((centre - tap) * (target - 1f), target), spring())
                                }
                            }
                        },
                    )
                }
                .pointerInput(file) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scope.launch {
                            val s = (scale.value * zoom).coerceIn(1f, MAX_SCALE)
                            scale.snapTo(s)
                            offset.snapTo(clamp(offset.value + pan, s))
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            when (val p = picture) {
                Picture.Loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Palette.Accent)
                Picture.Failed -> Text(stringResource(R.string.files_binary), style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
                is Picture.Ready -> androidx.compose.animation.AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.96f),
                ) {
                    Image(
                        p.bitmap,
                        contentDescription = file.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .graphicsLayer {
                                scaleX = scale.value
                                scaleY = scale.value
                                translationX = offset.value.x
                                translationY = offset.value.y
                            },
                    )
                }
            }

            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedVisibility(
                    visible = message != null,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 },
                ) {
                    Text(
                        message.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Text,
                        modifier = Modifier
                            .padding(bottom = 10.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Palette.CardPressed)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
                Row(
                    Modifier
                        .widthIn(max = 520.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Palette.Card)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ViewerAction(TermFoldIcons.ImageCopy, stringResource(R.string.img_copy)) {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { runCatching { FileActions.copyImage(context, file) }.isSuccess }
                            // Android 13+ confirms clipboard copies itself.
                            if (!ok) {
                                message = context.getString(R.string.files_unreadable)
                            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                message = context.getString(R.string.img_copied)
                            }
                        }
                    }
                    // A system picture is only looked at (and copied); sharing is for your own files.
                    if (!readOnly) {
                        ViewerAction(TermFoldIcons.Share, stringResource(R.string.fm_share)) {
                            runCatching { FileActions.share(context, listOf(file)) }.onFailure { message = it.message }
                        }
                        ViewerAction(TermFoldIcons.OpenExternal, stringResource(R.string.img_open_with)) {
                            runCatching { FileActions.openWith(context, file) }.onFailure { message = it.message }
                        }
                        ViewerAction(TermFoldIcons.SaveToDevice, stringResource(R.string.img_save)) { saveAs.launch(file.name) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = Palette.Text, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.TextDim, maxLines = 1)
    }
}

/** Decodes at most ~2560 px on the long side: sharp when zoomed, without a huge allocation. */
private fun decode(file: File): Picture {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Picture.Failed
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2560) sample *= 2
    val bitmap = runCatching {
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull() ?: return Picture.Failed
    return Picture.Ready(bitmap.asImageBitmap(), bounds.outWidth, bounds.outHeight)
}

internal fun humanSize(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
    size < 1024L * 1024 * 1024 -> "%.1f MB".format(size / 1048576.0)
    else -> "%.1f GB".format(size / 1073741824.0)
}
