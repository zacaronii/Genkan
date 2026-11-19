// Adapted from Argosy Launcher (GPL-3.0) @ 3480817: app/src/main/kotlin/com/nendo/argosy/data/remote/romm/RomMApi.kt
// Trimmed to the endpoints Genkan uses; saves, music, collections and streaming are left out.
package app.genkan.romm

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap
import retrofit2.http.Streaming

interface RomMApi {

    @GET("api/heartbeat")
    suspend fun heartbeat(): Response<RomMHeartbeatResponse>

    @POST("api/client-tokens/exchange")
    suspend fun exchangePairingCode(
        @Body body: RomMPairingExchangeRequest
    ): Response<RomMPairingExchangeResponse>

    @POST("api/auth/device/init")
    suspend fun deviceAuthInit(
        @Body body: RomMDeviceAuthInitRequest
    ): Response<RomMDeviceAuthInitResponse>

    @POST("api/auth/device/token")
    suspend fun deviceAuthToken(
        @Body body: RomMDeviceAuthTokenRequest
    ): Response<RomMDeviceAuthTokenResponse>

    @GET("api/users/me")
    suspend fun getCurrentUser(): Response<RomMUser>

    @GET("api/platforms")
    suspend fun getPlatforms(): Response<List<RomMPlatform>>

    @GET("api/roms")
    suspend fun getRoms(
        @QueryMap params: Map<String, String>
    ): Response<RomMRomPage>

    @GET("api/roms/{id}")
    suspend fun getRom(
        @Path("id") romId: Long
    ): Response<RomMRom>

    @Streaming
    @Headers("Accept-Encoding: identity")
    @GET("api/roms/{id}/content/{fileName}")
    fun downloadRomCall(
        @Path("id") romId: Long,
        @Path("fileName", encoded = true) fileName: String,
        @Header("Range") range: String? = null,
        @Query("file_ids") fileIds: String? = null,
        @Header("If-Range") ifRange: String? = null
    ): Call<ResponseBody>

    @POST("api/devices")
    suspend fun registerDevice(
        @Body body: RomMDeviceRegistration
    ): Response<RomMDeviceRegistrationResponse>

    @POST("api/play-sessions")
    suspend fun ingestPlaySessions(
        @Body body: RomMPlaySessionIngestPayload
    ): Response<RomMPlaySessionIngestResponse>

    /**
     * Sessions the server holds, scoped to the calling device unless [deviceId] names another
     * of the account's devices. Pages by [offset]; the server default limit is 50.
     */
    @GET("api/play-sessions")
    suspend fun getPlaySessions(
        @Query("rom_id") romId: Long? = null,
        @Query("device_id") deviceId: String? = null,
        @Query("start_after") startAfter: String? = null,
        @Query("end_before") endBefore: String? = null,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<List<RomMPlaySession>>
}
