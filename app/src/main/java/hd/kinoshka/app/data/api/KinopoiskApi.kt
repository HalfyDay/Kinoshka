package hd.kinoshka.app.data.api

import hd.kinoshka.app.data.model.FilmDetails
import hd.kinoshka.app.data.model.FilmImagesResponse
import hd.kinoshka.app.data.model.FilmLinksResponse
import hd.kinoshka.app.data.model.FilmSeasonsResponse
import hd.kinoshka.app.data.model.FilmVideosResponse
import hd.kinoshka.app.data.model.FilmsResponse
import hd.kinoshka.app.data.model.FiltersResponse
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query

interface KinopoiskApi {
    @Headers("Content-Type: application/json")
    @GET("api/v2.2/films/collections")
    suspend fun popular(
        @Query("type") type: String = "TOP_POPULAR_ALL",
        @Query("page") page: Int = 1
    ): FilmsResponse

    @Headers("Content-Type: application/json")
    @GET("api/v2.2/films")
    suspend fun search(
        @Query("keyword") keyword: String? = null,
        @Query("countries") countries: Int? = null,
        @Query("genres") genres: Int? = null,
        @Query("order") order: String? = "RATING",
        @Query("type") type: String? = "ALL",
        @Query("ratingFrom") ratingFrom: Int? = null,
        @Query("ratingTo") ratingTo: Int? = null,
        @Query("yearFrom") yearFrom: Int? = null,
        @Query("yearTo") yearTo: Int? = null,
        @Query("page") page: Int = 1
    ): FilmsResponse

    @Headers("Content-Type: application/json")
    @GET("api/v2.2/films/filters")
    suspend fun filters(): FiltersResponse

    @Headers("Content-Type: application/json")
    @GET("api/v2.2/films/{id}")
    suspend fun details(@Path("id") id: Int): FilmDetails

    @Headers("Content-Type: application/json")
    @GET("api/v2.2/films/{id}/seasons")
    suspend fun seasons(@Path("id") id: Int): FilmSeasonsResponse

    @Headers("Content-Type: application/json")
    @GET("api/v2.2/films/{id}/similars")
    suspend fun similars(@Path("id") id: Int): FilmLinksResponse

    @Headers("Content-Type: application/json")
    @GET("api/v2.2/films/{id}/relations")
    suspend fun relations(@Path("id") id: Int): FilmLinksResponse

    @Headers("Content-Type: application/json")
    @GET("api/v2.2/films/{id}/images")
    suspend fun images(
        @Path("id") id: Int,
        @Query("type") type: String = "STILL",
        @Query("page") page: Int = 1
    ): FilmImagesResponse

    @Headers("Content-Type: application/json")
    @GET("api/v2.2/films/{id}/videos")
    suspend fun videos(@Path("id") id: Int): FilmVideosResponse
}


