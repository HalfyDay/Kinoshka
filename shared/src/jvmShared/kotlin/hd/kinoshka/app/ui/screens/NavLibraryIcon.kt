package hd.kinoshka.app.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Иконка Библиотеки — книги на полке: тот же контур 24x24, что у кастомных
 * drawable пилюли (ic_nav_library_*). Единая для всех мест вместо
 * material-списка: фолбэк пилюли, строки настроек, desktop-меню.
 * Красится тинтом [Icon], внутри — белый штрих как в drawable.
 */
val LibraryShelfIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "LibraryShelf",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // Книги и полка — штрих 1.6/1.5, корешки — 0.8 (как в drawable).
        shelfPath("M5,18 L5,5 C5,4.5 5.3,4 6,4 L8,4 C8.7,4 9,4.5 9,5 L9,18", 1.6f)
        shelfPath("M10.5,18 L10.5,3 C10.5,2.5 10.8,2 11.5,2 L13.5,2 C14.2,2 14.5,2.5 14.5,3 L14.5,18", 1.6f)
        shelfPath("M16,18 L16,6 C16,5.5 16.3,5 17,5 L18.5,5 C19.2,5 19.5,5.5 19.5,6 L19.5,18", 1.6f)
        shelfPath("M3.5,18.5 L20.5,18.5", 1.5f)
        shelfPath("M6.5,7 L8,7 M6.5,9.5 L8,9.5", 0.8f, StrokeCap.Butt)
        shelfPath("M12,5.5 L13.5,5.5 M12,8 L13.5,8", 0.8f, StrokeCap.Butt)
        shelfPath("M17.5,8 L19,8 M17.5,10.5 L19,10.5", 0.8f, StrokeCap.Butt)
    }.build()
}

private fun ImageVector.Builder.shelfPath(
    data: String,
    strokeWidth: Float,
    cap: StrokeCap = StrokeCap.Round
) {
    addPath(
        pathData = PathParser().parsePathString(data).toNodes(),
        fill = null,
        stroke = SolidColor(Color.White),
        strokeLineWidth = strokeWidth,
        strokeLineCap = cap,
        strokeLineJoin = StrokeJoin.Round
    )
}
