package hd.kinoshka.app.data.source

import hd.kinoshka.app.data.model.MovieContentKind
import hd.kinoshka.app.data.model.MoviePlaybackRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KodikMovieParserTest {
    @Test
    fun `parses top level movie links`() {
        val candidates = KodikMovieParser.parseCandidates(
            """{"results":[
                {"link":"//player.example/movie","type":"movie"},
                {"player_url":"https://player.example/second","type":"movie"},
                {"iframe_url":"https://player.example/third","type":"movie"}
            ]}"""
        )
        assertEquals(3, candidates.size)
        assertEquals("https://player.example/movie", candidates[0].topLevelPlayerUrl)
        assertNotNull(candidates[1].topLevelPlayerUrl)
    }

    @Test
    fun `parses identity from material data`() {
        val candidate = KodikMovieParser.parseCandidates(
            """{"results":[{"link":"https://player.example/movie","type":"foreign-movie","material_data":{
                "kinopoisk_id":"5437614","imdb_id":"TT14513804","title":"Project Hail Mary","year":"2026"
            }}]}"""
        ).single()
        assertEquals(5437614, candidate.kinopoiskId)
        assertEquals("tt14513804", candidate.imdbId)
        assertEquals(2026, candidate.year)
    }

    @Test
    fun `top level identity wins over nested metadata`() {
        val candidate = KodikMovieParser.parseCandidates(
            """{"results":[{"link":"https://player.example/movie","kinopoisk_id":1,"material_data":{"kinopoisk_id":2}}]}"""
        ).single()
        assertEquals(1, candidate.kinopoiskId)
    }

    @Test
    fun `keeps same episode number from different seasons`() {
        val candidate = KodikMovieParser.parseCandidates(
            """{"results":[{"type":"serial","seasons":{
                "1":{"episodes":{"1":{"link":"https://player.example/s1e1"}}},
                "2":{"episodes":{"1":"https://player.example/s2e1"}}
            }}]}"""
        ).single()
        assertEquals(listOf(1 to 1, 2 to 1), candidate.episodes.map { it.seasonNumber to it.episodeNumber })
    }

    @Test
    fun `agreeing imdb id outranks a conflicting kinopoisk id`() {
        // Kinopoisk carries duplicate cards for one film, so a kp mismatch alongside an IMDb match
        // means "the app opened the duplicate card", not "different film". IMDb wins.
        val request = MoviePlaybackRequest(123, "tt100", listOf("Movie"), 2024, MovieContentKind.MOVIE)
        val candidates = KodikMovieParser.parseCandidates(
            """{"results":[
                {"link":"https://player.example/dup-kp","kinopoisk_id":999,"imdb_id":"tt100","title":"Movie","year":2024,"type":"movie"}
            ]}"""
        )
        assertEquals(
            "https://player.example/dup-kp",
            KodikMovieParser.acceptedCandidates(request, candidates).single().topLevelPlayerUrl
        )
    }

    @Test
    fun `conflicting imdb id is still rejected outright`() {
        // A *disagreeing* IMDb id is genuine proof of a different film, unlike a kp disagreement.
        val request = MoviePlaybackRequest(123, "tt100", listOf("Movie"), 2024, MovieContentKind.MOVIE)
        val candidates = KodikMovieParser.parseCandidates(
            """{"results":[
                {"link":"https://player.example/wrong-imdb","kinopoisk_id":123,"imdb_id":"tt999","title":"Movie","year":2024,"type":"movie"}
            ]}"""
        )
        assertTrue(KodikMovieParser.acceptedCandidates(request, candidates).isEmpty())
    }

    @Test
    fun `strict matching accepts exact kp and rejects wrong year title fallback`() {
        val request = MoviePlaybackRequest(123, null, listOf("Michael"), 2025, MovieContentKind.MOVIE)
        val candidates = KodikMovieParser.parseCandidates(
            """{"results":[
                {"link":"https://player.example/homonym","title":"Michael","year":2011,"type":"movie"},
                {"link":"https://player.example/exact","kinopoisk_id":123,"title":"Other localized title","year":2025,"type":"movie"}
            ]}"""
        )
        assertEquals("https://player.example/exact", KodikMovieParser.acceptedCandidates(request, candidates).single().topLevelPlayerUrl)
    }

    @Test
    fun `title only without year and kind is rejected`() {
        val request = MoviePlaybackRequest(null, null, listOf("Michael"), 2025, MovieContentKind.MOVIE)
        val candidates = KodikMovieParser.parseCandidates(
            """{"results":[{"link":"https://player.example/unknown","title":"Michael"}]}"""
        )
        assertTrue(KodikMovieParser.acceptedCandidates(request, candidates).isEmpty())
    }

    @Test
    fun `year tolerance accepts one year difference`() {
        val request = MoviePlaybackRequest(null, null, listOf("Movie"), 2025, MovieContentKind.MOVIE)
        val candidate = KodikMovieParser.parseCandidates(
            """{"results":[{"link":"https://player.example/movie","title":"Movie","year":2024,"type":"foreign-movie"}]}"""
        )
        assertEquals(1, KodikMovieParser.acceptedCandidates(request, candidate).size)
    }

    @Test
    fun `exact series id establishes identity without expanded episodes`() {
        val request = MoviePlaybackRequest(123, null, listOf("Show"), 2024, MovieContentKind.SERIES)
        val candidate = KodikMovieParser.parseCandidates(
            """{"results":[{"link":"https://player.example/show","kinopoisk_id":123,"title":"Show","year":2024,"type":"serial"}]}"""
        )
        assertEquals(1, KodikMovieParser.acceptedCandidates(request, candidate).size)
    }

    @Test
    fun `rejects malformed and blank links`() {
        val candidates = KodikMovieParser.parseCandidates(
            """{"results":[{"link":""},{"link":"javascript:alert(1)"},{"link":"https://player.example/valid"}]}"""
        )
        assertEquals(1, candidates.size)
    }
}
