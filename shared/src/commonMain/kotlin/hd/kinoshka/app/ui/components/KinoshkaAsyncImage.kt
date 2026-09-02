package hd.kinoshka.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale

/**
 * Общий аналог прежнего app-компонента: картинка с сетью фолбэков (buildImageUrlFallbacks),
 * shimmer-загрузкой и плавным появлением. Android — Coil (SubcomposeAsyncImage), desktop —
 * общий Skia-загрузчик KinoRemoteImage.
 */
@Composable
expect fun KinoshkaAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    filterQuality: FilterQuality = FilterQuality.Medium,
    /** Когда true — запрос полноразмерной картинки (без даунсэмплинга). Для крупных вью. */
    useOriginalSize: Boolean = false,
    fadeDurationMs: Int = 520,
    /** Рисуется, когда [model] и его внутренняя цепочка фолбэков не загрузились
     *  (например, превью YouTube-трейлера недоступно без VPN — показываем постер тайтла). */
    fallbackModel: Any? = null,
    /** Успешная загрузка: платформенно-нейтральные размеры загруженной картинки
     *  (вместо Coil-state; размер может быть неизвестен — вызывающие код защищён). */
    onSuccess: ((width: Int, height: Int) -> Unit)? = null
)
