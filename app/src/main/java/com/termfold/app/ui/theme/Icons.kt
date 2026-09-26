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

    val ChevronUp: ImageVector by lazy { stroked("ChevronUp", "M6.4,14.2 L12,8.6 L17.6,14.2") }

    /** A page with a plus: new file. */
    val FilePlus: ImageVector by lazy {
        stroked(
            "FilePlus",
            "M14,3.8 H7.2 C6.1,3.8 5.2,4.7 5.2,5.8 V18.2 C5.2,19.3 6.1,20.2 7.2,20.2 H16.8 C17.9,20.2 18.8,19.3 18.8,18.2 V8.6 Z",
            "M14,3.8 V8.6 H18.8",
            "M12,11.4 V16.6",
            "M9.4,14 H14.6",
        )
    }

    /** A folder with a plus: new folder. */
    val FolderPlus: ImageVector by lazy {
        stroked(
            "FolderPlus",
            "M3.8,6.6 C3.8,5.6 4.6,4.8 5.6,4.8 H9 L10.8,6.8 H18.4 C19.4,6.8 20.2,7.6 20.2,8.6 V17.4 " +
                "C20.2,18.4 19.4,19.2 18.4,19.2 H5.6 C4.6,19.2 3.8,18.4 3.8,17.4 Z",
            "M12,10.2 V15.8",
            "M9.2,13 H14.8",
        )
    }

    /** An arrow into a folder: move. */
    val MoveTo: ImageVector by lazy {
        stroked(
            "MoveTo",
            "M3.8,6.6 C3.8,5.6 4.6,4.8 5.6,4.8 H9 L10.8,6.8 H18.4 C19.4,6.8 20.2,7.6 20.2,8.6 V17.4 " +
                "C20.2,18.4 19.4,19.2 18.4,19.2 H5.6 C4.6,19.2 3.8,18.4 3.8,17.4 Z",
            "M8.4,13 H15.2",
            "M12.8,10.6 L15.2,13 L12.8,15.4",
        )
    }

    /** An arrow up out of a tray: upload into the current folder. */
    val Upload: ImageVector by lazy {
        stroked("Upload", "M12,15.5 V4.5", "M7.8,8.7 L12,4.5 L16.2,8.7", "M4.8,14.5 V18 C4.8,18.8 5.4,19.5 6.3,19.5 H17.7 C18.6,19.5 19.2,18.8 19.2,18 V14.5")
    }

    /** A tablet with an arrow coming down onto it: save a copy to the device. */
    val SaveToDevice: ImageVector by lazy {
        stroked(
            "SaveToDevice",
            "M7.5,3.8 H16.5 C17.3,3.8 18,4.5 18,5.3 V18.7 C18,19.5 17.3,20.2 16.5,20.2 H7.5 C6.7,20.2 6,19.5 6,18.7 V5.3 C6,4.5 6.7,3.8 7.5,3.8 Z",
            "M12,7.5 V14.2",
            "M9.4,11.8 L12,14.4 L14.6,11.8",
            "M10.8,17.4 H13.2",
        )
    }

    private const val FOLDER_OUTLINE =
        "M3.8,6.6 C3.8,5.6 4.6,4.8 5.6,4.8 H9 L10.8,6.8 H18.4 C19.4,6.8 20.2,7.6 20.2,8.6 V17.4 " +
            "C20.2,18.4 19.4,19.2 18.4,19.2 H5.6 C4.6,19.2 3.8,18.4 3.8,17.4 Z"

    /** A folder with an arrow leaving it: export to a folder on the device. */
    val FolderExport: ImageVector by lazy {
        stroked("FolderExport", FOLDER_OUTLINE, "M9.2,14.8 L14.6,9.4", "M11.2,9.4 H14.6 V12.8")
    }

    /** A folder with an arrow going in: import a folder from the device. */
    val FolderImport: ImageVector by lazy {
        stroked("FolderImport", FOLDER_OUTLINE, "M14.6,9.4 L9.2,14.8", "M9.2,11.4 V14.8 H12.6")
    }

    /** A box with an arrow out of its corner: open in another app. */
    val OpenExternal: ImageVector by lazy {
        stroked(
            "OpenExternal",
            "M11,5.5 H6.8 C6,5.5 5.4,6.1 5.4,6.9 V17.2 C5.4,18 6,18.6 6.8,18.6 H17.1 C17.9,18.6 18.5,18 18.5,17.2 V13",
            "M13.8,4.8 H19.2 V10.2",
            "M19.2,4.8 L11.4,12.6",
        )
    }

    /** Two stacked pictures: copy an image to the clipboard. */
    val ImageCopy: ImageVector by lazy {
        stroked(
            "ImageCopy",
            "M8.2,7.6 H18.2 C19,7.6 19.6,8.2 19.6,9 V18.2 C19.6,19 19,19.6 18.2,19.6 H8.2 C7.4,19.6 6.8,19 6.8,18.2 V9 C6.8,8.2 7.4,7.6 8.2,7.6 Z",
            "M4.4,15.8 V5.8 C4.4,5 5,4.4 5.8,4.4 H15.8",
            "M7,17.4 L10.8,13.4 L13.4,16 L15.2,14.2 L19.4,18.2",
            "M15.6,10.4 A1,1 0 1,1 15.59,10.4",
        )
    }

    /** Magnifier with a plus: zoom. */
    val ZoomIn: ImageVector by lazy {
        stroked("ZoomIn", "M10.8,4.6 A6.2,6.2 0 1,1 10.79,4.6", "M15.2,15.2 L19.6,19.6", "M10.8,8.2 V13.4", "M8.2,10.8 H13.4")
    }

    /** Lines of decreasing length: sort. */
    val Sort: ImageVector by lazy {
        stroked("Sort", "M4.8,7 H19.2", "M4.8,12 H14.8", "M4.8,17 H10.4")
    }

    /** A grid of four squares: grid view. */
    val Grid: ImageVector by lazy {
        stroked(
            "Grid",
            "M5,5 H10.4 V10.4 H5 Z", "M13.6,5 H19 V10.4 H13.6 Z",
            "M5,13.6 H10.4 V19 H5 Z", "M13.6,13.6 H19 V19 H13.6 Z",
        )
    }

    /** Stacked rows: list view. */
    val ListView: ImageVector by lazy {
        stroked("ListView", "M8.6,6.5 H19.4", "M8.6,12 H19.4", "M8.6,17.5 H19.4", "M4.8,6.5 H5.2", "M4.8,12 H5.2", "M4.8,17.5 H5.2")
    }

    /** Three joined dots: share. */
    val Share: ImageVector by lazy {
        stroked(
            "Share",
            "M17.5,4.6 A2.4,2.4 0 1,1 17.49,4.6",
            "M6.5,9.6 A2.4,2.4 0 1,1 6.49,9.6",
            "M17.5,14.6 A2.4,2.4 0 1,1 17.49,14.6",
            "M8.6,10.9 L15.4,7.1",
            "M8.6,13.1 L15.4,16.9",
        )
    }

    /** A clipboard: paste. */
    val Paste: ImageVector by lazy {
        stroked(
            "Paste",
            "M9,4.5 H15 V7 H9 Z",
            "M9,5.8 H7 C6.2,5.8 5.5,6.5 5.5,7.3 V18.5 C5.5,19.3 6.2,20 7,20 H17 C17.8,20 18.5,19.3 18.5,18.5 V7.3 C18.5,6.5 17.8,5.8 17,5.8 H15",
            "M9,12 H15",
            "M9,15.5 H13",
        )
    }

    /** An open eye: hidden files shown. */
    val Eye: ImageVector by lazy {
        stroked("Eye", "M2.8,12 C5,7.8 8.3,5.8 12,5.8 C15.7,5.8 19,7.8 21.2,12 C19,16.2 15.7,18.2 12,18.2 C8.3,18.2 5,16.2 2.8,12 Z", "M12,9.3 A2.7,2.7 0 1,1 11.99,9.3")
    }

    /** A struck-through eye: hidden files hidden. */
    val EyeOff: ImageVector by lazy {
        stroked("EyeOff", "M2.8,12 C5,7.8 8.3,5.8 12,5.8 C15.7,5.8 19,7.8 21.2,12 C19,16.2 15.7,18.2 12,18.2 C8.3,18.2 5,16.2 2.8,12 Z", "M4.5,4.5 L19.5,19.5")
    }

    /** Two branches joining: a git repository. */
    val GitBranch: ImageVector by lazy {
        stroked(
            "GitBranch",
            "M7,4.2 A2,2 0 1,1 6.99,4.2", "M7,15.8 A2,2 0 1,1 6.99,15.8", "M17,6.2 A2,2 0 1,1 16.99,6.2",
            "M7,8.2 V15.8", "M17,10.2 C17,13.4 13.4,13.6 7,15.6",
        )
    }
}
