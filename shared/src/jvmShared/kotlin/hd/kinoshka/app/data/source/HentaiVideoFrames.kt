package hd.kinoshka.app.data.source

/**
 * Кадры из видео ([stream]) по ключевым моментам: файлы пишутся в [dir] с префиксом [prefix].
 * null в списке — конкретный кадр снять не удалось. Платформа без видеодекодера (desktop)
 * отдаёт пустой список — вызывающий код падает на скриншоты страниц и обложки каталога.
 */
internal expect suspend fun grabVideoFrameFiles(
    stream: HentaiStream,
    dir: java.io.File,
    prefix: String
): List<java.io.File?>
