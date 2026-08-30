package hd.kinoshka.app.data.source

import hd.kinoshka.app.data.model.KodikMovieCandidate
import hd.kinoshka.app.data.model.MovieContentKind
import hd.kinoshka.app.data.model.MovieEpisodeRef
import hd.kinoshka.app.data.model.MoviePlaybackRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import java.net.URI
import kotlin.math.abs

object KodikMovieParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Which provider query produced a batch of results. ID-based queries are authoritative. */
    enum class MatchOrigin { KINOPOISK_ID, IMDB_ID, TITLE }

    fun parseCandidates(items: List<JSONObject>): List<KodikMovieCandidate> =
        items.mapIndexedNotNull { index, item -> parseCandidate(index, json.parseToJsonElement(item.toString()).jsonObject) }

    fun parseCandidates(source: String): List<KodikMovieCandidate> {
        val root = json.parseToJsonElement(source).jsonObject
        return root["results"]?.jsonArray.orEmpty().mapIndexedNotNull { index, item ->
            parseCandidate(index, item.jsonObject)
        }
    }

    fun acceptedCandidates(
        request: MoviePlaybackRequest,
        candidates: List<KodikMovieCandidate>,
        origin: MatchOrigin = MatchOrigin.TITLE
    ): List<KodikMovieCandidate> =
        rank(request, candidates.filter { matchesIdentity(request, it, origin) })

    fun rank(request: MoviePlaybackRequest, candidates: List<KodikMovieCandidate>): List<KodikMovieCandidate> =
        candidates.distinctBy(::candidateKey).sortedWith(
            compareByDescending<KodikMovieCandidate> { if (request.kinopoiskId != null && request.kinopoiskId == it.kinopoiskId) 1 else 0 }
                .thenByDescending { if (normalizeImdb(request.imdbId) != null && normalizeImdb(request.imdbId) == normalizeImdb(it.imdbId)) 1 else 0 }
                .thenByDescending { playabilityScore(request.kind, it) }
                .thenBy { it.sourceIndex }
        )

    fun normalizeTitle(value: String): String = value.lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    fun normalizeImdb(value: String?): String? {
        val cleaned = value?.trim()?.lowercase()?.removePrefix("tt") ?: return null
        return cleaned.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let { "tt$it" }
    }

    private fun matchesIdentity(
        request: MoviePlaybackRequest,
        candidate: KodikMovieCandidate,
        origin: MatchOrigin = MatchOrigin.TITLE
    ): Boolean {
        val requestImdb = normalizeImdb(request.imdbId)
        val candidateImdb = normalizeImdb(candidate.imdbId)

        // --- Positive identity by IMDb, checked FIRST. IMDb ids are globally canonical while
        // Kinopoisk publishes duplicate cards for the same film (e.g. "Семь самураев" exists as
        // both 332 and 522003), so a Kinopoisk mismatch must never outrank an IMDb agreement. ---
        if (requestImdb != null && requestImdb == candidateImdb) return true
        // Conflicting IMDb ids, on the other hand, do disprove identity outright.
        if (requestImdb != null && candidateImdb != null && requestImdb != candidateImdb) return false

        // --- Positive identity by Kinopoisk id, and by provenance. Evaluated BEFORE the soft
        // kind/year heuristics, because an external id is authoritative and Kodik's own
        // `type`/`year` are frequently wrong. ---
        if (request.kinopoiskId != null && request.kinopoiskId == candidate.kinopoiskId) return true

        // A search performed BY an external id returns only rows the provider itself already
        // joined to that id. Trust them even when the row omits the id in its payload.
        if (origin == MatchOrigin.KINOPOISK_ID || origin == MatchOrigin.IMDB_ID) return true

        // --- Title search fallback: heuristics apply only here. ---
        if (request.kind != MovieContentKind.UNKNOWN && candidate.kind != MovieContentKind.UNKNOWN && request.kind != candidate.kind) return false
        if (request.year != null && candidate.year != null && abs(request.year - candidate.year) > 1) return false

        val expectedTitles = request.titles.map(::normalizeTitle).filter(String::isNotBlank).toSet()
        val candidateTitles = listOfNotNull(candidate.title, candidate.originalTitle)
            .map(::normalizeTitle).filter(String::isNotBlank).toSet()
        if (expectedTitles.none(candidateTitles::contains)) return false

        // Require at least ONE corroborating signal (kind OR year) — never both, otherwise a
        // provider that omits `year` or a request without a year rejects every title match.
        val kindCorroborates = request.kind != MovieContentKind.UNKNOWN && candidate.kind == request.kind
        val yearCorroborates = request.year != null && candidate.year != null && abs(request.year - candidate.year) <= 1

        // Two *different* Kinopoisk ids are a weak counter-signal, not proof: duplicate cards are
        // common, and the duplicate is usually the one with no imdbId/year attached (exactly the
        // card whose lookup by id already failed and drove us into this title search). Demand the
        // stronger form of corroboration instead of vetoing.
        if (request.kinopoiskId != null && candidate.kinopoiskId != null && request.kinopoiskId != candidate.kinopoiskId) {
            return kindCorroborates && (request.year == null || candidate.year == null || yearCorroborates)
        }

        return kindCorroborates || yearCorroborates
    }

    private fun parseCandidate(index: Int, item: JsonObject): KodikMovieCandidate? {
        val material = item.objectOrNull("material_data")
        val translation = item.objectOrNull("translation")
        val topLevelUrl = sequenceOf("link", "player_url", "iframe_url")
            .map { item.string(it) }.mapNotNull(::normalizeUrl).firstOrNull()
        val episodes = parseEpisodes(item)
        if (topLevelUrl == null && episodes.isEmpty()) return null

        return KodikMovieCandidate(
            sourceIndex = index,
            kinopoiskId = item.firstInt(material, "kinopoisk_id", "kinopoiskId", "kinopoisk", "kp_id", "kpId"),
            imdbId = normalizeImdb(item.firstString(material, "imdb_id", "imdbId", "imdb")),
            title = item.firstString(material, "title", "title_ru", "name", "name_ru"),
            originalTitle = item.firstString(material, "title_orig", "title_original", "title_en", "original_title", "name_original", "name_orig"),
            year = item.firstInt(material, "year", "release_year"),
            kind = parseKind(item, material),
            translationId = translation?.string("id"),
            translationTitle = translation?.string("title"),
            topLevelPlayerUrl = topLevelUrl,
            episodes = episodes
        )
    }

    private fun parseEpisodes(item: JsonObject): List<MovieEpisodeRef> {
        val result = mutableListOf<MovieEpisodeRef>()
        item.objectOrNull("seasons")?.forEach { (seasonKey, seasonValue) ->
            val seasonNumber = seasonKey.toIntOrNull() ?: return@forEach
            parseEpisodeObject(seasonValue.asObject()?.objectOrNull("episodes"), seasonNumber, result)
        }
        parseEpisodeObject(item.objectOrNull("episodes"), 1, result)
        return result.distinctBy { Triple(it.seasonNumber, it.episodeNumber, it.playerUrl) }
            .sortedWith(compareBy(MovieEpisodeRef::seasonNumber, MovieEpisodeRef::episodeNumber))
    }

    private fun parseEpisodeObject(episodes: JsonObject?, seasonNumber: Int, target: MutableList<MovieEpisodeRef>) {
        episodes?.forEach { (key, value) ->
            val episodeNumber = key.toIntOrNull() ?: return@forEach
            val rawUrl = value.asObject()?.let { obj ->
                sequenceOf("link", "player_url", "iframe_url", "url").mapNotNull { obj.string(it) }.firstOrNull()
            } ?: (value as? JsonPrimitive)?.contentOrNull
            normalizeUrl(rawUrl)?.let { target += MovieEpisodeRef(seasonNumber, episodeNumber, "Серия $episodeNumber", it) }
        }
    }

    private fun parseKind(item: JsonObject, material: JsonObject?): MovieContentKind {
        val type = item.firstString(material, "type", "content_type", "type_name")
            .orEmpty().lowercase().replace('-', '_').replace(' ', '_')
        val serialFlag = item.firstString(material, "serial")?.lowercase() in setOf("true", "1", "yes")
        // Structure is a stronger signal than Kodik's label: a row carrying a season/episode map
        // is a series regardless of `type`, and "cartoon-serial" must not be read as a movie.
        val hasEpisodes = item.objectOrNull("seasons") != null || item.objectOrNull("episodes") != null
        return when {
            serialFlag || hasEpisodes || type.contains("serial") || type.contains("series") -> MovieContentKind.SERIES
            // Covers foreign-movie, russian-movie, cartoon-movie, anime-movie, documentary-movie,
            // plus the bare labels Kodik uses for one-off items: "anime", "*-cartoon", "video".
            type.contains("movie") || type.contains("film") || type.contains("cartoon") ||
                type == "video" || type == "anime" || type == "documentary" -> MovieContentKind.MOVIE
            else -> MovieContentKind.UNKNOWN
        }
    }

    private fun normalizeUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val normalized = if (value.startsWith("//")) "https:$value" else value
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) return null
        return runCatching { URI(normalized) }.getOrNull()?.takeIf { it.host != null }?.toString()
    }

    private fun candidateKey(candidate: KodikMovieCandidate): String = listOf(
        candidate.kinopoiskId, candidate.imdbId, normalizeTitle(candidate.title.orEmpty()),
        normalizeTitle(candidate.originalTitle.orEmpty()), candidate.year, candidate.kind,
        candidate.translationId, candidate.topLevelPlayerUrl,
        candidate.episodes.joinToString { "${it.seasonNumber}:${it.episodeNumber}:${it.playerUrl}" }
    ).joinToString("|")

    private fun playabilityScore(kind: MovieContentKind, candidate: KodikMovieCandidate): Int = when (kind) {
        MovieContentKind.MOVIE -> if (candidate.topLevelPlayerUrl != null) 2 else 0
        MovieContentKind.SERIES -> if (candidate.episodes.isNotEmpty()) 2 else 0
        MovieContentKind.UNKNOWN -> if (candidate.topLevelPlayerUrl != null || candidate.episodes.isNotEmpty()) 1 else 0
    }

    private fun JsonObject.firstString(fallback: JsonObject?, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { string(it) } ?: keys.firstNotNullOfOrNull { fallback?.string(it) }

    private fun JsonObject.firstInt(fallback: JsonObject?, vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { int(it) } ?: keys.firstNotNullOfOrNull { fallback?.int(it) }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

    private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.let { value ->
        (value.intOrNull ?: value.contentOrNull?.substringBefore('-')?.toIntOrNull())?.takeIf { it > 0 }
    }

    private fun JsonObject.objectOrNull(key: String): JsonObject? = get(key).asObject()
    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
}
