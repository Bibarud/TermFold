package com.termfold.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termfold.app.R
import com.termfold.app.core.ProjectJob
import com.termfold.app.ui.theme.Mono
import com.termfold.app.ui.theme.Palette
import com.termfold.app.ui.theme.TermFoldIcons

private enum class ProjectKind { EMPTY, CLONE, IMPORT }

/**
 * Starting a project: an empty folder, a git clone, or a copy of a folder from the device.
 * Every project lives inside the Ubuntu environment (~/projects).
 */
@Composable
fun NewProjectDialog(
    onCreateEmpty: (name: String) -> Unit,
    onClone: (url: String, name: String) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf(ProjectKind.EMPTY) }
    val name = rememberTextFieldState()
    val url = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(kind) { if (kind != ProjectKind.IMPORT) runCatching { focus.requestFocus() } }

    val canSubmit = when (kind) {
        ProjectKind.EMPTY -> name.text.isNotBlank()
        ProjectKind.CLONE -> url.text.isNotBlank()
        ProjectKind.IMPORT -> true
    }
    val submit = {
        when (kind) {
            ProjectKind.EMPTY -> if (name.text.isNotBlank()) onCreateEmpty(name.text.toString())
            ProjectKind.CLONE -> if (url.text.isNotBlank()) onClone(url.text.toString(), name.text.toString())
            ProjectKind.IMPORT -> onImport()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Card,
        shape = RoundedCornerShape(20.dp),
        title = { Text(stringResource(R.string.project_new), color = Palette.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KindRow(TermFoldIcons.FolderPlus, stringResource(R.string.project_empty), stringResource(R.string.project_empty_desc), kind == ProjectKind.EMPTY) { kind = ProjectKind.EMPTY }
                KindRow(TermFoldIcons.GitBranch, stringResource(R.string.project_clone), stringResource(R.string.project_clone_desc), kind == ProjectKind.CLONE) { kind = ProjectKind.CLONE }
                KindRow(TermFoldIcons.FolderImport, stringResource(R.string.project_import), stringResource(R.string.project_import_desc), kind == ProjectKind.IMPORT) { kind = ProjectKind.IMPORT }
                Spacer(Modifier.height(4.dp))
                when (kind) {
                    ProjectKind.EMPTY -> Field(name, stringResource(R.string.project_name_hint), focus, submit)
                    ProjectKind.CLONE -> {
                        Field(url, "https://github.com/user/repo.git", focus, submit, mono = true)
                        Field(name, stringResource(R.string.project_name_optional), null, submit)
                    }
                    ProjectKind.IMPORT -> Text(
                        stringResource(R.string.project_import_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextDim,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = canSubmit) {
                Text(
                    stringResource(if (kind == ProjectKind.IMPORT) R.string.project_choose_folder else R.string.files_create),
                    color = if (canSubmit) Palette.Accent else Palette.TextFaint,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Palette.TextDim) }
        },
    )
}

@Composable
private fun KindRow(icon: ImageVector, title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Palette.CardPressed else Palette.Bg)
            .border(1.dp, if (selected) Palette.Border else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) Palette.Accent else Palette.TextDim, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = Palette.Text)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
        }
    }
}

@Composable
private fun Field(
    state: androidx.compose.foundation.text.input.TextFieldState,
    hint: String,
    focus: FocusRequester?,
    onSubmit: () -> Unit,
    mono: Boolean = false,
) {
    val style = MaterialTheme.typography.bodyMedium.copy(
        color = Palette.Text,
        fontFamily = if (mono) Mono else MaterialTheme.typography.bodyMedium.fontFamily,
        fontSize = if (mono) 13.sp else MaterialTheme.typography.bodyMedium.fontSize,
    )
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Bg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (state.text.isEmpty()) Text(hint, style = style.copy(color = Palette.TextFaint))
        BasicTextField(
            state = state,
            lineLimits = TextFieldLineLimits.SingleLine,
            textStyle = style,
            cursorBrush = SolidColor(Palette.Accent),
            onKeyboardAction = { onSubmit() },
            modifier = Modifier.fillMaxWidth().let { if (focus != null) it.focusRequester(focus) else it },
        )
    }
}

/** Progress of a clone or import, then its result. */
@Composable
fun ProjectJobDialog(job: ProjectJob, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (job.done || job.error != null) onDismiss() },
        containerColor = Palette.Card,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                when {
                    job.error != null -> stringResource(R.string.project_job_failed, job.title)
                    job.done -> stringResource(R.string.project_job_done, job.title)
                    else -> stringResource(R.string.project_job_working, job.title)
                },
                color = Palette.Text,
            )
        },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!job.done && job.error == null) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.Accent)
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    job.error ?: job.progress,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 12.sp),
                    color = if (job.error != null) Palette.Pink else Palette.TextDim,
                    maxLines = 8,
                )
            }
        },
        confirmButton = {
            if (job.done && job.folderId != null) {
                TextButton(onClick = { onOpen(job.folderId) }) { Text(stringResource(R.string.project_open), color = Palette.Accent) }
            } else if (job.error != null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close), color = Palette.TextDim) }
            }
        },
    )
}
