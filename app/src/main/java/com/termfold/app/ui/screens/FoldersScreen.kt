package com.termfold.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termfold.app.R
import com.termfold.app.core.Folder
import com.termfold.app.ui.components.BareIconButton
import com.termfold.app.ui.components.BottomNavSpacer
import com.termfold.app.ui.components.CircleIconButton
import com.termfold.app.ui.components.CountChevron
import com.termfold.app.ui.components.LeadingTile
import com.termfold.app.ui.components.ListRow
import com.termfold.app.ui.components.RowSpec
import com.termfold.app.ui.components.RowSpacer
import com.termfold.app.ui.components.SectionTitle
import com.termfold.app.ui.theme.Mono
import com.termfold.app.ui.theme.Palette
import com.termfold.app.ui.theme.TermFoldIcons
import com.termfold.app.ui.theme.folderTint

/** Screen 1: the folder list. Uses a card grid when [useGrid] is set (tablet / landscape). */
@Composable
fun FoldersScreen(
    folders: List<Folder>,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onAddFolder: () -> Unit,
    onSearchToggle: () -> Unit,
    searchOpen: Boolean,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    useGrid: Boolean = false,
) {
    val visible = remember(folders, query) {
        if (query.isBlank()) {
            folders
        } else {
            folders.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.path.contains(query, ignoreCase = true)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // The rail already carries the logo, and the design folds search into the title row on
        // wide layouts, so the compact header is only rendered in the phone layout.
        if (!useGrid) {
            HeaderBar(
                onSearchToggle = onSearchToggle,
                searchOpen = searchOpen,
                onMore = onMore,
            )
        }

        if (searchOpen) {
            SearchField(
                query = query,
                onQueryChange = onQueryChange,
                modifier = Modifier.padding(
                    start = if (useGrid) 30.dp else 20.dp,
                    end = if (useGrid) 26.dp else 20.dp,
                    top = 4.dp,
                ),
            )
            Spacer(Modifier.height(12.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = if (useGrid) 30.dp else 22.dp,
                    end = if (useGrid) 26.dp else 16.dp,
                    top = if (searchOpen) 0.dp else 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle(
                text = androidx.compose.ui.res.stringResource(R.string.folders_title),
                modifier = Modifier.weight(1f),
            )
            if (useGrid) {
                BareIconButton(
                    icon = TermFoldIcons.Search,
                    contentDescription = androidx.compose.ui.res.stringResource(R.string.cd_search),
                    onClick = onSearchToggle,
                    tint = if (searchOpen) Palette.Accent else Palette.Text,
                )
                Spacer(Modifier.size(8.dp))
            }
            CircleIconButton(
                icon = TermFoldIcons.Plus,
                contentDescription = androidx.compose.ui.res.stringResource(R.string.cd_add_folder),
                onClick = onAddFolder,
                primary = true,
                size = 46,
            )
        }

        Spacer(Modifier.height(18.dp))

        when {
            visible.isEmpty() -> EmptyState(
                title = androidx.compose.ui.res.stringResource(R.string.empty_folders),
                hint = androidx.compose.ui.res.stringResource(R.string.empty_folders_hint),
            )

            useGrid -> FolderGrid(folders = visible, onOpenFolder = onOpenFolder)

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                items(visible, key = { it.id }) { folder ->
                    ListRow(
                        title = folder.name,
                        leading = {
                            LeadingTile(
                                tint = folderTint(folder.tint),
                                icon = TermFoldIcons.Folder,
                            )
                        },
                        trailing = { CountChevron(folder.sessions.size) },
                        onClick = { onOpenFolder(folder.id) },
                    )
                    RowSpacer()
                }
                item { BottomNavSpacer() }
            }
        }
    }
}

/** Multi-column card grid, matching the tablet layout in the design. */
@Composable
private fun FolderGrid(
    folders: List<Folder>,
    onOpenFolder: (String) -> Unit,
) {
    // Three columns matches the tablet design; only very wide displays get a fourth.
    val columns = if (LocalConfiguration.current.screenWidthDp >= 1400) 4 else 3

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 30.dp, end = 26.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(folders, key = { it.id }) { folder ->
            FolderCard(folder = folder, onClick = { onOpenFolder(folder.id) })
        }
    }
}

/** One folder card: tinted glyph above the name and session count. */
@Composable
private fun FolderCard(folder: Folder, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Card)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 20.dp),
    ) {
        Icon(
            imageVector = TermFoldIcons.Folder,
            contentDescription = null,
            tint = folderTint(folder.tint),
            modifier = Modifier.size(40.dp),
        )

        Spacer(Modifier.height(22.dp))

        Text(
            text = folder.name,
            style = MaterialTheme.typography.titleMedium,
            color = Palette.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = folder.sessions.size.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextDim,
            )
            Spacer(Modifier.size(6.dp))
            Icon(
                imageVector = TermFoldIcons.ChevronRight,
                contentDescription = null,
                tint = Palette.TextFaint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Logo, search and overflow actions sharing one compact bar. */
@Composable
private fun HeaderBar(
    onSearchToggle: () -> Unit,
    searchOpen: Boolean,
    onMore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = TermFoldIcons.Terminal,
            contentDescription = null,
            tint = Palette.Text,
            modifier = Modifier.size(26.dp),
        )

        Spacer(Modifier.weight(1f))

        BareIconButton(
            icon = TermFoldIcons.Search,
            contentDescription = androidx.compose.ui.res.stringResource(R.string.cd_search),
            onClick = onSearchToggle,
            tint = if (searchOpen) Palette.Accent else Palette.Text,
        )
        BareIconButton(
            icon = TermFoldIcons.More,
            contentDescription = androidx.compose.ui.res.stringResource(R.string.cd_more),
            onClick = onMore,
        )
    }
}

/** Inline search field; kept plain so it reads as part of the header, not a separate card. */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    val focusRequester = remember { FocusRequester() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Field),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Palette.Text),
            cursorBrush = SolidColor(Palette.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 15.dp)
                .focusRequester(focusRequester),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        text = placeholder.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.TextFaint,
                    )
                }
                inner()
            },
        )
    }
}

@Composable
fun EmptyState(title: String, hint: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = TermFoldIcons.Terminal,
            contentDescription = null,
            tint = Palette.TextFaint,
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Palette.TextDim,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextFaint,
            textAlign = TextAlign.Center,
        )
    }
}

/** Simple single-field prompt used for renaming and for naming new sessions. */
@Composable
fun TextPromptDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    placeholder: String = "",
    confirmLabel: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Card,
        titleContentColor = Palette.Text,
        textContentColor = Palette.TextDim,
        title = {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.Field),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Palette.Text),
                    cursorBrush = SolidColor(Palette.Accent),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 15.dp),
                    decorationBox = { inner ->
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.TextFaint,
                            )
                        }
                        inner()
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel
                        ?: androidx.compose.ui.res.stringResource(R.string.action_add),
                    color = Palette.Accent,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.action_cancel),
                    color = Palette.TextDim,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    )
}

/**
 * Row of selectable chips for choosing a session preset.
 *
 * Chips size to their label and the row scrolls horizontally, because preset names vary in length
 * and equal-width chips would truncate the longer ones.
 */
@Composable
fun PresetChips(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selectedIndex
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) Palette.AccentSoft else Palette.Field)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    ),
                    color = if (active) Palette.Accent else Palette.TextDim,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}


/** Key/value line for the settings screen. */
@Composable
fun InfoLine(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextDim,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = if (mono) {
                TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 18.sp)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = Palette.Text,
            textAlign = TextAlign.End,
        )
    }
}

/** Divider used between settings rows. */
@Composable
fun HairlineDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Palette.BorderSoft)
    )
}

