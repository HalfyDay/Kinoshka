package hd.kinoshka.app.data.api

import hd.kinoshka.app.data.model.AnixartDefaultResponse
import hd.kinoshka.app.data.model.AnixartListResponse
import hd.kinoshka.app.data.model.AnixartLoginResponse
import hd.kinoshka.app.data.model.AnixartReleaseInfoResponse
import hd.kinoshka.app.data.model.AnixartSearchRequest
import hd.kinoshka.app.data.model.AnixartSearchResponse
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Неофициальный API Anixart (api.anixsekai.com, спека AniX-org v9, сверено
 * с AnixartJS): авторизация — query-параметр ?token=, а логин — POST
 * с urlEncoded-боди (login+password). Query-вариант сервер принимает с 200,
 * но отвечает кодом ошибки — плюс пароль светился в URL и logcat.
 * v1: только списки (статусы). Посерийный прогресс требует sourceId их
 * парсеров и сюда не входит.
 */
interface AnixartApi {

    @FormUrlEncoded
    @POST("auth/signIn")
    suspend fun signIn(
        @Field("login") login: String,
        @Field("password") password: String
    ): AnixartLoginResponse

    @GET("profile/list/all/{list}/{page}")
    suspend fun profileList(
        @Path("list") list: Int,
        @Path("page") page: Int,
        @Query("token") token: String
    ): AnixartListResponse

    @GET("profile/list/add/{list}/{id}")
    suspend fun addToList(
        @Path("list") list: Int,
        @Path("id") releaseId: Int,
        @Query("token") token: String
    ): AnixartDefaultResponse

    @GET("profile/list/delete/{list}/{id}")
    suspend fun deleteFromList(
        @Path("list") list: Int,
        @Path("id") releaseId: Int,
        @Query("token") token: String
    ): AnixartDefaultResponse

    /**
     * Полный объект релиза (включая profile_list_status юзера) — точечная сверка
     * карточки и verify перед записью без полного пула всех списков.
     */
    @GET("release/{id}")
    suspend fun releaseInfo(
        @Path("id") releaseId: Int,
        @Query("token") token: String
    ): AnixartReleaseInfoResponse

    /**
     * Поиск по каталогу релизов (v2 — заголовок Api-Version обязателен, сверено
     * живьём: без него зеркало отвечает 404). Токен опционален — поиск работает
     * и без него, но с токеном ответы персонализированы. Тело — JSON {query, searchBy}.
     */
    @POST("search/releases/{page}")
    suspend fun searchReleases(
        @Path("page") page: Int,
        @Body body: AnixartSearchRequest,
        @Header("Api-Version") apiVersion: String = "v2",
        @Query("token") token: String? = null
    ): AnixartSearchResponse
}
