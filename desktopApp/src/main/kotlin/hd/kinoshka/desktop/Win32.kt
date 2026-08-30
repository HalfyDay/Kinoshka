package hd.kinoshka.desktop

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser

internal object Win32 {
    /**
     * HWND heavyweight-канваса AWT внутри окна с заданным заголовком.
     * mpv через опцию wid рендерит прямо в это дочернее окно.
     * AWT-пир канваса на Windows регистрирует класс "SunAwtCanvas".
     */
    fun findChildCanvasHwnd(windowTitle: String): Long? {
        val parent: HWND = User32.INSTANCE.FindWindow(null, windowTitle) ?: return null
        val found = arrayOfNulls<HWND>(1)
        val proc = object : WinUser.WNDENUMPROC {
            override fun callback(child: HWND, data: Pointer?): Boolean {
                val className = CharArray(64)
                User32.INSTANCE.GetClassName(child, className, 64)
                if (String(className).trim('\u0000') == "SunAwtCanvas") {
                    found[0] = child
                    return false
                }
                return true
            }
        }
        User32.INSTANCE.EnumChildWindows(parent, proc, null)
        return found[0]?.let { Pointer.nativeValue(it.pointer) }
    }
}
