package hd.kinoshka.app.data.api

import hd.kinoshka.app.data.model.ShikimoriAnimeDetails
import hd.kinoshka.app.data.model.ShikimoriAnimeItem
import hd.kinoshka.app.data.model.ShikimoriCalendarItem
import hd.kinoshka.app.data.model.ShikimoriScreenshot
import hd.kinoshka.app.data.model.ShikimoriTokenResponse
import hd.kinoshka.app.data.model.ShikimoriTopic
import hd.kinoshka.app.data.model.ShikimoriUserRate
import hd.kinoshka.app.data.model.ShikimoriWhoami
import hd.kinoshka.app.data.model.UserRateRequest
import hd.kinoshka.app.data.model.UserRateUpdateRequest
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ShikimoriApi {
    @GET("api/animes")
    suspend fun search(
        @Query("search") search: String? = null,
        @Query("order") order: String? = "popularity",
        @Query("kind") kind: String? = null,
        @Query("status") status: String? = null,
        @Query("season") season: String? = null,
        @Query("score") score: Int? = null,
        @Query("rating") rating: String? = null,
        @Query("genre") genre: String? = null,
        @Query("censored") censored: Boolean? = null,
        @Query("limit") limit: Int = 20,
        @Query("page") page: Int = 1
    ): List<ShikimoriAnimeItem>

    @GET("api/animes/{id}")
    suspend fun details(
        @Path("id") id: Int
    ): ShikimoriAnimeDetails

    @GET("api/animes/{id}/screenshots")
    suspend fun screenshots(
        @Path("id") id: Int
    ): List<ShikimoriScreenshot>

    @GET("api/animes/{id}/related")
    suspend fun related(
        @Path("id") id: Int
    ): List<hd.kinoshka.app.data.model.ShikimoriRelatedItem>

    @GET("api/animes/{id}/franchise")
    suspend fun franchise(
        @Path("id") id: Int
    ): hd.kinoshka.app.data.model.ShikimoriFranchiseResponse

    @GET("api/animes/{id}/roles")
    suspend fun roles(
        @Path("id") id: Int
    ): List<hd.kinoshka.app.data.model.ShikimoriRole>

    @GET("api/characters/{id}")
    suspend fun getCharacter(
        @Path("id") id: Int
    ): hd.kinoshka.app.data.model.ShikimoriCharacterDetails

    @GET("api/users/whoami")
    suspend fun whoami(
        @Header("Authorization") token: String
    ): ShikimoriWhoami

    @GET("api/users/{id}/anime_rates")
    suspend fun getUserAnimeRates(
        @Path("id") userId: Int,
        @Query("limit") limit: Int = 5000,
        @Query("status") status: String? = null
    ): List<ShikimoriUserRate>

    @GET("api/v2/user_rates")
    suspend fun getUserRates(
        @Query("user_id") userId: Int,
        @Query("target_type") targetType: String = "Anime",
        @Query("limit") limit: Int = 5000
    ): List<ShikimoriUserRate>

    @POST("api/v2/user_rates")
    suspend fun createUserRate(
        @Header("Authorization") token: String,
        @Body userRate: UserRateRequest
    ): ShikimoriUserRate

    @PATCH("api/v2/user_rates/{id}")
    suspend fun updateUserRate(
        @Header("Authorization") token: String,
        @Path("id") rateId: Int,
        @Body userRate: UserRateUpdateRequest
    ): ShikimoriUserRate

    @DELETE("api/v2/user_rates/{id}")
    suspend fun deleteUserRate(
        @Header("Authorization") token: String,
        @Path("id") rateId: Int
    )

    @GET("api/calendar")
    suspend fun calendar(): List<ShikimoriCalendarItem>

    @GET("api/topics")
    suspend fun topics(
        @Query("forum") forum: String = "news",
        @Query("limit") limit: Int = 30
    ): List<ShikimoriTopic>

    @FormUrlEncoded
    @POST("oauth/token")
    suspend fun refreshToken(
        @Field("grant_type") grantType: String = "refresh_token",
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("refresh_token") refreshToken: String
    ): ShikimoriTokenResponse

    @FormUrlEncoded
    @POST("oauth/token")
    suspend fun exchangeCodeForToken(
        @Field("grant_type") grantType: String = "authorization_code",
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("code") code: String,
        @Field("redirect_uri") redirectUri: String = "urn:ietf:wg:oauth:2.0:oob"
    ): ShikimoriTokenResponse
}
