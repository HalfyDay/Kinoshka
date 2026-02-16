package hd.kinoshka.app.data.api

import hd.kinoshka.app.data.model.FilmDetails
import hd.kinoshka.app.data.model.FilmImagesResponse
import hd.kinoshka.app.data.model.FilmLinksResponse
import hd.kinoshka.app.data.model.FilmSeasonsResponse
import hd.kinoshka.app.data.model.FilmsResponse
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
        @Query("keyword") keyword: String,
        @Query("page") page: Int = 1
    ): FilmsResponse

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
}


