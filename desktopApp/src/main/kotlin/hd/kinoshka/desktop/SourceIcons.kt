package hd.kinoshka.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import hd.kinoshka.app.data.source.PlaybackSources
import hd.kinoshka.app.ui.screens.SourceBrandIcon

/**
 * Иконка источника для страницы «Источники» на desktop.
 *
 * Аниме — те же логотипы, что в мобильном пикере озвучек/источников:
 * Kodik и AniLiberty отрендерены из тех же vector-drawable в PNG 256,
 * AnimeLib/Anixart/Shikimori/AniStar — официальные PNG с сайтов и приложения.
 * Остальные с реальными логотипами — PNG с официальных сайтов/фавиконов
 * (desktopApp/src/main/resources/icons). У Turbo, VideoCDN, Collaps и Veoveo
 * публичных логотипов нет — рисованный бейдж из shared.
 */
@Composable
fun DesktopSourceIcon(sourceId: String, modifier: Modifier = Modifier) {
    when (PlaybackSources.canonical(sourceId)) {
        PlaybackSources.KODIK ->
            Badge(Color(0xFF121826), "icons/ic_src_kodik_desktop.png", 1f, ContentScale.Fit, modifier)
        PlaybackSources.SHIKIMORI ->
            Badge(Color(0xFFE8E3EF), "icons/ic_src_shikimori.png", 1f, ContentScale.Fit, modifier)
        PlaybackSources.ANILIBERTY ->
            Badge(Color(0xFF17171A), "icons/ic_src_aniliberty_desktop.png", 0.83f, ContentScale.Fit, modifier)
        PlaybackSources.ANILIB ->
            Badge(Color(0xFF20232A), "icons/ic_src_animelib_desktop.png", 0.83f, ContentScale.Fit, modifier)
        PlaybackSources.ANISTAR ->
            Badge(Color.White, "icons/ic_src_anistar.png", 0.83f, ContentScale.Fit, modifier)
        PlaybackSources.ANIXART ->
            // Лого с «ушками» до краёв вьюпорта: в круге показываем уменьшенным.
            Image(
                painter = painterResource("icons/ic_src_anixart_desktop.png"),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = modifier
                    .clip(CircleShape)
                    .padding(5.dp)
            )
        PlaybackSources.ALLOHA ->
            Badge(Color(0xFF0B2A5B), "icons/ic_src_alloha.png", 0.88f, ContentScale.Fit, modifier)
        PlaybackSources.HENTAI_OPPAI ->
            Badge(Color.White, "icons/ic_src_oppai.png", 0.92f, ContentScale.Fit, modifier)
        PlaybackSources.HENTAI_ALLHENTAI ->
            Badge(null, "icons/ic_src_allhentai.png", 1f, ContentScale.Crop, modifier)
        PlaybackSources.HENTAI_HENTAIZ ->
            Badge(Color.Black, "icons/ic_src_hentaiz.png", 0.9f, ContentScale.Fit, modifier)
        PlaybackSources.HENTAI_HENTAIDREAM ->
            Badge(null, "icons/ic_src_hentaidream.png", 1f, ContentScale.Crop, modifier)
        PlaybackSources.HENTAI_HANIME1 ->
            Badge(Color.Black, "icons/ic_src_hanime1.png", 0.9f, ContentScale.Fit, modifier)
        PlaybackSources.SMARTHARD ->
            Badge(Color(0xFF26262B), "icons/ic_src_smarthard.png", 0.88f, ContentScale.Fit, modifier)
        PlaybackSources.VOIDBOOST ->
            Badge(Color(0xFF141414), "icons/ic_src_voidboost.png", 0.9f, ContentScale.Fit, modifier)
        else -> SourceBrandIcon(sourceId = sourceId)
    }
}

@Composable
private fun Badge(
    bg: Color?,
    path: String,
    artFraction: Float,
    contentScale: ContentScale,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.clip(CircleShape)
            .then(if (bg != null) Modifier.background(bg) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(path),
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(artFraction)
        )
    }
}
