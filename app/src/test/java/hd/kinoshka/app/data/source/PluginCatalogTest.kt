package hd.kinoshka.app.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCatalogTest {

    private val indexJson = """
        {
            "format": 1,
            "updatedAt": "2026-09-19",
            "plugins": [
                {
                    "id": "example-embed",
                    "name": "Example Embed",
                    "author": "@kinoshka",
                    "description": "Шаблон",
                    "version": "1.1.0",
                    "codeUrl": "https://example.com/plugin.js",
                    "sha256": "abc",
                    "sections": ["FILMS", "ANIME"],
                    "minAppVersion": "1.1.5",
                    "verified": true
                },
                {"id": "broken", "name": "", "version": "x"},
                {"id": "BAD ID!", "name": "Bad", "version": "1", "codeUrl": "https://example.com/b.js"}
            ]
        }
    """.trimIndent()

    @Test
    fun `parses index and skips broken entries`() {
        val index = PluginCatalog.parseIndex(indexJson)!!
        assertEquals(1, index.entries.size)
        val e = index.entries.first()
        assertEquals("example-embed", e.id)
        assertEquals("1.1.0", e.version)
        assertTrue(e.verified)
        assertEquals(setOf(SourceCategory.FILMS, SourceCategory.ANIME), e.sections)
    }

    @Test
    fun `unknown sections fall back to films`() {
        val index = PluginCatalog.parseIndex(
            """{"format":1,"plugins":[{"id":"a","name":"A","version":"1","codeUrl":"https://example.com/a.js","sections":["NOPE"]}]}"""
        )!!
        assertEquals(setOf(SourceCategory.FILMS), index.entries.first().sections)
    }

    @Test
    fun `sha256 matches known vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            PluginCatalog.sha256Hex("abc")
        )
    }

    @Test
    fun `registry slot is stable per catalog id`() {
        assertEquals("CUSTOM_EXAMPLE-EMBED", PluginCatalog.registryIdFor("example-embed"))
    }

    @Test
    fun `target reuses installed row, fresh otherwise`() {
        val entry = PluginCatalog.parseIndex(indexJson)!!.entries.first()
        val installed = CustomSource(
            id = "CUSTOM_EXAMPLE-EMBED", name = "Example", urlTemplate = "",
            kind = CustomSourceKind.PLUGIN, endpoint = "https://example.com/plugin.js",
            pluginVersion = "1.0.0"
        )
        assertEquals("CUSTOM_EXAMPLE-EMBED", PluginCatalog.targetId(entry, listOf(installed)))
        assertTrue(PluginCatalog.hasUpdate(installed, entry))
        assertFalse(PluginCatalog.hasUpdate(installed.copy(pluginVersion = "1.1.0"), entry))
        assertFalse(PluginCatalog.hasUpdate(null, entry))
        // Слот занят чужим — суффикс, не затираем.
        val other = CustomSource(id = "CUSTOM_EXAMPLE-EMBED", name = "Чужой", urlTemplate = "")
        assertEquals("CUSTOM_EXAMPLE-EMBED-2", PluginCatalog.targetId(entry, listOf(other)))
    }

    @Test
    fun `version compare is numeric`() {
        assertTrue(PluginCatalog.compareVersions("1.1.5", "1.1.15") < 0)
        assertTrue(PluginCatalog.compareVersions("1.1.5", "1.1.5") == 0)
        assertTrue(PluginCatalog.compareVersions("2.0", "1.9.9") > 0)
        val entry = PluginCatalog.parseIndex(indexJson)!!.entries.first()
        assertTrue(PluginCatalog.isAppVersionOk(entry, "1.1.5"))
        assertFalse(PluginCatalog.isAppVersionOk(entry, "1.1.4"))
        assertTrue(PluginCatalog.isAppVersionOk(entry.copy(minAppVersion = ""), "0.1"))
    }
}
