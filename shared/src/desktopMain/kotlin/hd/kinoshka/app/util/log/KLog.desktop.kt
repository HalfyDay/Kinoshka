package hd.kinoshka.app.util.log

actual object KLog {
    private fun out(level: String, tag: String, message: String) {
        System.err.println("$level/$tag: $message")
    }

    actual fun d(tag: String, message: String) { out("D", tag, message) }
    actual fun i(tag: String, message: String) { out("I", tag, message) }
    actual fun w(tag: String, message: String) { out("W", tag, message) }
    actual fun w(tag: String, message: String, throwable: Throwable) { out("W", tag, "$message\n$throwable") }
    actual fun e(tag: String, message: String) { out("E", tag, message) }
    actual fun e(tag: String, message: String, throwable: Throwable) { out("E", tag, "$message\n$throwable") }
}
