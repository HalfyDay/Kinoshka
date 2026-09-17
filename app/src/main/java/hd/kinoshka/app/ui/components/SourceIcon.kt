package hd.kinoshka.app.ui.components

import androidx.annotation.DrawableRes
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
import hd.kinoshka.app.R
import hd.kinoshka.app.data.source.PlaybackSources
import hd.kinoshka.app.ui.screens.SourceBrandIcon

/**
 * Иконка источника для страницы «Источники».
 *
 * Аниме — те же drawable, что на экране выбора серии/озвучки/источника
 * ([hd.kinoshka.app.ui.screens.AnimePlaybackSelectionScreen]): Kodik, Shikimori,
 * AniLiberty, AnimeLib, AniStar, Anixart. Остальные с реальными логотипами —
 * PNG с официальных сайтов/фавиконов (см. drawable-nodpi/ic_src_*). У Turbo,
 * VideoCDN, Collaps и Veoveo публичных логотипов нет (внутренние типы эмбедов
 * ddbb) — для них рисованный бейдж из shared ([SourceBrandIcon]).
 */
@Composable
fun AppSourceIcon(sourceId: String, modifier: Modifier = Modifier) {
    when (PlaybackSources.canonical(sourceId)) {
        // --- Аниме: 1-в-1 как в пикере озвучек/источников ---
        PlaybackSources.KODIK ->
            Badge(Color(0xFF121826), R.drawable.ic_src_kodik, 1f, ContentScale.Fit, modifier)
        PlaybackSources.SHIKIMORI ->
            Badge(Color(0xFFE8E3EF), R.drawable.ic_src_shikimori, 1f, ContentScale.Fit, modifier)
        PlaybackSources.ANILIBERTY ->
            Badge(Color(0xFF17171A), R.drawable.ic_src_aniliberty, 0.83f, ContentScale.Fit, modifier)
        PlaybackSources.ANILIB ->
            Badge(Color(0xFF20232A), R.drawable.ic_src_animelib, 0.83f, ContentScale.Fit, modifier)
        PlaybackSources.ANISTAR ->
            Badge(Color.White, R.drawable.ic_src_anistar, 0.83f, ContentScale.Fit, modifier)
        PlaybackSources.ANIXART ->
            // Лого с «ушками» до краёв вьюпорта: в круге показываем уменьшенным,
            // иначе края режутся.
            Image(
                painter = painterResource(R.drawable.ic_src_anixart),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = modifier
                    .clip(CircleShape)
                    .padding(9.dp)
            )
        // --- Реальные логотипы картинками ---
        // Alloha: favicon allohatv.com (тёмно-синий шеврон + cyan play).
        PlaybackSources.ALLOHA ->
            Badge(Color(0xFF0B2A5B), R.drawable.ic_src_alloha, 0.88f, ContentScale.Fit, modifier)
        // Oppai.Stream: apple-touch-icon (смайл на прозрачном).
        PlaybackSources.HENTAI_OPPAI ->
            Badge(Color.White, R.drawable.ic_src_oppai, 0.92f, ContentScale.Fit, modifier)
        // AllHentai: favicon allhentai.fun (во всю плитку).
        PlaybackSources.HENTAI_ALLHENTAI ->
            Badge(null, R.drawable.ic_src_allhentai, 1f, ContentScale.Crop, modifier)
        // HentaiZ: apple-icon ru.hentaiiz.org (оранжевая Z на чёрном).
        PlaybackSources.HENTAI_HENTAIZ ->
            Badge(Color.Black, R.drawable.ic_src_hentaiz, 0.9f, ContentScale.Fit, modifier)
        // HentaiDream: favicon hentaidream.fun (во всю плитку).
        PlaybackSources.HENTAI_HENTAIDREAM ->
            Badge(null, R.drawable.ic_src_hentaidream, 1f, ContentScale.Crop, modifier)
        // Hanime1.me: tab-иконка сайта (красная H, чёрный бренд).
        PlaybackSources.HENTAI_HANIME1 ->
            Badge(Color.Black, R.drawable.ic_src_hanime1, 0.9f, ContentScale.Fit, modifier)
        // Smarthard: favicon smarthard.net (архив shikicinema).
        PlaybackSources.SMARTHARD ->
            Badge(Color(0xFF26262B), R.drawable.ic_src_smarthard, 0.88f, ContentScale.Fit, modifier)
        // Voidboost: favicon voidboost.net (HD, бэкенд Rezka).
        PlaybackSources.VOIDBOOST ->
            Badge(Color(0xFF141414), R.drawable.ic_src_voidboost, 0.9f, ContentScale.Fit, modifier)
        // Логотипов не существует — рисованный бейдж.
        else -> SourceBrandIcon(sourceId = sourceId)
    }
}

/** Круглая подложка фирменного цвета + арт поверх (как в пикере источников). */
@Composable
private fun Badge(
    bg: Color?,
    @DrawableRes art: Int,
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
            painter = painterResource(art),
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(artFraction)
        )
    }
}
