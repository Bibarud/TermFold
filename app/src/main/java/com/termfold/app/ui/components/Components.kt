package com.termfold.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.termfold.app.ui.theme.Palette

/** Shared row geometry so every list in the app lines up. */
object RowSpec {
    val Height = 64.dp
    val Radius = 18.dp
    val Gap = 10.dp
    val HorizontalPadding = 7.dp
}

/** A dark card row: leading tinted tile, a single line of text, optional trailing slot. */
@Composable
fun ListRow(
    title: String,
    leading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    highlighted: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(RowSpec.Radius)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(RowSpec.Height)
            .clip(shape)
            .background(if (highlighted) Palette.CardPressed else Palette.Card)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = ripple(color = Color.White),
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = RowSpec.HorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()

        Spacer(Modifier.width(15.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Palette.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

/** Leading tile shared by folders and sessions, tinted per item. */
@Composable
fun LeadingTile(
    tint: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
) {
    Box(
        modifier = modifier.size(46.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector = icon,
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(26.dp),
        )
    }
}

/** The `>_` mark shown on session rows. */
@Composable
fun SessionMark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(46.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector = com.termfold.app.ui.theme.TermFoldIcons.Terminal,
            contentDescription = null,
            colorFilter = ColorFilter.tint(Palette.Text),
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Small status dot. */
@Composable
fun StatusDot(color: Color, size: Int = 9) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/** A number followed by a chevron, used as the trailing slot on folder rows. */
@Composable
fun CountChevron(count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = Palette.TextDim,
        )
        Icon(
            imageVector = com.termfold.app.ui.theme.TermFoldIcons.ChevronRight,
            contentDescription = null,
            tint = Palette.TextFaint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Circular icon button; [primary] renders the filled accent variant. */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    size: Int = 44,
) {
    val background = if (primary) Palette.Accent else Palette.Field
    val tint = if (primary) Palette.OnAccent else Palette.Text

    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(if (primary) 22.dp else 21.dp),
        )
    }
}

/** Borderless icon button for the top app bar. */
@Composable
fun BareIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Palette.Text,
    size: Int = 40,
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Single-line section title, deliberately without supporting copy. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = Palette.Text,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Thin top-row label used above grouped settings entries. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextFaint,
        fontWeight = FontWeight.Medium,
        modifier = modifier.padding(start = 6.dp, bottom = 8.dp, top = 6.dp),
    )
}

/** Vertical spacer matching row rhythm. */
@Composable
fun RowSpacer() {
    Spacer(Modifier.height(RowSpec.Gap))
}


/** The TermFold logo mark (the folded folder), used wherever the app shows its own brand. */
@Composable
fun BrandMark(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, alpha: Float = 1f) {
    Image(
        painter = androidx.compose.ui.res.painterResource(com.termfold.app.R.drawable.brand_mark),
        contentDescription = null,
        alpha = alpha,
        modifier = modifier.size(size),
    )
}
