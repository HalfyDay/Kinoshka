package hd.kinoshka.app.data.api

import hd.kinoshka.app.data.model.ShikimoriAnimeDetails
import hd.kinoshka.app.data.model.ShikimoriAnimeItem
import hd.kinoshka.app.data.model.ShikimoriScreenshot
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface ShikimoriApi {
    @GET("api/animes")
    suspend fun popular(
        @Query("order") order: String = "popularity",
        @Query("limit") limit: Int = 20,
        @Query("page") page: Int = 1
    ): List<ShikimoriAnimeItem>

    @GET("api/animes")
    suspend fun search(
        @Query("search") search: String,
        @Query("order") order: String = "popularity",
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

    @GET("api/animes/{id}/roles")
    suspend fun roles(
        @Path("id") id: Int
    ): List<hd.kinoshka.app.data.model.ShikimoriRole>
}
