package app.genkan.romm

import app.genkan.library.LibrarySync
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the real device-code login and a full library read against a live RomM server, then
 * revokes the token it made. Skipped unless these are set (never commit them):
 *
 *   ROMM_LIVE_URL=https://romm.example.com ROMM_LIVE_USER=... ROMM_LIVE_PASS=... \
 *     ./gradlew testDebugUnitTest --tests '*RomMLiveTest*'
 *
 * The user/password stand in for the person approving the code in RomM's web UI.
 */
class RomMLiveTest {

    private val url = System.getenv("ROMM_LIVE_URL")
    private val user = System.getenv("ROMM_LIVE_USER")
    private val pass = System.getenv("ROMM_LIVE_PASS")
    private val http = OkHttpClient()

    @Test
    fun `device login then a full library read`() = runBlocking {
        assumeTrue("ROMM_LIVE_* not set", url != null && user != null && pass != null)
        val baseUrl = RomMApiFactory.normalizeBaseUrl(url!!)
        val anonymous = RomMApiFactory.create(baseUrl, null, http)

        val version = anonymous.heartbeat().body()?.version
        assertTrue("RomM $version is too old", RomMCapabilities.from(version).supportsDeviceAuth)

        val init = anonymous.deviceAuthInit(
            RomMDeviceAuthInitRequest(
                clientDeviceIdentifier = "genkan-live-test",
                name = TOKEN_NAME,
                clientVersion = "test",
                requestedScopes = RomMSession.SCOPES,
            )
        ).body()!!
        approve(baseUrl, init.userCode)

        var deviceId: String? = null
        val outcome = pollDeviceAuthUntilResolved(1, init.expiresIn) {
            val response = anonymous.deviceAuthToken(RomMDeviceAuthTokenRequest(init.deviceCode))
            response.body()?.let { deviceId = it.deviceId; DeviceAuthPoll.Approved(it.accessToken) }
                ?: when (RomMSession.parseDetail(response.errorBody()?.string())) {
                    "authorization_pending" -> DeviceAuthPoll.Pending
                    "slow_down" -> DeviceAuthPoll.SlowDown
                    else -> DeviceAuthPoll.Failed("HTTP ${response.code()}", retryable = false)
                }
        }
        val token = (outcome as DeviceAuthOutcome.Approved).token
        try {
            assertTrue(token.startsWith("rmm_"))
            assertTrue(!deviceId.isNullOrBlank())

            val api = RomMApiFactory.create(baseUrl, token, http)
            assertEquals(user, api.getCurrentUser().body()!!.username)

            val platforms = api.getPlatforms().body()!!.filter { it.romCount > 0 }
            var listed = 0
            for (platform in platforms) {
                var offset = 0
                do {
                    val page = api.getRoms(LibrarySync.pageParams(platform.id, offset)).body()!!
                    page.items.map(LibrarySync::toEntity).forEach { assertEquals(platform.id, it.platformId) }
                    offset += page.items.size
                } while (page.items.size == LibrarySync.PAGE_SIZE)
                assertEquals("rom count for ${platform.slug}", platform.romCount, offset)
                listed += offset
            }
            println("Live RomM $version: ${platforms.size} platforms, $listed games")
        } finally {
            revokeTestTokens(baseUrl)
        }
    }

    private fun approve(baseUrl: String, userCode: String) {
        val body = """{"user_code":"$userCode","approved_scopes":${RomMSession.SCOPES.joinToString(",", "[", "]") { "\"$it\"" }}}"""
        basicAuth(Request.Builder().url(baseUrl + "api/auth/device/approve").post(body.toRequestBody(JSON))).use {
            assertTrue("approve failed: ${it.code} ${it.body.string()}", it.isSuccessful)
        }
    }

    /** Deletes the tokens and device this test created, so runs leave nothing on the server. */
    private fun revokeTestTokens(baseUrl: String) {
        for ((endpoint, idPattern) in listOf("client-tokens" to """(\d+)""", "devices" to """"([^"]+)"""")) {
            val list = basicAuth(Request.Builder().url(baseUrl + "api/$endpoint")).use { it.body.string() }
            Regex("""\{[^{}]*"id":\s*$idPattern[^{}]*"name":\s*"$TOKEN_NAME"""").findAll(list).forEach { match ->
                basicAuth(Request.Builder().url(baseUrl + "api/$endpoint/${match.groupValues[1]}").delete()).close()
            }
        }
    }

    private fun basicAuth(builder: Request.Builder) =
        http.newCall(builder.header("Authorization", Credentials.basic(user!!, pass!!)).build()).execute()

    private companion object {
        const val TOKEN_NAME = "Genkan live test"
        val JSON = "application/json".toMediaType()
    }
}
