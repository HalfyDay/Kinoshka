package hd.kinoshka.app.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchQueryUtilsTest {

    @Test
    fun `fixKeyboardLayout converts English to Russian`() {
        assertEquals("йцукен", SearchQueryUtils.fixKeyboardLayout("qwert"))
    }

    @Test
    fun `fixKeyboardLayout converts Russian to English`() {
        assertEquals("qwert", SearchQueryUtils.fixKeyboardLayout("йцукен"))
    }

    @Test
    fun `fixKeyboardLayout handles mixed case`() {
        assertEquals("Йцукен", SearchQueryUtils.fixKeyboardLayout("Qwert"))
    }

    @Test
    fun `fixKeyboardLayout preserves special characters`() {
        assertEquals("йцукен!", SearchQueryUtils.fixKeyboardLayout("qwert!"))
    }

    @Test
    fun `fixKeyboardLayout preserves numbers`() {
        assertEquals("йцукен123", SearchQueryUtils.fixKeyboardLayout("qwert123"))
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
    fun `fixKeyboardLayout handles full Russian layout`() {
        val input = "фываапролджэ"
        val expected = "asdfghjkl;'"
        assertEquals(expected, SearchQueryUtils.fixKeyboardLayout(input))
    }

    @Test
    fun `fixKeyboardLayout handles full English layout`() {
        val input = "asdfghjkl;'"
        val expected = "фываапролджэ"
        assertEquals(expected, SearchQueryUtils.fixKeyboardLayout(input))
    }

    @Test
    fun `fixKeyboardLayout handles cyrillic punctuation`() {
        // Russian period maps to English period
        assertEquals(".", SearchQueryUtils.fixKeyboardLayout("."))
    }
}
