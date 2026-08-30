package hd.kinoshka.app.data.download

/**
 * Человекочитаемый размер («1,4 ГБ»). Общий для Android и desktop:
 * извлечена из DownloadModels (app), который остался в app из-за android.net.Uri.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "?"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(java.util.Locale.getDefault(), "%.1f ГБ", gb)
        mb >= 1.0 -> String.format(java.util.Locale.getDefault(), "%.0f МБ", mb)
        kb >= 1.0 -> String.format(java.util.Locale.getDefault(), "%.0f КБ", kb)
        else -> "$bytes Б"
    }
}
