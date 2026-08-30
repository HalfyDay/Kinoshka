package hd.kinoshka.app.ui.common

/**
 * Цепочка фолбэк-URL для постеров аниме: зеркала smarthard ↔ shikimori.
 * Общая для Android- (Coil) и desktop- (Skia) реализаций KinoRemoteImage.
 */
fun buildImageUrlFallbacks(rawUrl: String?): List<String> {
    val str = rawUrl ?: return emptyList()
    val urls = mutableListOf<String>()
    urls.add(str)

    val idRegex = Regex("""(?:animes/|animes/original/|animes/preview/|animes/x96/|animes/x48/|animes/|/static/animes/)?(\d+)(?:\.jpeg|\.jpg|\?|/|$)""")
    val match = idRegex.find(str)
    val animeId = match?.groupValues?.get(1)?.toIntOrNull()

    if (str.contains("smarthard.net") && animeId != null && animeId > 0) {
        urls.add("https://shikimori.io/system/animes/original/$animeId.jpg")
        urls.add("https://shikimori.one/system/animes/original/$animeId.jpg")
    } else if (str.contains("shikimori")) {
        if (str.contains("shikimori.io")) {
            urls.add(str.replace("shikimori.io", "shikimori.one"))
        } else if (str.contains("shikimori.one")) {
            urls.add(str.replace("shikimori.one", "shikimori.io"))
        }
        if (animeId != null && animeId > 0) {
            urls.add("https://smarthard.net/static/animes/$animeId.jpeg")
        }
    }
    return urls.distinct()
}
