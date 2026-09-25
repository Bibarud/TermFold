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

    /** One stroked glyph from path strings, in the set's usual weight and joins. */
    private fun stroked(name: String, vararg paths: String, width: Float = STROKE): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach { path ->
            builder.addPath(
                pathData = addPathNodes(path),
                stroke = SolidColor(Color.White),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }

    /** A picture: frame, sun, and a hill. Attach-image and image-key affordances. */
    val Image: ImageVector by lazy {
        stroked(
            "Image",
            "M5.5,5 H18.5 C19.33,5 20,5.67 20,6.5 V17.5 C20,18.33 19.33,19 18.5,19 H5.5 " +
                "C4.67,19 4,18.33 4,17.5 V6.5 C4,5.67 4.67,5 5.5,5 Z",
            "M9,10.2 m-1.5,0 a1.5,1.5 0 1,0 3,0 a1.5,1.5 0 1,0 -3,0",
            "M4.4,16.4 L9.2,12.6 L12.6,15.4 L15.2,13.2 L19.6,16.8",
        )
    }

    /** A page with a folded corner and two text lines: a pasted-text attachment. */
    val FileText: ImageVector by lazy {
        stroked(
            "FileText",
            "M7,4 H13.6 L18,8.4 V18.5 C18,19.33 17.33,20 16.5,20 H7 C6.17,20 5.5,19.33 5.5,18.5 " +
                "V5.5 C5.5,4.67 6.17,4 7,4 Z",
            "M13.4,4.2 V8.6 H17.8",
            "M8.6,12.6 H14.9",
            "M8.6,15.8 H13",
        )
    }

    /** Rounded square: stop the running turn. */
    val Stop: ImageVector by lazy {
        stroked(
            "Stop",
            "M8.6,7.4 H15.4 C16.06,7.4 16.6,7.94 16.6,8.6 V15.4 C16.6,16.06 16.06,16.6 15.4,16.6 " +
                "H8.6 C7.94,16.6 7.4,16.06 7.4,15.4 V8.6 C7.4,7.94 7.94,7.4 8.6,7.4 Z",
            width = 2f,
        )
    }

    /** Two offset sheets: copy to clipboard. */
    val Copy: ImageVector by lazy {
        stroked(
            "Copy",
            "M9.6,8.6 H17 C17.77,8.6 18.4,9.23 18.4,10 V18 C18.4,18.77 17.77,19.4 17,19.4 H9.6 " +
                "C8.83,19.4 8.2,18.77 8.2,18 V10 C8.2,9.23 8.83,8.6 9.6,8.6 Z",
            "M15.6,5.8 V5.6 C15.6,4.83 14.97,4.2 14.2,4.2 H7 C6.23,4.2 5.6,4.83 5.6,5.6 V13.6 " +
                "C5.6,14.37 6.23,15 7,15 H7.4",
        )
    }

    /** An open circle with an arrowhead: restart / retry. */
    val Refresh: ImageVector by lazy {
        stroked(
            "Refresh",
            "M18.6,12 A6.6,6.6 0 1,1 16.4,7.1",
            "M16.9,3.9 L16.9,7.5 L13.3,7.5",
        )
    }

    /** A cross: remove an attachment, dismiss. */
    val Close: ImageVector by lazy {
        stroked("Close", "M7,7 L17,17", "M17,7 L7,17", width = 2f)
    }

    /** A tick: done. */
    val Check: ImageVector by lazy {
        stroked("Check", "M5.6,12.6 L10,16.8 L18.4,7.6", width = 2f)
    }

    /** A pencil: file edits in tool calls. */
    val Pencil: ImageVector by lazy {
        stroked(
            "Pencil",
            "M14.6,5.6 L18.4,9.4 L9.2,18.6 L5,19.4 L5.8,15.2 Z",
            "M12.8,7.4 L16.6,11.2",
        )
    }

    /** Three sliders: session settings (model, reasoning level, mode). */
    val Sliders: ImageVector by lazy {
        stroked(
            "Sliders",
            "M4.6,7 H19.4", "M4.6,12 H19.4", "M4.6,17 H19.4",
            "M9,5.2 V8.8", "M15.4,10.2 V13.8", "M7.4,15.2 V18.8",
            width = 1.8f,
        )
    }

    /** A clock inside a turning arrow: earlier sessions. */
    val History: ImageVector by lazy {
        stroked(
            "History",
            "M4.6,12 A7.4,7.4 0 1,0 6.8,6.8",
            "M4.2,4.6 L4.4,7.6 L7.4,7.4",
            "M12,8.2 L12,12.3 L14.8,14",
        )
    }

    /** A folder with a branch: the project's file tree. */
    val Files: ImageVector by lazy {
        stroked(
            "Files",
            "M3.8,6.6 C3.8,5.6 4.6,4.8 5.6,4.8 H9 L10.8,6.8 H18.4 C19.4,6.8 20.2,7.6 20.2,8.6 V17.4 " +
                "C20.2,18.4 19.4,19.2 18.4,19.2 H5.6 C4.6,19.2 3.8,18.4 3.8,17.4 Z",
            "M8,11 V15.4 H12.4",
            "M8,13 H12.4",
        )
    }

    /** A disk: save. */
    val Save: ImageVector by lazy {
        stroked(
            "Save",
            "M5.6,4.4 H15.6 L19.6,8.4 V18.4 C19.6,19.08 19.08,19.6 18.4,19.6 H5.6 C4.92,19.6 4.4,19.08 4.4,18.4 V5.6 " +
                "C4.4,4.92 4.92,4.4 5.6,4.4 Z",
            "M8,4.6 V8.6 H14.4 V4.6",
            "M8,19.4 V14 H16 V19.4",
        )
    }

    /** Lines folding back: soft wrap. */
    val Wrap: ImageVector by lazy {
        stroked(
            "Wrap",
            "M4.5,6.5 H19.5",
            "M4.5,12 H16.5 C18.2,12 19.5,13.3 19.5,15 C19.5,16.7 18.2,18 16.5,18 H12.5",
            "M14.3,16 L12.3,18 L14.3,20",
            "M4.5,18 H8.5",
        )
    }

    /** An arrow curling back: undo. */
    val Undo: ImageVector by lazy {
        stroked(
            "Undo",
            "M8.5,5.5 L4.5,9.5 L8.5,13.5",
            "M4.5,9.5 H14.5 C17.3,9.5 19.5,11.7 19.5,14.5 C19.5,17.3 17.3,19.5 14.5,19.5 H10.5",
        )
    }

    /** An arrow curling forward: redo. */
    val Redo: ImageVector by lazy {
        stroked(
            "Redo",
            "M15.5,5.5 L19.5,9.5 L15.5,13.5",
            "M19.5,9.5 H9.5 C6.7,9.5 4.5,11.7 4.5,14.5 C4.5,17.3 6.7,19.5 9.5,19.5 H13.5",
        )
    }

    /** A bin: delete. */
    val Trash: ImageVector by lazy {
        stroked(
            "Trash",
            "M4.5,7 H19.5",
            "M9.5,7 V5.2 C9.5,4.8 9.8,4.5 10.2,4.5 H13.8 C14.2,4.5 14.5,4.8 14.5,5.2 V7",
            "M6.5,7 L7.3,18.6 C7.4,19.2 7.9,19.5 8.5,19.5 H15.5 C16.1,19.5 16.6,19.2 16.7,18.6 L17.5,7",
            "M10.3,10.5 V16",
            "M13.7,10.5 V16",
        )
    }
}
