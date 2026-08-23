package hd.kinoshka.app.data.source

import hd.kinoshka.app.data.model.MovieContentKind
import hd.kinoshka.app.data.model.MoviePlaybackRequest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reproduction of "Catalog rejected: 14 provider results, no identity match" for kinopoiskId=5437614.
 * Each test documents ONE gate in matchesIdentity that rejects an authoritative result.
 */
class KodikMovieIdentityReproTest {
    private val byId = KodikMovieParser.MatchOrigin.KINOPOISK_ID

    private val request = MoviePlaybackRequest(
        kinopoiskId = 5437614,
        imdbId = null,
        titles = listOf("Проект «Аве Мария»", "Project Hail Mary"),
        year = 2026,
        kind = MovieContentKind.MOVIE
    )

    // Baseline: proves int() parses the STRING kinopoisk_id fine.
    @Test
    fun `string kinopoisk id parses`() {
        val c = KodikMovieParser.parseCandidates(
            """{"results":[{"link":"//kodik.info/video/1/a/720p","type":"foreign-movie",
               "title":"Проект «Аве Мария»","year":2026,"kinopoisk_id":"5437614"}]}"""
        ).single()
        assertEquals(5437614, c.kinopoiskId)
        assertEquals(MovieContentKind.MOVIE, c.kind)
        assertEquals(1, KodikMovieParser.acceptedCandidates(request, listOf(c), byId).size)
    }

    // GATE A (line 60): year disagreement kills an EXACT kinopoisk_id match.
    @Test
    fun `exact kinopoisk id now accepted when year differs`() {
        val candidates = KodikMovieParser.parseCandidates(
            """{"results":[{"link":"//kodik.info/video/1/a/720p","type":"foreign-movie",
               "title":"Проект «Аве Мария»","year":2024,"kinopoisk_id":"5437614"}]}"""
        )
        assertEquals(5437614, candidates.single().kinopoiskId)
        assertEquals(1, KodikMovieParser.acceptedCandidates(request, candidates, byId).size)
    }

    // GATE B (line 59): kind disagreement kills an EXACT kinopoisk_id match.
    @Test
    fun `exact kinopoisk id now accepted when kodik mislabels film as serial`() {
        val candidates = KodikMovieParser.parseCandidates(
            """{"results":[{"link":"//kodik.info/video/1/a/720p","type":"foreign-serial",
               "title":"Проект «Аве Мария»","year":2026,"kinopoisk_id":"5437614",
               "seasons":{"1":{"episodes":{"1":{"link":"//kodik.info/seria/1/a/720p"}}}}}]}"""
        )
        assertEquals(5437614, candidates.single().kinopoiskId)
        assertEquals(1, KodikMovieParser.acceptedCandidates(request, candidates, byId).size)
    }

    // GATE C (line 74): parseKind maps real Kodik types to UNKNOWN -> title match discarded.
    @Test
    fun `kodik one-off types now map to MOVIE and are accepted`() {
        listOf("anime", "soviet-cartoon", "foreign-cartoon", "russian-cartoon").forEach { type ->
            val candidates = KodikMovieParser.parseCandidates(
                """{"results":[{"link":"//kodik.info/video/1/a/720p","type":"$type",
                   "title":"Проект «Аве Мария»","year":2026}]}"""
            )
            assertEquals("type=$type", MovieContentKind.MOVIE, candidates.single().kind)
            assertEquals("type=$type", 1, KodikMovieParser.acceptedCandidates(request, candidates).size)
        }
    }

    // GATE D (line 73): perfect title+kind match rejected purely because year is absent.
    @Test
    fun `title and kind match now accepted when kodik omits year`() {
        val candidates = KodikMovieParser.parseCandidates(
            """{"results":[{"link":"//kodik.info/video/1/a/720p","type":"foreign-movie",
               "title":"Проект «Аве Мария»","title_orig":"Project Hail Mary"}]}"""
        )
        assertEquals(1, KodikMovieParser.acceptedCandidates(request, candidates).size)
    }

    // GATE E: a request without a year previously rejected every title match.
    @Test
    fun `request without year now accepted via kind corroboration`() {
        val noYear = request.copy(kinopoiskId = null, year = null)
        val candidates = KodikMovieParser.parseCandidates(
            """{"results":[{"link":"//kodik.info/video/1/a/720p","type":"foreign-movie",
               "title":"Проект «Аве Мария»","year":2026}]}"""
        )
        assertEquals(1, KodikMovieParser.acceptedCandidates(noYear, candidates).size)
    }

    // GATE F: Kinopoisk publishes duplicate cards for one film (verified on device: "Семь самураев"
    // exists as both kp=332, which Kodik indexes, and kp=522003, which the app opened and which
    // carries neither imdbId nor year). A differing kinopoisk_id is therefore a weak counter-signal,
    // not proof of a different film. When title AND kind AND year all agree, accept.
    @Test
    fun `duplicate kinopoisk card accepted when title kind and year all agree`() {
        val candidates = KodikMovieParser.parseCandidates(
            """{"results":[{"link":"//kodik.info/video/9/z/720p","type":"foreign-movie",
               "title":"Проект «Аве Мария»","year":2026,"kinopoisk_id":"999999"}]}"""
        )
        assertEquals(1, KodikMovieParser.acceptedCandidates(request, candidates).size)
    }

    // Guard: a genuinely different film — differing kinopoisk_id AND a year that cannot be a
    // rounding difference — must still be rejected on a title search.
    @Test
    fun `conflicting kinopoisk id with distant year still rejected on title search`() {
        val candidates = KodikMovieParser.parseCandidates(
            """{"results":[{"link":"//kodik.info/video/9/z/720p","type":"foreign-movie",
               "title":"Проект «Аве Мария»","year":2011,"kinopoisk_id":"999999"}]}"""
        )
        assertEquals(0, KodikMovieParser.acceptedCandidates(request, candidates).size)
    }

    // Guard: a differing kinopoisk_id plus a kind disagreement is still rejected — with the id
    // contradicted, kind corroboration is mandatory rather than merely one of two options.
    @Test
    fun `conflicting kinopoisk id with wrong kind still rejected on title search`() {
        val candidates = KodikMovieParser.parseCandidates(
            """{"results":[{"link":"//kodik.info/video/9/z/720p","type":"foreign-serial",
               "title":"Проект «Аве Мария»","kinopoisk_id":"999999",
               "seasons":{"1":{"episodes":{"1":{"link":"//kodik.info/seria/1/a/720p"}}}}}]}"""
        )
        assertEquals(0, KodikMovieParser.acceptedCandidates(request, candidates).size)
    }

    // An agreeing IMDb id is canonical and outranks a Kinopoisk mismatch entirely — this is the
    // exact shape of the "Семь самураев" failure (kp differs, tt0047478 agrees).
    @Test
    fun `agreeing imdb id accepted despite conflicting kinopoisk id`() {
        val withImdb = request.copy(imdbId = "tt0047478")
        val candidates = KodikMovieParser.parseCandidates(
            """{"results":[{"link":"//kodik.info/video/9/z/720p","type":"foreign-movie",
               "title":"Совершенно другое локализованное название","year":1954,
               "kinopoisk_id":"332","imdb_id":"tt0047478"}]}"""
        )
        assertEquals(1, KodikMovieParser.acceptedCandidates(withImdb, candidates).size)
    }

    // Guard: an unrelated title on a title search must still be rejected.
    @Test
    fun `unrelated title still rejected on title search`() {
        val anon = request.copy(kinopoiskId = null)
        val candidates = KodikMovieParser.parseCandidates(
            """{"results":[{"link":"//kodik.info/video/9/z/720p","type":"foreign-movie",
               "title":"Совершенно другой фильм","year":2026}]}"""
        )
        assertEquals(0, KodikMovieParser.acceptedCandidates(anon, candidates).size)
    }
}
