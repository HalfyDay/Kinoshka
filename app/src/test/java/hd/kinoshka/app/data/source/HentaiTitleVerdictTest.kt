package hd.kinoshka.app.data.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Вердикт «это хентай» для кнопки «Смотреть»: возрастной ценз 18+ сам по себе
 * хентаем не является (кейс фильма «Акира»: Kinopoisk age18 + mpaa R, жанры чистые).
 */
class HentaiTitleVerdictTest {

    @Test
    fun `akira style r-rated anime film is not hentai`() {
        assertFalse(
            HentaiStreamResolver.isHentaiTitle(
                genreNames = listOf("аниме", "мультфильм", "фантастика", "боевик"),
                ratingMpaa = "r",
                originalTitle = "Akira",
                russianTitle = "Акира"
            )
        )
    }

    @Test
    fun `hentai genre marks title as adult`() {
        assertTrue(
            HentaiStreamResolver.isHentaiTitle(
                genreNames = listOf("аниме", "хентай"),
                ratingMpaa = null,
                originalTitle = "Some Title",
                russianTitle = "Какое-то название"
            )
        )
    }

    @Test
    fun `adult mpaa marks title as adult`() {
        assertTrue(
            HentaiStreamResolver.isHentaiTitle(
                genreNames = listOf("аниме"),
                ratingMpaa = "NC-17",
                originalTitle = "Some Title",
                russianTitle = "Какое-то название"
            )
        )
    }

    @Test
    fun `plain anime without markers is not hentai`() {
        assertFalse(
            HentaiStreamResolver.isHentaiTitle(
                genreNames = listOf("аниме", "комедия"),
                ratingMpaa = "pg13",
                originalTitle = "Naruto",
                russianTitle = "Наруто"
            )
        )
    }

    @Test
    fun `weak single words never match catalog containment`() {
        // «akira» — имя персонажа хентая, а не тайтл: containment запрещён.
        assertFalse(HentaiStreamResolver.isStrongCatalogQuery("akira"))
        assertFalse(HentaiStreamResolver.isStrongCatalogQuery("акира"))
        assertFalse(HentaiStreamResolver.isStrongCatalogQuery(""))
    }

    @Test
    fun `long phrases stay matchable`() {
        assertTrue(HentaiStreamResolver.isStrongCatalogQuery("bible black"))
        assertTrue(HentaiStreamResolver.isStrongCatalogQuery("bondage game"))
    }

    @Test
    fun `shikimori verdict ignores r-plus and age ratings`() {
        // Акира: Shikimori rating r_plus, чистые жанры — обычный пикер, не хентай.
        assertFalse(
            HentaiStreamResolver.isHentaiShikimori(
                rating = "r_plus",
                genreNames = listOf("Action", "Sci-Fi", "Seinen"),
                originalTitle = "Akira",
                russianTitle = "Акира"
            )
        )
    }

    @Test
    fun `shikimori rx rating marks title as adult`() {
        assertTrue(
            HentaiStreamResolver.isHentaiShikimori(
                rating = "rx",
                genreNames = emptyList(),
                originalTitle = "Some Title",
                russianTitle = "Какое-то название"
            )
        )
    }

    @Test
    fun `shikimori hentai genre marks title as adult`() {
        assertTrue(
            HentaiStreamResolver.isHentaiShikimori(
                rating = "r_plus",
                genreNames = listOf("Hentai"),
                originalTitle = "Some Title",
                russianTitle = "Какое-то название"
            )
        )
    }
}
