package com.obsidiancompanion.core.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 图标库 —— 原型内联 SVG 的 ImageVector 移植（24×24 viewBox / stroke 1.8 / round cap & join）。
 * 原型中的 <circle>/<rect> 已转换为等价 arc/路径字符串。
 * stroke 色烘焙为黑，由 Icon(tint=…) 统一着色。
 */

private const val STROKE_W = 1.8f

private fun strokeIcon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        paths.forEach { d ->
            addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE_W,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

private fun fillIcon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        paths.forEach { d ->
            addPath(
                pathData = addPathNodes(d),
                fill = SolidColor(Color.Black),
            )
        }
    }.build()

object AppIcons {
    val Search by lazy { strokeIcon("search", "M4 11A7 7 0 1 0 18 11A7 7 0 1 0 4 11", "M16.5 16.5L21 21") }
    val Home by lazy { strokeIcon("home", "M3 10.5L12 3L21 10.5", "M5.5 9.5V20A1 1 0 0 0 6.5 21H17.5A1 1 0 0 0 18.5 20V9.5") }
    val Folder by lazy { strokeIcon("folder", "M3.5 7A1.5 1.5 0 0 1 5 5.5H8.6A1.5 1.5 0 0 1 9.8 6.1L11.3 8H19A1.5 1.5 0 0 1 20.5 9.5V17A1.5 1.5 0 0 1 19 18.5H5A1.5 1.5 0 0 1 3.5 17Z") }
    val FileText by lazy { strokeIcon("filetxt", "M6 3.5H13L18.5 9V20A0.5 0.5 0 0 0 19 20.5H6A0.5 0.5 0 0 1 5.5 20V4A0.5 0.5 0 0 1 6 3.5Z", "M13 3.5V9H18.5", "M9 13H15M9 16.5H15") }
    val Image by lazy { strokeIcon("img", "M5.5 5H18.5A2 2 0 0 1 20.5 7V17A2 2 0 0 1 18.5 19H5.5A2 2 0 0 1 3.5 17V7A2 2 0 0 1 5.5 5Z", "M7.5 10A1.5 1.5 0 1 0 10.5 10A1.5 1.5 0 1 0 7.5 10", "M5.5 17L9.5 13L12.5 16L15 13.5L19 16.5") }
    val Pdf by lazy { strokeIcon("pdf", "M6 3.5H13L18.5 9V20A0.5 0.5 0 0 0 19 20.5H6A0.5 0.5 0 0 1 5.5 20V4A0.5 0.5 0 0 1 6 3.5Z", "M13 3.5V9H18.5", "M9 15.5H14") }
    val Canvas by lazy { strokeIcon("canvas", "M5.5 4H9.5A1.5 1.5 0 0 1 11 5.5V9.5A1.5 1.5 0 0 1 9.5 11H5.5A1.5 1.5 0 0 1 4 9.5V5.5A1.5 1.5 0 0 1 5.5 4Z", "M14.5 4H18.5A1.5 1.5 0 0 1 20 5.5V9.5A1.5 1.5 0 0 1 18.5 11H14.5A1.5 1.5 0 0 1 13 9.5V5.5A1.5 1.5 0 0 1 14.5 4Z", "M5.5 13H9.5A1.5 1.5 0 0 1 11 14.5V18.5A1.5 1.5 0 0 1 9.5 20H5.5A1.5 1.5 0 0 1 4 18.5V14.5A1.5 1.5 0 0 1 5.5 13Z", "M16.5 13.5V19.5M13.5 16.5H19.5") }
    val ChevronRight by lazy { strokeIcon("chev", "M9.5 6L15.5 12L9.5 18") }
    val Back by lazy { strokeIcon("back", "M15 5L8 12L15 19") }
    val More by lazy { strokeIcon("more", "M3.7 12A1.3 1.3 0 1 0 6.3 12A1.3 1.3 0 1 0 3.7 12", "M10.7 12A1.3 1.3 0 1 0 13.3 12A1.3 1.3 0 1 0 10.7 12", "M17.7 12A1.3 1.3 0 1 0 20.3 12A1.3 1.3 0 1 0 17.7 12") }
    val Star by lazy { strokeIcon("star", "M12 3.8L14.5 8.8L20 9.6L16 13.5L16.95 19L12 16.4L7.05 19L8 13.5L4 9.6L9.5 8.8Z") }
    val StarFilled by lazy { fillIcon("starf", "M12 3.8L14.5 8.8L20 9.6L16 13.5L16.95 19L12 16.4L7.05 19L8 13.5L4 9.6L9.5 8.8Z") }
    val Check by lazy { strokeIcon("check", "M5 12.5L9.5 17L19 7.5") }
    val CheckCircle by lazy { strokeIcon("checkc", "M3.5 12A8.5 8.5 0 1 0 20.5 12A8.5 8.5 0 1 0 3.5 12", "M8.5 12.3L10.9 14.7L15.5 9.8") }
    val Refresh by lazy { strokeIcon("refresh", "M20 12A8 8 0 1 1 17.66 6.34", "M20 4V8.3H15.7") }
    val CloudOff by lazy { strokeIcon("cloudoff", "M17.5 18H7A3.5 3.5 0 0 1 6.6 11A5.5 5.5 0 0 1 15.6 8.6", "M3 3L21 21") }
    val Alert by lazy { strokeIcon("alert", "M12 4L21 19.5H3Z", "M12 10V14", "M11.5 16.8A0.5 0.5 0 1 0 12.5 16.8A0.5 0.5 0 1 0 11.5 16.8") }
    val Close by lazy { strokeIcon("x", "M6 6L18 18", "M18 6L6 18") }
    val Pencil by lazy { strokeIcon("pencil", "M4 20H8L19.5 8.5A2.12 2.12 0 0 0 16.5 5.5L5 17Z", "M13.5 6.5L16.5 9.5") }
    val Info by lazy { strokeIcon("info", "M3.5 12A8.5 8.5 0 1 0 20.5 12A8.5 8.5 0 1 0 3.5 12", "M12 11V15", "M11.5 8A0.5 0.5 0 1 0 12.5 8A0.5 0.5 0 1 0 11.5 8") }
    val Copy by lazy { strokeIcon("copy", "M11 9H18A2 2 0 0 1 20 11V18A2 2 0 0 1 18 20H11A2 2 0 0 1 9 18V11A2 2 0 0 1 11 9Z", "M5 15H4A1.5 1.5 0 0 1 2.5 13.5V4.5A1.5 1.5 0 0 1 4 3H13A1.5 1.5 0 0 1 14.5 4.5V5") }
    val ExternalLink by lazy { strokeIcon("extlink", "M7 17L17 7", "M9 7H17V15") }
    val Sliders by lazy { strokeIcon("sliders", "M4 7H13M17 7H20M4 17H7M11 17H20", "M13 7A2 2 0 1 0 17 7A2 2 0 1 0 13 7", "M7 17A2 2 0 1 0 11 17A2 2 0 1 0 7 17") }
    val Shield by lazy { strokeIcon("shield", "M12 3.5L5 6V11C5 15.5 8 18.8 12 20.5C16 18.8 19 15.5 19 11V6Z") }
    val Book by lazy { strokeIcon("book", "M5 5.5C7 4 10 4 12 5.5C14 4 17 4 19 5.5V18.5C17 17 14 17 12 18.5C10 17 7 17 5 18.5Z", "M12 5.5V18.5") }
    val List by lazy { strokeIcon("list", "M8 6H20M8 12H20M8 18H20", "M3 6A1 1 0 1 0 5 6A1 1 0 1 0 3 6", "M3 12A1 1 0 1 0 5 12A1 1 0 1 0 3 12", "M3 18A1 1 0 1 0 5 18A1 1 0 1 0 3 18") }
    val Task by lazy { strokeIcon("task", "M8 4H16A4 4 0 0 1 20 8V16A4 4 0 0 1 16 20H8A4 4 0 0 1 4 16V8A4 4 0 0 1 8 4Z", "M8.5 12L10.8 14.3L15.5 9.5") }
    val Wifi by lazy { strokeIcon("wifi", "M5 9.5A10 10 0 0 1 19 9.5", "M8 12.5A6 6 0 0 1 16 12.5", "M11 15.5A1 1 0 1 0 13 15.5A1 1 0 1 0 11 15.5") }
    val Cell by lazy { strokeIcon("cell", "M4 19V9M9 19V5M14 19V10M19 19V13") }
    val Battery by lazy { strokeIcon("batt", "M5 8H17A2 2 0 0 1 19 10V14A2 2 0 0 1 17 16H5A2 2 0 0 1 3 14V10A2 2 0 0 1 5 8Z", "M21.5 11V13") }
    val Add by lazy { strokeIcon("add", "M12 5.5V18.5M5.5 12H18.5") }
    val Share by lazy { strokeIcon("share", "M15.5 5A2.5 2.5 0 1 0 20.5 5A2.5 2.5 0 1 0 15.5 5", "M3.5 12A2.5 2.5 0 1 0 8.5 12A2.5 2.5 0 1 0 3.5 12", "M15.5 19A2.5 2.5 0 1 0 20.5 19A2.5 2.5 0 1 0 15.5 19", "M15.4 6.5L8.6 10.5", "M8.6 13.5L15.4 17.5") }
    val Eye by lazy { strokeIcon("eye", "M2.5 12C4.5 7.6 8 5.5 12 5.5C16 5.5 19.5 7.6 21.5 12C19.5 16.4 16 18.5 12 18.5C8 18.5 4.5 16.4 2.5 12Z", "M9.7 12A2.3 2.3 0 1 0 14.3 12A2.3 2.3 0 1 0 9.7 12") }
}
