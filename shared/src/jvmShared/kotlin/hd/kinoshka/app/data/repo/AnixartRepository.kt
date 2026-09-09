package hd.kinoshka.app.data.repo

import hd.kinoshka.app.data.api.AnixartApi
import hd.kinoshka.app.data.local.UserFilmStatus
import hd.kinoshka.app.data.model.AnixartLists
import hd.kinoshka.app.data.model.AnixartRelease
import hd.kinoshka.app.util.log.KLog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore

/**
 * Списки Anixart: вход по логину+паролю, чтение списков, перенос между ними.
 * Посерийного прогресса тут нет (нужен sourceId их парсеров) — только статусы.
 */
class AnixartRepository(private val api: AnixartApi) {

    data class Session(val token: String, val userId: Int, val nickname: String?)

    /** code 0 — успех, 2 — неверный логин, 3 — неверный пароль. */
    suspend fun signIn(login: String, password: String): Result<Session> = runCatching {
        val resp = api.signIn(login.trim(), password)
        val token = resp.profileToken?.token.takeUnless { it.isNullOrBlank() }
        // Без секретов: только код и наличие полей — достаточно для диагностики формата.
        KLog.d(
            "AnixartSync",
            "signIn: code=${resp.code} hasToken=${token != null} hasProfile=${resp.profile != null}"
        )
        when {
            resp.code == 2 -> error("Неверный логин")
            resp.code == 3 -> error("Неверный пароль")
            resp.code != 0 || token == null -> error("Вход не удался (код ${resp.code})")
        }
        Session(token!!, resp.profile?.id ?: 0, resp.profile?.login)
    }

    /** Шаг 1 регистрации: код на почту. Успех — hash для шага verify. */
    suspend fun signUp(login: String, email: String, password: String): Result<String> = runCatching {
        val cleanLogin = login.trim()
        val cleanEmail = email.trim()
        val resp = api.signUp(cleanLogin, cleanLogin, cleanEmail, password)
        KLog.d("AnixartSync", "signUp: code=${resp.code} hasHash=${!resp.hash.isNullOrBlank()}")
        when (resp.code) {
            2 -> error("Некорректный логин")
            3 -> error("Некорректный email")
            4 -> error("Некорректный пароль")
            5 -> error("Логин уже занят")
            6 -> error("Email уже занят")
            7 -> error("Код уже отправлен — проверьте почту")
            8 -> error("Не удалось отправить код")
            9 -> error("Этот почтовый сервис запрещён — используйте другую почту")
            10 -> error("Слишком много регистраций — попробуйте позже")
            0 -> resp.hash.takeUnless { it.isNullOrBlank() } ?: error("Сервер не вернул hash")
            else -> error("Регистрация не удалась (код ${resp.code})")
        }
    }

    /** Шаг 2 регистрации: код из письма — сразу возвращает сессию (автовход). */
    suspend fun verifySignUp(
        login: String,
        email: String,
        password: String,
        hash: String,
        code: String
    ): Result<Session> = runCatching {
        val cleanLogin = login.trim()
        val resp = api.verifySignUp(cleanLogin, cleanLogin, email.trim(), password, hash, code.trim())
        val token = resp.profileToken?.token.takeUnless { it.isNullOrBlank() }
        KLog.d("AnixartSync", "verifySignUp: code=${resp.code} hasToken=${token != null}")
        when (resp.code) {
            2 -> error("Некорректный логин")
            3 -> error("Некорректный email")
            4 -> error("Некорректный пароль")
            5 -> error("Логин уже занят")
            6 -> error("Email уже занят")
            7 -> error("Код уже отправлен — проверьте почту")
            8 -> error("Не удалось отправить код")
            9 -> error("Код устарел — запросите новый")
            10 -> error("Этот почтовый сервис запрещён — используйте другую почту")
            11 -> error("Слишком много регистраций — попробуйте позже")
            0 -> Unit
            else -> error("Подтверждение не удалось (код ${resp.code})")
        }
        if (token == null) error("Подтверждение не удалось (код ${resp.code})")
        Session(token!!, resp.profile?.id ?: 0, resp.profile?.login)
    }

    /** Шаг 1 восстановления: код на почту по логину. Успех — hash для шага verify. */
    suspend fun restore(login: String): Result<String> = runCatching {
        val cleanLogin = login.trim()
        val resp = api.restore(cleanLogin, cleanLogin)
        KLog.d("AnixartSync", "restore: code=${resp.code} hasHash=${!resp.hash.isNullOrBlank()}")
        when (resp.code) {
            2 -> error("Профиль не найден")
            3 -> error("Код уже отправлен — проверьте почту")
            4 -> error("Не удалось отправить код")
            0 -> resp.hash.takeUnless { it.isNullOrBlank() } ?: error("Сервер не вернул hash")
            else -> error("Не удалось отправить код (${resp.code})")
        }
    }

    /** Шаг 2 восстановления: код + новый пароль — сразу возвращает сессию (автовход). */
    suspend fun verifyRestore(
        login: String,
        newPassword: String,
        hash: String,
        code: String
    ): Result<Session> = runCatching {
        val cleanLogin = login.trim()
        val resp = api.verifyRestore(cleanLogin, cleanLogin, newPassword, hash, code.trim())
        val token = resp.profileToken?.token.takeUnless { it.isNullOrBlank() }
        KLog.d("AnixartSync", "verifyRestore: code=${resp.code} hasToken=${token != null}")
        when (resp.code) {
            2 -> error("Профиль не найден")
            3 -> error("Некорректный пароль")
            4 -> error("Неверный код")
            5 -> error("Код истёк — запросите новый")
            6 -> error("Сессия устарела — запросите код заново")
            0 -> Unit
            else -> error("Смена пароля не удалась (код ${resp.code})")
        }
        if (token == null) error("Смена пароля не удалась (код ${resp.code})")
        Session(token!!, resp.profile?.id ?: 0, resp.profile?.login)
    }

    /** Все 5 списков разом (постранично, с потолком — защита от гонок пагинации). */
    suspend fun getLists(token: String): Result<Map<Int, List<AnixartRelease>>> = runCatching {
        val lists = listOf(
            AnixartLists.WATCHING,
            AnixartLists.PLANNED,
            AnixartLists.COMPLETED,
            AnixartLists.ON_HOLD,
            AnixartLists.DROPPED
        )
        lists.associateWith { list -> fetchList(token, list).distinctBy { it.id } }
    }

    /**
     * Постраничный список: нулевая страница — синхронно (признак конца и размер),
     * дальше — пачками по [PARALLEL_PAGES] (зеркало быстрое, лимита как у Shikimori нет).
     * Короткая страница или ошибка — конец. total_count не доверяем (врёт) — идём пока полно.
     */
    private suspend fun fetchList(token: String, list: Int): List<AnixartRelease> = coroutineScope {
        val out = mutableListOf<AnixartRelease>()
        val first = runCatching { api.profileList(list, 0, token) }.getOrNull()
        if (first == null || first.code != 0) return@coroutineScope emptyList()
        val raw0 = first.content.orEmpty()
        out += raw0.filter { it.id > 0 }
        if (raw0.size < ASSUMED_PAGE_SIZE) return@coroutineScope out.distinctBy { it.id }
        val semaphore = Semaphore(PARALLEL_PAGES)
        var page = 1
        while (page < MAX_LIST_PAGES) {
            val batch = (page until minOf(page + PARALLEL_PAGES, MAX_LIST_PAGES)).toList()
            val chunks = batch.map { p ->
                async {
                    semaphore.acquire()
                    try {
                        runCatching { api.profileList(list, p, token) }.getOrNull()
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll()
            var allFull = true
            for (resp in chunks) {
                val chunk = if (resp != null && resp.code == 0) resp.content.orEmpty() else emptyList()
                out += chunk.filter { it.id > 0 }
                if (chunk.size < ASSUMED_PAGE_SIZE) allFull = false
            }
            if (!allFull) break
            page += batch.size
        }
        if (page >= MAX_LIST_PAGES) {
            KLog.w("AnixartSync", "list $list hit page cap ($MAX_LIST_PAGES), tail truncated")
        }
        out.distinctBy { it.id }
    }

    suspend fun addToList(token: String, list: Int, releaseId: Int): Boolean =
        runCatching { api.addToList(list, releaseId, token).code == 0 }.getOrDefault(false)

    suspend fun removeFromList(token: String, list: Int, releaseId: Int): Boolean =
        runCatching { api.deleteFromList(list, releaseId, token).code == 0 }.getOrDefault(false)

    /** Исход резолюции релиза в каталоге: id + флаг «поиск отработал» (а не сеть легла). */
    data class ResolveOutcome(
        val releaseId: Int?,
        val searchedOk: Boolean,
        /** Точное совпало, но год не сошёлся: не мемоизируем (как сеть — повторить позже). */
        val yearVetoed: Boolean = false
    )

    /**
     * Резолюция id релиза Anixart по названиям-кандидатам (для пуша тайтлов, которых
     * нет в списках юзера). Только ТОЧНЫЙ нормализованный матч любого поля
     * (ru/original/en) любому кандидату: пуш пишет в чужой аккаунт, нечёткое
     * совпадение может положить туда чужое аниме. Первая страница поиска, до 3
     * кандидатов. searchedOk=false — сеть/код, повторить позже (не мемоизировать).
     * [expectedYear] (год выхода с Shikimori): точный с чужим годом пропускаем
     * и ищем дальше — сезоны под одним названием иначе схлопываются.
     */
    suspend fun findReleaseId(
        token: String?,
        titles: List<String>,
        expectedYear: Int? = null
    ): ResolveOutcome {
        val candidates = titles.mapNotNull { it.trim().takeIf { t -> t.isNotEmpty() } }.distinct().take(3)
        if (candidates.isEmpty()) return ResolveOutcome(null, true)
        val normed = candidates.map { hd.kinoshka.app.data.source.TitleMatching.normalizeTitle(it) }
            .filter { it.isNotEmpty() }
        if (normed.isEmpty()) return ResolveOutcome(null, true)
        var searchedOk = false
        for (query in candidates) {
            val resp = runCatching {
                api.searchReleases(
                    page = 0,
                    body = hd.kinoshka.app.data.model.AnixartSearchRequest(query = query),
                    token = token
                )
            }.getOrNull() ?: continue
            if (resp.code != 0) continue
            searchedOk = true
            val hits = resp.releases.orEmpty()
            if (hits.isEmpty()) continue
            var vetoed = false
            for (release in hits) {
                if (release.id <= 0) continue
                val releaseTitles = listOfNotNull(release.titleRu, release.titleOriginal, release.titleEn)
                    .map { hd.kinoshka.app.data.source.TitleMatching.normalizeTitle(it) }
                if (releaseTitles.any { it.isNotEmpty() && it in normed }) {
                    val releaseYear = release.releaseYear()
                    if (expectedYear != null && releaseYear != null && releaseYear != expectedYear) {
                        vetoed = true
                        continue
                    }
                    return ResolveOutcome(release.id, true)
                }
            }
            if (vetoed) return ResolveOutcome(null, true, yearVetoed = true)
        }
        return ResolveOutcome(null, searchedOk)
    }

    /**
     * Полный объект релиза (включая profile_list_status и shikimori_id) либо null
     * (сеть/код): вызывающая сторона пропускает решение, а не гадает.
     */
    suspend fun releaseInfo(token: String, releaseId: Int): AnixartRelease? {
        val resp = runCatching { api.releaseInfo(releaseId, token) }.getOrNull() ?: return null
        if (resp.code != 0 || resp.release == null) return null
        return resp.release
    }

    /**
     * Точечный статус релиза в списках юзера (номер списка 1-5, 0 — ни в одном).
     * null — неизвестно (сеть/код): вызывающая сторона пропускает решение, а не гадает.
     */
    suspend fun releaseListStatus(token: String, releaseId: Int): Int? =
        releaseInfo(token, releaseId)?.profileListStatus

    companion object {
        private const val MAX_LIST_PAGES = 40
        private const val ASSUMED_PAGE_SIZE = 25
        // Зеркало без жёстких лимитов (в отличие от Shikimori 5rps): самый толстый
        // список (490 тайтлов = 20 страниц) качался ~2 c тройками — пятёрками ~1 c.
        private const val PARALLEL_PAGES = 5
    }
}

/** Статус Anixart-списка -> наш статус (пересмотра у них нет — это Watching). */
fun anixartListToStatus(list: Int): UserFilmStatus? = when (list) {
    AnixartLists.WATCHING -> UserFilmStatus.WATCHING
    AnixartLists.PLANNED -> UserFilmStatus.PLANNED
    AnixartLists.COMPLETED -> UserFilmStatus.COMPLETED
    AnixartLists.ON_HOLD -> UserFilmStatus.ON_HOLD
    AnixartLists.DROPPED -> UserFilmStatus.DROPPED
    else -> null
}

/** Наш статус -> список Anixart (пересмотр уезжает в Смотрю). */
fun UserFilmStatus.toAnixartList(): Int = when (this) {
    UserFilmStatus.WATCHING -> AnixartLists.WATCHING
    UserFilmStatus.PLANNED -> AnixartLists.PLANNED
    UserFilmStatus.COMPLETED -> AnixartLists.COMPLETED
    UserFilmStatus.REWATCHING -> AnixartLists.WATCHING
    UserFilmStatus.ON_HOLD -> AnixartLists.ON_HOLD
    UserFilmStatus.DROPPED -> AnixartLists.DROPPED
}
