package hd.kinoshka.app.data.api

import hd.kinoshka.app.data.model.AnixartDefaultResponse
import hd.kinoshka.app.data.model.AnixartListResponse
import hd.kinoshka.app.data.model.AnixartLoginResponse
import hd.kinoshka.app.data.model.AnixartSignUpResponse
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

    /**
     * Регистрация (сверено с AnixartJS 0.1.8): сервер шлёт код на почту,
     * в ответе hash для шага verify. Поле логина дублируем (login+username):
     * старые спеки ждут login, типы AnixartJS — username.
     */
    @FormUrlEncoded
    @POST("auth/signUp")
    suspend fun signUp(
        @Field("login") login: String,
        @Field("username") username: String,
        @Field("email") email: String,
        @Field("password") password: String
    ): AnixartSignUpResponse

    /** Подтверждение регистрации кодом из письма — возвращает профиль+токен. */
    @FormUrlEncoded
    @POST("auth/verify")
    suspend fun verifySignUp(
        @Field("login") login: String,
        @Field("username") username: String,
        @Field("email") email: String,
        @Field("password") password: String,
        @Field("hash") hash: String,
        @Field("code") code: String
    ): AnixartLoginResponse

    /**
     * Восстановление пароля (AnixartJS): шаг 1 — код на почту по логину.
     * Поле зовётся data, дублируем login для совместимости со спеками.
     */
    @FormUrlEncoded
    @POST("auth/restore")
    suspend fun restore(
        @Field("data") data: String,
        @Field("login") login: String
    ): AnixartSignUpResponse

    /** Шаг 2 восстановления: код + новый пароль — возвращает профиль+токен. */
    @FormUrlEncoded
    @POST("auth/restore/verify")
    suspend fun verifyRestore(
        @Field("data") data: String,
        @Field("login") login: String,
        @Field("password") password: String,
        @Field("hash") hash: String,
        @Field("code") code: String
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
