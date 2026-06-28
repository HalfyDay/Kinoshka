package hd.kinoshka.app.data.api

import hd.kinoshka.app.data.model.ShikimoriAnimeDetails
import hd.kinoshka.app.data.model.ShikimoriAnimeItem
import hd.kinoshka.app.data.model.ShikimoriCalendarItem
import hd.kinoshka.app.data.model.ShikimoriScreenshot
import hd.kinoshka.app.data.model.ShikimoriTopic
import hd.kinoshka.app.data.model.ShikimoriUserRate
import hd.kinoshka.app.data.model.ShikimoriWhoami
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
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
        @Query("user_rate[user_id]") userId: Int,
        @Query("user_rate[target_id]") targetId: Int,
        @Query("user_rate[target_type]") targetType: String = "Anime",
        @Query("user_rate[status]") status: String,
        @Query("user_rate[episodes]") episodes: Int = 0,
        @Query("user_rate[score]") score: Int = 0
    ): ShikimoriUserRate

    @PUT("api/v2/user_rates/{id}")
    suspend fun updateUserRate(
        @Header("Authorization") token: String,
        @Path("id") rateId: Int,
        @Query("user_rate[status]") status: String? = null,
        @Query("user_rate[episodes]") episodes: Int? = null,
        @Query("user_rate[score]") score: Int? = null
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
}
