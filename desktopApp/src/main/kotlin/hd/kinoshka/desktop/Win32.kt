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

    /** HWND окна верхнего уровня по точному заголовку (точки входа плеера и окна видео). */
    fun findWindowHwnd(title: String): Long? {
        val hwnd: HWND = User32.INSTANCE.FindWindow(null, title) ?: return null
        return Pointer.nativeValue(hwnd.pointer)
    }

    private fun hwndOf(peer: Long): HWND = HWND(Pointer.createConstant(peer))

    /** Поставить hwnd непосредственно ПОД insertBelowHwnd по z-порядку (видео под окном UI). */
    fun setZOrderBelow(hwnd: Long, insertBelowHwnd: Long) {
        User32.INSTANCE.SetWindowPos(
            hwndOf(hwnd),
            hwndOf(insertBelowHwnd),
            0, 0, 0, 0,
            WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or WinUser.SWP_NOACTIVATE,
        )
    }

    /** Геометрия окна без активации (синхронизация окна видео с окном плеера). */
    fun moveWindow(hwnd: Long, x: Int, y: Int, width: Int, height: Int) {
        User32.INSTANCE.SetWindowPos(
            hwndOf(hwnd),
            null,
            x, y, width, height,
            WinUser.SWP_NOZORDER or WinUser.SWP_NOACTIVATE,
        )
    }
}
