package hd.kinoshka.app.data.model

import hd.kinoshka.app.data.local.UserFilmProfile
import hd.kinoshka.app.data.local.UserFilmStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class MovieSeriesPlaybackTest {
    private val episodes = listOf(
        MovieEpisodeRef(1, 1, playerUrl = "https://example.com/s1e1"),
        MovieEpisodeRef(1, 3, playerUrl = "https://example.com/s1e3"),
        MovieEpisodeRef(2, 1, playerUrl = "https://example.com/s2e1"),
        MovieEpisodeRef(2, 4, playerUrl = "https://example.com/s2e4")
    )

    @Test
    fun `no profile starts at season one episode one`() {
        assertEquals(1 to 1, selectInitialSeriesEpisode(episodes, null).pair())
    }

    @Test
    fun `profile selects exact season and episode`() {
        assertEquals(2 to 4, selectInitialSeriesEpisode(episodes, profile(2, 4)).pair())
    }

    @Test
    fun `missing episode selects nearest in requested season`() {
        assertEquals(1 to 3, selectInitialSeriesEpisode(episodes, profile(1, 4)).pair())
    }

    @Test
    fun `missing season falls back to season one episode one`() {
        assertEquals(1 to 1, selectInitialSeriesEpisode(episodes, profile(9, 2)).pair())
    }

    @Test
    fun `completed profile restarts at season one episode one`() {
        assertEquals(1 to 1, selectInitialSeriesEpisode(episodes, profile(2, 4, UserFilmStatus.COMPLETED)).pair())
    }

    @Test
    fun `series context preserves episodes across json round trip`() {
        val request = MoviePlaybackRequest(1, null, listOf("Show"), 2024, MovieContentKind.SERIES)
        val candidate = KodikMovieCandidate(0, 1, null, "Show", null, 2024, MovieContentKind.SERIES, null, null, null, episodes)
        val context = MovieSeriesPlaybackContext(request, listOf(candidate), episodes, episodes[2], 1, "Show")

        val restored = Json.decodeFromString<MovieSeriesPlaybackContext>(Json.encodeToString(context))

        assertEquals(1 to 1, restored.episodes[0].seasonNumber to restored.episodes[0].episodeNumber)
        assertEquals(2 to 1, restored.currentEpisode.seasonNumber to restored.currentEpisode.episodeNumber)
    }

    private fun profile(
        season: Int,
        episode: Int,
        status: UserFilmStatus = UserFilmStatus.WATCHING
    ) = UserFilmProfile(
        kinopoiskId = 1,
        title = "Show",
        subtitle = null,
        posterUrl = null,
        ratingText = null,
        type = "TV_SERIES",
        isRussian = false,
        status = status,
        userRating = null,
        note = null,
        watchedSeasons = season,
        watchedEpisodes = episode,
        totalEpisodesInSeason = null,
        totalSeasons = null,
        totalEpisodes = null,
        updatedAt = 0L
    )

    private fun MovieEpisodeRef?.pair() = this?.let { it.seasonNumber to it.episodeNumber }
}
