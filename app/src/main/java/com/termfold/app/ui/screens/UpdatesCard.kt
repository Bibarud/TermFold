package com.termfold.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.termfold.app.BuildConfig
import com.termfold.app.R
import com.termfold.app.ui.theme.Palette
import com.termfold.app.update.UpdateManager
import com.termfold.app.update.UpdateManager.State

/**
 * Settings card for app updates: check GitHub, download the new APK and hand it to Android's
 * installer. Opening Settings checks too (at most twice a day), so a new version is noticed
 * without having to ask.
 */
@Composable
fun UpdatesCard() {
    val context = LocalContext.current
    val state by UpdateManager.state.collectAsState()

    LaunchedEffect(Unit) { UpdateManager.checkIfDue(context) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.Card)
            .padding(18.dp),
    ) {
        Text(
            text = stringResource(R.string.update_current, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.titleMedium,
            color = Palette.Text,
        )
        Spacer(Modifier.height(8.dp))

        when (val s = state) {
            State.Idle -> Body(stringResource(R.string.update_hint))
            State.Checking -> Body(stringResource(R.string.update_checking))
            State.UpToDate -> Body(stringResource(R.string.update_latest))
            is State.Failed -> Body(stringResource(R.string.update_failed, s.message), error = true)
            is State.Available -> {
                Body(stringResource(R.string.update_available, s.release.version), strong = true)
                Notes(s.release.notes)
            }
            is State.Downloading -> {
                Body(stringResource(R.string.update_downloading, (s.fraction * 100).toInt()), strong = true)
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.Field),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(s.fraction.coerceIn(0f, 1f))
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Palette.Accent),
                    )
                }
            }
            is State.Ready -> {
                Body(stringResource(R.string.update_ready, s.release.version), strong = true)
                if (s.needsPermission) {
                    Spacer(Modifier.height(6.dp))
                    Body(stringResource(R.string.update_allow_install))
                }
                Notes(s.release.notes)
            }
        }

        val actions = when (val s = state) {
            State.Checking, is State.Downloading -> emptyList()
            is State.Available -> listOf(stringResource(R.string.update_download) to { UpdateManager.download(context) })
            is State.Ready -> listOf(stringResource(R.string.update_install) to { UpdateManager.install(context) })
            is State.Failed -> listOf(stringResource(R.string.update_check) to { UpdateManager.check(context) })
            else -> listOf(stringResource(R.string.update_check) to { UpdateManager.check(context) })
        }
        if (actions.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            ActionRow(items = actions)
        }
    }
}

@Composable
private fun Body(text: String, strong: Boolean = false, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = when {
            error -> Palette.Pink
            strong -> Palette.Text
            else -> Palette.TextDim
        },
    )
}

@Composable
private fun Notes(notes: String) {
    if (notes.isBlank()) return
    Spacer(Modifier.height(10.dp))
    Text(
        text = stringResource(R.string.update_whats_new),
        style = MaterialTheme.typography.labelLarge,
        color = Palette.TextDim,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = notes,
        style = MaterialTheme.typography.bodySmall,
        color = Palette.TextFaint,
        maxLines = 9,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}
