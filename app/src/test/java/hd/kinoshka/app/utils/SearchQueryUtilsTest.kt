package hd.kinoshka.app.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Expectations here are derived mechanically from the QWERTY/ЙЦУКЕН key positions encoded in
 * [SearchQueryUtils.EN_CHARS] / RU_CHARS, index by index. The previous revision of this file
 * asserted strings of a different length than their input (`"qwert"` -> `"йцукен"`), which no
 * per-character mapping can ever satisfy — those cases were failing, not the implementation.
 */
class SearchQueryUtilsTest {

    @Test
    fun `fixKeyboardLayout converts English to Russian`() {
        // q w e r t -> й ц у к е
        assertEquals("йцуке", SearchQueryUtils.fixKeyboardLayout("qwert"))
    }

    @Test
    fun `fixKeyboardLayout converts Russian to English`() {
        // й ц у к е н -> q w e r t y
        assertEquals("qwerty", SearchQueryUtils.fixKeyboardLayout("йцукен"))
    }

    @Test
    fun `fixKeyboardLayout handles mixed case`() {
        assertEquals("Йцуке", SearchQueryUtils.fixKeyboardLayout("Qwert"))
    }

    @Test
    fun `fixKeyboardLayout preserves special characters`() {
        // '!' sits on neither layout map, so it passes through untouched.
        assertEquals("йцуке!", SearchQueryUtils.fixKeyboardLayout("qwert!"))
    }

    @Test
    fun `fixKeyboardLayout preserves numbers`() {
        assertEquals("йцуке123", SearchQueryUtils.fixKeyboardLayout("qwert123"))
    }

    @Test
    fun `fixKeyboardLayout handles empty string`() {
        assertEquals("", SearchQueryUtils.fixKeyboardLayout(""))
    }

    @Test
    fun `fixKeyboardLayout handles single character`() {
        assertEquals("й", SearchQueryUtils.fixKeyboardLayout("q"))
    }

    @Test
    fun `fixKeyboardLayout handles full Russian home row`() {
        // ф ы в а п р о л д ж э -> a s d f g h j k l ; '
        assertEquals("asdfghjkl;'", SearchQueryUtils.fixKeyboardLayout("фывапролджэ"))
    }

    @Test
    fun `fixKeyboardLayout handles full English home row`() {
        assertEquals("фывапролджэ", SearchQueryUtils.fixKeyboardLayout("asdfghjkl;'"))
    }

    @Test
    fun `fixKeyboardLayout treats period as a layout key`() {
        // '.' is the physical key carrying 'ю' on ЙЦУКЕН, so layout correction maps it. This is
        // deliberate: someone typing "ю" with the wrong layout active produces ".".
        assertEquals("ю", SearchQueryUtils.fixKeyboardLayout("."))
        assertEquals(".", SearchQueryUtils.fixKeyboardLayout("ю"))
    }
}
