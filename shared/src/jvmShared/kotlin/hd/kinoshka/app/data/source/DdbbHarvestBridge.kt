package hd.kinoshka.app.data.source

/**
 * Мост jvmShared → Android-реализация headless-WebView харвестера (app-модуль:
 * hd.kinoshka.app.data.source.WebViewStreamHarvester — general WebView недоступна в shared).
 * KinoApplication устанавливает harvester при старте; пока не установлен (или на desktop),
 * harvest возвращает null — ddbb-резолвер просто пропускает harvest-ветку.
 */
object DdbbHarvestBridge {
    data class Harvested(val url: String, val referer: String?)

    var harvester: (suspend (embedUrl: String, pageReferer: String?, timeoutMs: Long) -> Harvested?)? = null

    suspend fun harvest(embedUrl: String, pageReferer: String?, timeoutMs: Long): Harvested? =
        harvester?.invoke(embedUrl, pageReferer, timeoutMs)
}
