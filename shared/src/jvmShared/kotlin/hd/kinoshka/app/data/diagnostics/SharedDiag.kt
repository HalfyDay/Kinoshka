package hd.kinoshka.app.data.diagnostics

/**
 * Мост jvmShared → кольцевой буфер событий app-модуля (AppDiagnostics — android.util.Log,
 * краш-хендлер и системный «Поделиться» остаются в app). KinoApplication подключает sink при
 * старте; без него события резолверов просто не пишутся в диагностический отчёт.
 */
object SharedDiag {
    var sink: ((message: String) -> Unit)? = null

    fun event(message: String) {
        sink?.invoke(message)
    }
}
