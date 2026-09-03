package hd.kinoshka.app.util.log

/** Замена android.util.Log для кода, общего между Android и desktop. */
expect object KLog {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable)
    fun e(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable)
}
