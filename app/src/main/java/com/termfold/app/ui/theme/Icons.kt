package com.termfold.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Hand-built icons, drawn on a 24x24 grid.
 *
 * Outlined glyphs are stroked rather than filled so a single path set reads correctly at both the
 * 18dp nav size and the 22dp row size without pixel-snapping artifacts.
 */
object TermFoldIcons {

    private const val STROKE = 1.75f

    /** The `>_` mark used for the logo and every terminal surface. */
    val Terminal: ImageVector by lazy {
        ImageVector.Builder("Terminal", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes("M5.2,8.4 L9.4,12.5 L5.2,16.6"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .addPath(
                pathData = addPathNodes("M12.2,16.8 L17.6,16.8"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
            )
            .build()
    }

    /** Outlined folder, used where a stroked icon matches its neighbours. */
    val FolderOutline: ImageVector by lazy {
        ImageVector.Builder("FolderOutline", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes(
                    "M4.1,7.4 C4.1,6.02 5.22,4.9 6.6,4.9 H9.0 C9.7,4.9 10.36,5.2 10.82,5.72 " +
                        "L11.9,6.95 H17.4 C18.78,6.95 19.9,8.07 19.9,9.45 V16.1 " +
                        "C19.9,17.48 18.78,18.6 17.4,18.6 H6.6 C5.22,18.6 4.1,17.48 4.1,16.1 Z"
                ),
                stroke = SolidColor(Color.White),
                strokeLineWidth = STROKE,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
    }

    /** Solid folder with a tab, matching the filled folder in the design. */
    val Folder: ImageVector by lazy {
        ImageVector.Builder("Folder", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes(
                    "M4.1,7.4 C4.1,6.02 5.22,4.9 6.6,4.9 H9.0 C9.7,4.9 10.36,5.2 10.82,5.72 " +
                        "L11.9,6.95 H17.4 C18.78,6.95 19.9,8.07 19.9,9.45 V16.1 " +
                        "C19.9,17.48 18.78,18.6 17.4,18.6 H6.6 C5.22,18.6 4.1,17.48 4.1,16.1 Z"
                ),
                fill = SolidColor(Color.White),
            )
            .build()
    }

    val Home: ImageVector by lazy {
        ImageVector.Builder("Home", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes(
                    "M3.6,10.9 C3.6,10.36 3.83,9.85 4.23,9.5 L10.6,3.86 " +
                        "C11.4,3.15 12.6,3.15 13.4,3.86 L19.77,9.5 " +
                        "C20.17,9.85 20.4,10.36 20.4,10.9 V18.4 " +
                        "C20.4,19.23 19.73,19.9 18.9,19.9 H14.9 V14.6 " +
                        "C14.9,14.05 14.45,13.6 13.9,13.6 H10.1 " +
                        "C9.55,13.6 9.1,14.05 9.1,14.6 V19.9 H5.1 " +
                        "C4.27,19.9 3.6,19.23 3.6,18.4 Z"
                ),
                stroke = SolidColor(Color.White),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
    }

    /**
     * Cog. Proportions matter more than detail here: a large ring with short, thick teeth reads
     * as a gear, whereas a small ring with long thin teeth reads as a sun at nav-bar size.
     */
    val Gear: ImageVector by lazy {
        val builder = ImageVector.Builder("Gear", 24.dp, 24.dp, 24f, 24f)
        val ringRadius = 6.3f
        val toothStroke = 2.4f

        builder.addPath(
            pathData = addPathNodes(
                "M${12f - ringRadius},12 " +
                    "A$ringRadius,$ringRadius 0 1,1 ${12f + ringRadius},12 " +
                    "A$ringRadius,$ringRadius 0 1,1 ${12f - ringRadius},12 Z"
            ),
            stroke = SolidColor(Color.White),
            strokeLineWidth = STROKE,
        )

        // Teeth overlap the ring edge so they merge into a single silhouette.
        val inner = ringRadius - 0.6f
        val outer = 9.4f
        listOf(0, 45, 90, 135, 180, 225, 270, 315).forEach { degrees ->
            val radians = Math.toRadians(degrees.toDouble())
            val cos = kotlin.math.cos(radians).toFloat()
            val sin = kotlin.math.sin(radians).toFloat()
            builder.addPath(
                pathData = addPathNodes(
                    "M${12f + inner * cos},${12f + inner * sin} " +
                        "L${12f + outer * cos},${12f + outer * sin}"
                ),
                stroke = SolidColor(Color.White),
                strokeLineWidth = toothStroke,
                strokeLineCap = StrokeCap.Round,
            )
        }

        builder.build()
    }

    val Search: ImageVector by lazy {
        ImageVector.Builder("Search", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes(
                    "M10.8,4.2 A6.6,6.6 0 1,0 10.8,17.4 A6.6,6.6 0 1,0 10.8,4.2 Z"
                ),
                stroke = SolidColor(Color.White),
                strokeLineWidth = STROKE + 0.15f,
            )
            .addPath(
                pathData = addPathNodes("M15.7,15.7 L20.2,20.2"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = STROKE + 0.15f,
                strokeLineCap = StrokeCap.Round,
            )
            .build()
    }

    val More: ImageVector by lazy {
        ImageVector.Builder("More", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes(
                    "M12,5.6 A1.5,1.5 0 1,0 12,8.6 A1.5,1.5 0 1,0 12,5.6 Z"
                ),
                fill = SolidColor(Color.White),
            )
            .addPath(
                pathData = addPathNodes(
                    "M12,10.5 A1.5,1.5 0 1,0 12,13.5 A1.5,1.5 0 1,0 12,10.5 Z"
                ),
                fill = SolidColor(Color.White),
            )
            .addPath(
                pathData = addPathNodes(
                    "M12,15.4 A1.5,1.5 0 1,0 12,18.4 A1.5,1.5 0 1,0 12,15.4 Z"
                ),
                fill = SolidColor(Color.White),
            )
            .build()
    }

    val Back: ImageVector by lazy {
        ImageVector.Builder("Back", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes("M14.6,5.6 L8.2,12 L14.6,18.4"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
    }

    val Plus: ImageVector by lazy {
        ImageVector.Builder("Plus", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes("M12,5.6 L12,18.4"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
            )
            .addPath(
                pathData = addPathNodes("M5.6,12 L18.4,12"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
            )
            .build()
    }

    val ChevronRight: ImageVector by lazy {
        ImageVector.Builder("ChevronRight", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes("M9.8,6.4 L15.4,12 L9.8,17.6"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
    }

    val ChevronDown: ImageVector by lazy {
        ImageVector.Builder("ChevronDown", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes("M6.4,9.8 L12,15.4 L17.6,9.8"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
    }

    val Send: ImageVector by lazy {
        ImageVector.Builder("Send", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes("M5.4,12 L18.2,12"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
            )
            .addPath(
                pathData = addPathNodes("M13.2,6.8 L18.4,12 L13.2,17.2"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
    }
}
