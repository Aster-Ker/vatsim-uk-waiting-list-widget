package io.github.asterker.waitinglist.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Line icons from Lucide (https://lucide.dev, ISC licence), matching PanelUI's line style.
object PanelIcons {
    val Calendar = lineIcon(
        "M 8 2 v 4",
        "M 16 2 v 4",
        "M 5 4 h 14 a 2 2 0 0 1 2 2 v 14 a 2 2 0 0 1 -2 2 H 5 a 2 2 0 0 1 -2 -2 V 6 a 2 2 0 0 1 2 -2 z",
        "M 3 10 h 18",
    )
    val ListOrdered = lineIcon(
        "M 10 12 h 11",
        "M 10 18 h 11",
        "M 10 6 h 11",
        "M 4 10 h 2",
        "M 4 6 h 1 v 4",
        "M 6 18 H 4 c 0 -1 2 -2 2 -3 s -1 -1.5 -2 -1",
    )
    val Settings = lineIcon(
        "M 12.22 2 h -.44 a 2 2 0 0 0 -2 2 v .18 a 2 2 0 0 1 -1 1.73 l -.43 .25 a 2 2 0 0 1 -2 0 l -.15 -.08 a 2 2 0 0 0 -2.73 .73 l -.22 .38 a 2 2 0 0 0 .73 2.73 l .15 .1 a 2 2 0 0 1 1 1.72 v .51 a 2 2 0 0 1 -1 1.74 l -.15 .09 a 2 2 0 0 0 -.73 2.73 l .22 .38 a 2 2 0 0 0 2.73 .73 l .15 -.08 a 2 2 0 0 1 2 0 l .43 .25 a 2 2 0 0 1 1 1.73 V 20 a 2 2 0 0 0 2 2 h .44 a 2 2 0 0 0 2 -2 v -.18 a 2 2 0 0 1 1 -1.73 l .43 -.25 a 2 2 0 0 1 2 0 l .15 .08 a 2 2 0 0 0 2.73 -.73 l .22 -.39 a 2 2 0 0 0 -.73 -2.73 l -.15 -.08 a 2 2 0 0 1 -1 -1.74 v -.5 a 2 2 0 0 1 1 -1.74 l .15 -.09 a 2 2 0 0 0 .73 -2.73 l -.22 -.38 a 2 2 0 0 0 -2.73 -.73 l -.15 .08 a 2 2 0 0 1 -2 0 l -.43 -.25 a 2 2 0 0 1 -1 -1.73 V 4 a 2 2 0 0 0 -2 -2 z",
        "M 12 9 a 3 3 0 1 0 0 6 a 3 3 0 1 0 0 -6 z",
    )
}

// Drawn as 2px strokes on a 24px grid, like Lucide; Icon's tint recolours the strokes.
private fun lineIcon(vararg paths: String): ImageVector {
    val builder = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    for (path in paths) {
        builder.addPath(
            pathData = addPathNodes(path),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return builder.build()
}
