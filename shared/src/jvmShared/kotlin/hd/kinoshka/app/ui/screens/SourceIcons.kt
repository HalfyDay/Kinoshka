package hd.kinoshka.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hd.kinoshka.app.data.source.PlaybackSources

/**
 * Фирменные иконки источников для страницы «Источники» (общие для Android и desktop).
 *
 * Аниме-источники — порты drawable из приложения (ic_src_kodik/aniliberty/animelib/
 * anixart + PNG shikimori/anistar). Остальные — по реальным логотипам/фавиконам
 * с официальных сайтов: Alloha (allohatv.com — синий шеврон + cyan play),
 * Oppai.Stream (apple-touch-icon — смайл), AllHentai (favicon — неко-девочка,
 * здесь: ушки + A), HentaiZ (apple-icon — оранжевая Z на чёрном),
 * HentaiDream (тёмный фон + розовый HD-монограмм — явный favicon в приложение
 * не тянем), Hanime1.me (чёрно-красный бренд), Voidboost (бэкенд Rezka —
 * чёрный + красный, как HD-фавикон), VideoCDN/Collaps/Turbo/Veoveo (ddbb-эмбеды
 * без публичных лого — бренд-цвета + символы плеера/скорости), Smarthard
 * (архив shikicinema — тёмный + розовый акцент).
 */
@Composable
fun SourceBrandIcon(sourceId: String, size: Dp = 44.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        when (PlaybackSources.canonical(sourceId)) {
            PlaybackSources.KODIK -> KodikBadge(Modifier.fillMaxSize())
            PlaybackSources.SHIKIMORI -> BrandLetter(
                letter = "式",
                fg = Color(0xFF202024),
                bg = Color(0xFFE8E3EF),
                serif = true,
                scale = 0.58f,
                size = size
            )
            PlaybackSources.ANILIBERTY -> {
                val logo = remember { aniLibertyLogo() }
                Box(Modifier.fillMaxSize().background(Color(0xFF17171A)), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = logo,
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.fillMaxSize(0.83f)
                    )
                }
            }
            PlaybackSources.ANILIB -> {
                val logo = remember { animeLibLogo() }
                Box(Modifier.fillMaxSize().background(Color(0xFF20232A)), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = logo,
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.fillMaxSize(0.83f)
                    )
                }
            }
            PlaybackSources.ANISTAR -> AniStarBadge(Modifier.fillMaxSize(), size)
            PlaybackSources.ANIXART -> AnixartBadge(Modifier.fillMaxSize())
            PlaybackSources.SMARTHARD -> SmarthardBadge(Modifier.fillMaxSize(), size)
            PlaybackSources.TURBO -> TurboBadge(Modifier.fillMaxSize())
            PlaybackSources.VIDEOCDN -> VideoCdnBadge(Modifier.fillMaxSize())
            PlaybackSources.COLLAPS -> CollapsBadge(Modifier.fillMaxSize())
            PlaybackSources.VOIDBOOST -> BrandLetter(
                letter = "V", fg = Color(0xFFE53935), bg = Color(0xFF141414), scale = 0.52f, size = size
            )
            PlaybackSources.ALLOHA -> AllohaBadge(Modifier.fillMaxSize())
            PlaybackSources.HDREZKA -> BrandLetter(
                letter = "HD", fg = Color.White, bg = Color(0xFF8E1414), scale = 0.32f, size = size
            )
            PlaybackSources.VEOVEO -> BrandLetter(
                letter = "V", fg = Color.White, bg = Color(0xFF00A88E), scale = 0.52f, size = size
            )
            PlaybackSources.HENTAI_ALLHENTAI -> AllHentaiBadge(Modifier.fillMaxSize(), size)
            PlaybackSources.HENTAI_HENTAIDREAM -> BrandLetter(
                letter = "HD", fg = Color(0xFFFF6E9C), bg = Color(0xFF1E1B26), scale = 0.32f, size = size
            )
            PlaybackSources.HENTAI_HENTAIZ -> BrandLetter(
                letter = "Z", fg = Color(0xFFF5A623), bg = Color.Black, scale = 0.52f, size = size
            )
            PlaybackSources.HENTAI_HANIME1 -> BrandLetter(
                letter = "H", fg = Color(0xFFE93030), bg = Color(0xFF0D0D0F), scale = 0.52f, size = size
            )
            PlaybackSources.HENTAI_OPPAI -> OppaiBadge(Modifier.fillMaxSize())
            else -> BrandLetter(
                letter = sourceId.take(1).uppercase(),
                fg = MaterialTheme.colorScheme.onSurface,
                bg = MaterialTheme.colorScheme.surfaceContainerHighest,
                scale = 0.45f,
                size = size
            )
        }
    }
}

/** Одно- или двухбуквенный бейдж на фирменном фоне. */
@Composable
private fun BrandLetter(
    letter: String,
    fg: Color,
    bg: Color,
    size: Dp,
    scale: Float = 0.45f,
    serif: Boolean = false
) {
    Box(
        modifier = Modifier.fillMaxSize().background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = letter,
            fontSize = (size.value * scale).sp,
            fontWeight = FontWeight.Black,
            fontFamily = if (serif) FontFamily.Serif else FontFamily.Default,
            color = fg
        )
    }
}

/** Kodik: тёмный фон + белая монограмма K + зелёный play-бейдж (порт ic_src_kodik). */
@Composable
private fun KodikBadge(modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color(0xFF121826))) {
        val w = size.width
        val h = size.height
        val white = Color.White
        val sw = w * 0.089f
        drawLine(white, Offset(w * 0.20f, h * 0.25f), Offset(w * 0.20f, h * 0.75f), sw, StrokeCap.Round)
        drawLine(white, Offset(w * 0.42f, h * 0.28f), Offset(w * 0.21f, h * 0.50f), sw, StrokeCap.Round)
        drawLine(white, Offset(w * 0.21f, h * 0.50f), Offset(w * 0.42f, h * 0.72f), sw, StrokeCap.Round)
        drawCircle(Color(0xFF7FDC7F), radius = w * 0.208f, center = Offset(w * 0.694f, h * 0.50f))
        drawPath(
            Path().apply {
                moveTo(w * 0.644f, h * 0.38f)
                lineTo(w * 0.806f, h * 0.50f)
                lineTo(w * 0.644f, h * 0.62f)
                close()
            },
            Color(0xFF121826)
        )
    }
}

/** AniStar: белая подложка, чёрная A + фиолетовая S внахлёст (по ic_src_anistar.png). */
@Composable
private fun AniStarBadge(modifier: Modifier = Modifier, size: Dp) {
    Box(modifier.background(Color.White), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "A",
                fontSize = (size.value * 0.48f).sp,
                fontWeight = FontWeight.Black,
                color = Color.Black
            )
            Text(
                text = "S",
                fontSize = (size.value * 0.48f).sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFFA24BFF),
                modifier = Modifier.offset(x = -(size.value * 0.20f).dp)
            )
        }
    }
}

/** Anixart: тёмные «ушки» + красно-оранжевый диск с белым центром (по ic_src_anixart). */
@Composable
private fun AnixartBadge(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val ear = Color(0xFF3A3A40)
        drawPath(
            Path().apply {
                moveTo(w * 0.10f, h * 0.32f)
                lineTo(w * 0.20f, h * 0.02f)
                lineTo(w * 0.36f, h * 0.24f)
                close()
            },
            ear
        )
        drawPath(
            Path().apply {
                moveTo(w * 0.90f, h * 0.32f)
                lineTo(w * 0.80f, h * 0.02f)
                lineTo(w * 0.64f, h * 0.24f)
                close()
            },
            ear
        )
        val center = Offset(w * 0.5f, h * 0.56f)
        drawCircle(
            Brush.linearGradient(
                colors = listOf(Color(0xFFFDAA77), Color(0xFFFF5A67), Color(0xFFFA2961)),
                start = Offset(w * 0.15f, h * 0.15f),
                end = Offset(w * 0.85f, h * 0.95f)
            ),
            radius = minOf(w, h) * 0.40f,
            center = center
        )
        drawCircle(Color.White, radius = minOf(w, h) * 0.14f, center = center)
    }
}

/** Smarthard: тёмный архивный фон + белая S с розовым акцентом. */
@Composable
private fun SmarthardBadge(modifier: Modifier = Modifier, size: Dp) {
    Box(modifier.background(Color(0xFF26262B)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "S",
                fontSize = (size.value * 0.44f).sp,
                fontWeight = FontWeight.Black,
                color = Color.White
            )
            Box(
                Modifier
                    .width((size.value * 0.30f).dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFFFF5C8A))
            )
        }
    }
}

/** Turbo (ddbb): оранжевый фон + белая молния скорости. */
@Composable
private fun TurboBadge(modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color(0xFFFF6D00))) {
        val w = size.width
        val h = size.height
        drawPath(
            Path().apply {
                moveTo(w * 0.56f, h * 0.16f)
                lineTo(w * 0.30f, h * 0.56f)
                lineTo(w * 0.47f, h * 0.56f)
                lineTo(w * 0.40f, h * 0.84f)
                lineTo(w * 0.69f, h * 0.42f)
                lineTo(w * 0.52f, h * 0.42f)
                close()
            },
            Color.White
        )
    }
}

/** VideoCDN: тёмно-синий фон + белый контур экрана с cyan play. */
@Composable
private fun VideoCdnBadge(modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color(0xFF16294D))) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(w * 0.20f, h * 0.26f),
            size = Size(w * 0.60f, h * 0.48f),
            cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
            style = Stroke(width = w * 0.06f)
        )
        drawPath(
            Path().apply {
                moveTo(w * 0.44f, h * 0.39f)
                lineTo(w * 0.44f, h * 0.61f)
                lineTo(w * 0.62f, h * 0.50f)
                close()
            },
            Color(0xFF35E0E6)
        )
    }
}

/** Collaps: фиолетовый фон плеера + белая C-дуга с play-треугольником. */
@Composable
private fun CollapsBadge(modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color(0xFF5B3DF5))) {
        val w = size.width
        val h = size.height
        drawArc(
            color = Color.White,
            startAngle = 48f,
            sweepAngle = 264f,
            useCenter = false,
            topLeft = Offset(w * 0.22f, h * 0.24f),
            size = Size(w * 0.52f, h * 0.52f),
            style = Stroke(width = w * 0.11f, cap = StrokeCap.Round)
        )
        drawPath(
            Path().apply {
                moveTo(w * 0.45f, h * 0.41f)
                lineTo(w * 0.45f, h * 0.59f)
                lineTo(w * 0.61f, h * 0.50f)
                close()
            },
            Color.White
        )
    }
}

/** Alloha: тёмно-синий фон, белый шеврон + cyan play (по favicon allohatv.com). */
@Composable
private fun AllohaBadge(modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color(0xFF0B2A5B))) {
        val w = size.width
        val h = size.height
        drawPath(
            Path().apply {
                moveTo(w * 0.27f, h * 0.36f)
                lineTo(w * 0.27f, h * 0.64f)
                lineTo(w * 0.50f, h * 0.50f)
                close()
            },
            Color(0xFF35E0E6)
        )
        val chev = Color.White
        val sw = w * 0.10f
        drawLine(chev, Offset(w * 0.48f, h * 0.28f), Offset(w * 0.70f, h * 0.50f), sw, StrokeCap.Round)
        drawLine(chev, Offset(w * 0.70f, h * 0.50f), Offset(w * 0.48f, h * 0.72f), sw, StrokeCap.Round)
    }
}

/** AllHentai: тёмно-сливовый фон + розовые неко-ушки и белая A (по favicon). */
@Composable
private fun AllHentaiBadge(modifier: Modifier = Modifier, badgeSize: Dp) {
    Box(modifier.background(Color(0xFF2B1A2E)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val pink = Color(0xFFFF7AB8)
            drawPath(
                Path().apply {
                    moveTo(w * 0.22f, h * 0.36f)
                    lineTo(w * 0.28f, h * 0.08f)
                    lineTo(w * 0.44f, h * 0.30f)
                    close()
                },
                pink
            )
            drawPath(
                Path().apply {
                    moveTo(w * 0.78f, h * 0.36f)
                    lineTo(w * 0.72f, h * 0.08f)
                    lineTo(w * 0.56f, h * 0.30f)
                    close()
                },
                pink
            )
        }
        Text(
            text = "A",
            fontSize = (badgeSize.value * 0.44f).sp,
            fontWeight = FontWeight.Black,
            color = Color.White
        )
    }
}

/** Oppai.Stream: белый смайл — чёрные глаза-дуги + красная улыбка (по apple-touch-icon). */
@Composable
private fun OppaiBadge(modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color.White)) {
        val w = size.width
        val h = size.height
        val eyeW = w * 0.055f
        drawArc(
            color = Color.Black,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.22f, h * 0.30f),
            size = Size(w * 0.18f, h * 0.16f),
            style = Stroke(width = eyeW, cap = StrokeCap.Round)
        )
        drawArc(
            color = Color.Black,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.60f, h * 0.30f),
            size = Size(w * 0.18f, h * 0.16f),
            style = Stroke(width = eyeW, cap = StrokeCap.Round)
        )
        drawArc(
            color = Color(0xFFD81B60),
            startAngle = 22f,
            sweepAngle = 136f,
            useCenter = false,
            topLeft = Offset(w * 0.20f, h * 0.38f),
            size = Size(w * 0.60f, h * 0.48f),
            style = Stroke(width = w * 0.105f, cap = StrokeCap.Round)
        )
    }
}

/** Официальный логотип AniLiberty (anilibria.top): красный знак, вьюпорт 1174. */
private fun aniLibertyLogo(): ImageVector {
    val red = SolidColor(Color(0xFFFE3635))
    return ImageVector.Builder(
        name = "AniLiberty",
        defaultWidth = 36.dp,
        defaultHeight = 36.dp,
        viewportWidth = 1174f,
        viewportHeight = 1174f
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(
                "M698,245c0,-7 8,-12 14,-9l169,77c4,2 6,6 6,9v395c0,6 4,10 10,10h248c6,0 11,6 9,12c-25.3,94.2 -73.6,180.7 -140.7,251.5 -67.1,70.9 -150.8,123.9 -243.5,154.2 -92.7,30.4 -191.5,37.2 -287.5,19.8 -96,-17.4 -186.2,-58.5 -262.3,-119.5 -3,-3 -4,-8 -2,-12l34,-58c3,-5 10,-7 15,-3 51.5,43.1 111.1,75.4 175.4,94.9 64.2,19.6 131.7,25.9 198.5,18.7 66.7,-7.2 131.4,-27.8 190,-60.5 58.6,-32.8 110,-77 151.1,-130.1 5,-7 1,-16 -8,-16h-93c-4,0 -7,-2 -9,-5L699,574l-1,-5z"
            ).toNodes(),
            fill = red
        )
        addPath(
            pathData = PathParser().parsePathString(
                "M422,240c4,-7 14,-7 18,0l360,624c4,7 -1,15 -8,15H628c-4,0 -7,-2 -9,-5l-43,-75c-2,-3 -5,-5 -9,-5H373c-7,0 -12,-8 -8,-15l65,-113c1,-3 5,-5 8,-5h41c7,0 12,-8 8,-15l-47,-83c-4,-7 -14,-7 -18,0L211,929v1l-23,39 -1,1 -10,19c-4,6 -12,7 -16,2 -36,-38 -67,-82 -92,-128 -2,-3 -2,-7 0,-10l10,-19 2,-2v-1z"
            ).toNodes(),
            fill = red
        )
        addPath(
            pathData = PathParser().parsePathString(
                "M587,0c83.2,0.1 165.5,17.8 241.3,52.1 75.8,34.3 143.5,84.2 198.5,146.7 55.1,62.4 96.2,135.8 120.7,215.3s31.8,163.3 21.5,245.9c0,5 -4,9 -9,9h-67c-6,0 -11,-6 -10,-12 20.8,-146.3 -24,-294.3 -122.5,-404.5C862.1,142.4 720,81.3 572.3,85.6 424.6,90 286.3,159.3 194.5,275 102.7,390.8 66.6,541.2 96,686l-1,7 -47,81c-5,7 -15,6 -18,-2C0.5,683.8 -7.6,589.8 6.4,497.8c14,-91.9 49.6,-179.2 104.1,-254.7 54.4,-75.4 126,-136.8 208.9,-179.1C402.2,21.7 494,-0.2 587,0z"
            ).toNodes(),
            fill = red
        )
    }.build()
}

/** Официальный site-logo AnimeLib (animelib.org): фиолетовые уголки + белая A. */
private fun animeLibLogo(): ImageVector {
    return ImageVector.Builder(
        name = "AnimeLib",
        defaultWidth = 36.dp,
        defaultHeight = 36.dp,
        viewportWidth = 512f,
        viewportHeight = 512f
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(
                "M220,78v30c0,4.42 -3.58,8 -8,8h-86c-5.52,0 -10,4.48 -10,10v86c0,4.42 -3.58,8 -8,8H78c-4.42,0 -8,-3.58 -8,-8V82c0,-6.63 5.37,-12 12,-12h130c4.42,0 8,3.58 8,8zM292,434v-30c0,-4.42 3.58,-8 8,-8h86c5.52,0 10,-4.48 10,-10v-86c0,-4.42 3.58,-8 8,-8h30c4.42,0 8,3.58 8,8v130c0,6.63 -5.37,12 -12,12H300c-4.42,0 -8,-3.58 -8,-8z"
            ).toNodes(),
            fill = SolidColor(Color(0xFF4C2881))
        )
        addPath(
            pathData = PathParser().parsePathString(
                "M164.16,354c-1.5,0 -2.85,-0.6 -4.06,-1.82 -1.22,-1.21 -1.82,-2.56 -1.82,-4.06 0,-0.93 0.09,-1.77 0.28,-2.52l65.8,-179.76c0.56,-2.05 1.72,-3.87 3.5,-5.46 1.77,-1.58 4.24,-2.38 7.42,-2.38h41.44c3.17,0 5.64,0.8 7.42,2.38 1.77,1.59 2.94,3.41 3.5,5.46l65.52,179.76c0.37,0.75 0.56,1.59 0.56,2.52 0,1.5 -0.61,2.85 -1.82,4.06 -1.21,1.22 -2.66,1.82 -4.34,1.82h-34.44c-2.8,0 -4.9,-0.7 -6.3,-2.1 -1.4,-1.4 -2.29,-2.66 -2.66,-3.78l-10.92,-28.56h-74.76l-10.64,28.56c-0.38,1.12 -1.22,2.38 -2.52,3.78 -1.31,1.4 -3.55,2.1 -6.72,2.1h-34.44zM229.96,279.24h52.08L256,205.32l-26.04,73.92z"
            ).toNodes(),
            fill = SolidColor(Color.White)
        )
    }.build()
}
