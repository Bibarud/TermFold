package com.termfold.app.ui.screens

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.termfold.app.R
import com.termfold.app.shell.ShellRuntime
import com.termfold.app.shell.ShellPaths
import com.termfold.app.ui.components.StatusDot
import com.termfold.app.ui.theme.Mono
import com.termfold.app.ui.theme.Palette

/**
 * Shown while the bundled Ubuntu environment is being unpacked for the first time.
 *
 * This is a one-time cost of a few seconds, so the screen reports the current step rather than a
 * fake percentage, and it explains that the environment is a one-off install.
 */
@Composable
fun ProvisioningScreen(
    step: String,
    progress: Float,
    error: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(targetValue = progress, label = "provision")

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.provision_title),
            style = MaterialTheme.typography.titleLarge,
            color = Palette.Text,
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.provision_body, ShellRuntime.FLAVOUR),
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextDim,
        )

        Spacer(Modifier.height(26.dp))

        if (error == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Palette.Field),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animated.coerceIn(0f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Palette.Accent),
                )
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = step,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                color = Palette.TextFaint,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Palette.Card)
                    .padding(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(Palette.Pink)
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = stringResource(R.string.provision_failed),
                        style = MaterialTheme.typography.titleMedium,
                        color = Palette.Text,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    color = Palette.TextDim,
                )
            }

            Spacer(Modifier.height(18.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.Accent)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.action_retry),
                    style = MaterialTheme.typography.labelLarge,
                    color = Palette.OnAccent,
                )
            }
        }
    }
}

/** Where the environment lives and how big it is, for the settings screen. */
data class ShellStatus(
    val ready: Boolean,
    val flavour: String,
    val architecture: String,
    val sizeText: String,
    val supported: Boolean,
)

/**
 * Reads the current state of the bundled environment without provisioning it. Cheap: the size
 * on disk is not included, because measuring it means visiting every file; see [shellSizeText].
 */
fun shellStatus(context: Context, sizeText: String = ""): ShellStatus {
    val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
    val supported = com.termfold.app.shell.ShellConfig.rootfsAbi(abi) != null
    val ready = runCatching { ShellRuntime.isReady(context) }.getOrDefault(false)
    return ShellStatus(
        ready = ready,
        flavour = ShellRuntime.FLAVOUR,
        architecture = abi,
        sizeText = sizeText,
        supported = supported,
    )
}

/**
 * The environment's size on disk. Call it off the main thread: once apt, Node and agents are
 * installed it is tens of thousands of files.
 *
 * Symlinks are never followed. Ubuntu has directory links that point back up the tree
 * (`/usr/bin/X11 -> .`), and following them made the old walk loop until the app was killed,
 * which is what closed the app on opening Settings.
 */
fun shellSizeText(context: Context): String {
    val bytes = runCatching {
        var total = 0L
        java.nio.file.Files.walkFileTree(
            ShellPaths.rootDir(context).toPath(),
            object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(
                    file: java.nio.file.Path,
                    attrs: java.nio.file.attribute.BasicFileAttributes,
                ): java.nio.file.FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: java.nio.file.Path,
                    exc: java.io.IOException,
                ): java.nio.file.FileVisitResult = java.nio.file.FileVisitResult.CONTINUE
            },
        )
        total
    }.getOrDefault(0L)
    Log.d("ShellStatus", "rootfs bytes=$bytes")
    return formatBytes(bytes)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format("%.0f MB", bytes / (1L shl 20).toDouble())
    else -> String.format("%.0f KB", bytes / 1024.0)
}
