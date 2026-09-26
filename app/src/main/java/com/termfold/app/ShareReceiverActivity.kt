package com.termfold.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.termfold.app.files.FileActions
import com.termfold.app.shell.Projects
import com.termfold.app.shell.ShellPaths
import com.termfold.app.shell.ShellRuntime
import com.termfold.app.ui.theme.Palette
import com.termfold.app.ui.theme.TermFoldIcons
import com.termfold.app.ui.theme.TermFoldTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Share → TermFold" from any app: files of any type (or text and links, saved as a .txt) are
 * copied into the project or folder the user picks, inside the Ubuntu environment.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        val text = if (uris.isEmpty()) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        if (!ShellRuntime.isReady(this)) {
            Toast.makeText(this, R.string.share_not_ready, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (uris.isEmpty() && text.isNullOrBlank()) {
            Toast.makeText(this, R.string.share_nothing, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val home = File(ShellPaths.rootfsDir(this), "root")
        val projects = Projects.dir(this).apply { mkdirs() }
            .listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
        val what = when {
            uris.size == 1 -> FileActions.displayName(this, uris[0]) ?: "1 file"
            uris.size > 1 -> "${uris.size} files"
            else -> "the text"
        }

        setContent {
            TermFoldTheme {
                val scope = rememberCoroutineScope()
                var saving by remember { mutableStateOf(false) }
                fun saveTo(dir: File) {
                    saving = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                if (uris.isNotEmpty()) {
                                    FileActions.upload(this@ShareReceiverActivity, uris, dir).size
                                } else {
                                    val stamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())
                                    FileActions.unique(dir, "shared-$stamp.txt").writeText(text!!)
                                    1
                                }
                            }
                        }
                        val where = Projects.guestPath(this@ShareReceiverActivity, dir.absolutePath)
                            ?.replaceFirst("/root", "~") ?: dir.name
                        Toast.makeText(
                            this@ShareReceiverActivity,
                            result.fold({ getString(R.string.share_saved, where) }, { it.message ?: "Failed" }),
                            Toast.LENGTH_LONG,
                        ).show()
                        finish()
                    }
                }

                Box(
                    Modifier.fillMaxSize().background(Color(0x99000000)).clickable { if (!saving) finish() },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier
                            .widthIn(max = 460.dp)
                            .padding(24.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Palette.Card)
                            .clickable(enabled = false) {}
                            .padding(20.dp),
                    ) {
                        Text(stringResource(R.string.share_title), style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                        Text(
                            stringResource(R.string.share_body, what),
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.TextDim,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                        )
                        if (saving) {
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Palette.Accent)
                            }
                        } else {
                            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                                items(projects, key = { it.absolutePath }) { dir ->
                                    Destination(dir.name, TermFoldIcons.Folder) { saveTo(dir) }
                                }
                                item { Destination(stringResource(R.string.share_home), TermFoldIcons.Home) { saveTo(home) } }
                            }
                            TextButton(onClick = { finish() }, modifier = Modifier.align(Alignment.End)) {
                                Text(stringResource(R.string.action_cancel), color = Palette.TextDim)
                            }
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM),
        )
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
        else -> emptyList()
    }
}

@androidx.compose.runtime.Composable
private fun Destination(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Palette.Accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
