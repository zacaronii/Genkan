package app.genkan.romm

import android.os.Build
import android.util.Log
import app.genkan.BuildConfig
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers

sealed class RomMState {
    data object Loading : RomMState()
    data object SignedOut : RomMState()
    data object Connecting : RomMState()
    data class AwaitingApproval(val userCode: String, val verificationUrl: String) : RomMState()
    data class SignedIn(val account: RomMAccount) : RomMState()
    data class Failed(val message: String) : RomMState()
}

/** Owns the RomM sign-in: both login flows, the stored account, and the authenticated API. */
@Singleton
class RomMSession @Inject constructor(
    private val store: RomMAccountStore,
    private val httpClient: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<RomMState>(RomMState.Loading)
    val state: StateFlow<RomMState> = _state.asStateFlow()

    @Volatile private var account: RomMAccount? = null
    @Volatile private var api: RomMApi? = null
    private var loginJob: Job? = null

    init {
        scope.launch {
            val saved = store.current()
            activate(saved)
        }
    }

    /** Base URL and token for same-origin image requests; null while signed out. */
    fun credentials(): Pair<String, String>? = account?.let { it.baseUrl to it.token }

    fun api(): RomMApi? = api

    fun currentAccount(): RomMAccount? = account

    /**
     * Device-code login (RFC 8628): show [RomMState.AwaitingApproval]'s code, the user approves it
     * in the RomM web UI, and the poll loop picks up the token.
     */
    fun startDeviceLogin(serverInput: String) {
        loginJob?.cancel()
        loginJob = scope.launch {
            _state.value = RomMState.Connecting
            val baseUrl = RomMApiFactory.normalizeBaseUrl(serverInput)
            val anonymous = RomMApiFactory.create(baseUrl, null, httpClient)
            val capabilities = probe(anonymous) ?: return@launch
            if (!capabilities.supportsDeviceAuth) {
                fail("RomM ${capabilities.serverVersion} has no device login; use a pairing code")
                return@launch
            }
            val init = try {
                anonymous.deviceAuthInit(
                    RomMDeviceAuthInitRequest(
                        clientDeviceIdentifier = store.clientDeviceIdentifier(),
                        name = "Genkan on ${Build.MODEL}",
                        clientVersion = BuildConfig.VERSION_NAME,
                        requestedScopes = SCOPES,
                    )
                )
            } catch (e: Exception) {
                fail(e.message ?: "Could not reach $baseUrl"); return@launch
            }
            val body = init.body()
            if (!init.isSuccessful || body == null) {
                fail(if (init.code() == 429) "Too many attempts, try again later" else "Login failed (${init.code()})")
                return@launch
            }
            _state.value = RomMState.AwaitingApproval(
                userCode = body.userCode,
                verificationUrl = RomMApiFactory.assetUrl(baseUrl, body.verificationPathComplete) ?: baseUrl,
            )
            var deviceId: String? = null
            val outcome = pollDeviceAuthUntilResolved(body.interval, body.expiresIn) {
                pollOnce(anonymous, body.deviceCode) { deviceId = it }
            }
            when (outcome) {
                is DeviceAuthOutcome.Approved -> finishLogin(baseUrl, outcome.token, deviceId, capabilities)
                DeviceAuthOutcome.Denied -> fail("Login was denied in RomM")
                DeviceAuthOutcome.Expired -> fail("The code expired; try again")
                is DeviceAuthOutcome.Failed -> fail(outcome.message)
            }
        }
    }

    /** Pairing-code login: an 8-digit code from RomM's "Client API tokens" page. */
    fun loginWithPairingCode(serverInput: String, code: String) {
        loginJob?.cancel()
        loginJob = scope.launch {
            _state.value = RomMState.Connecting
            val baseUrl = RomMApiFactory.normalizeBaseUrl(serverInput)
            val anonymous = RomMApiFactory.create(baseUrl, null, httpClient)
            val capabilities = probe(anonymous) ?: return@launch
            val response = try {
                anonymous.exchangePairingCode(RomMPairingExchangeRequest(code.filter(Char::isDigit)))
            } catch (e: Exception) {
                fail(e.message ?: "Could not reach $baseUrl"); return@launch
            }
            val body = response.body()
            if (!response.isSuccessful || body == null) {
                fail("That code was not accepted (${response.code()})"); return@launch
            }
            val deviceId = registerDevice(RomMApiFactory.create(baseUrl, body.rawToken, httpClient))
            finishLogin(baseUrl, body.rawToken, deviceId, capabilities)
        }
    }

    fun cancelLogin() {
        loginJob?.cancel()
        _state.value = RomMState.SignedOut
    }

    /**
     * RomM answered 401 to a signed-in request: the token was revoked or expired on the server.
     * Forget it and send the user back to the login screen with the reason.
     */
    fun onUnauthorized() {
        if (account == null) return
        loginJob?.cancel()
        scope.launch {
            store.clear()
            account = null
            api = null
            fail("RomM no longer accepts this device's sign-in. Sign in again.")
        }
    }

    fun signOut() {
        loginJob?.cancel()
        scope.launch {
            store.clear()
            activate(null)
        }
    }

    private suspend fun probe(anonymous: RomMApi): RomMCapabilities? {
        val heartbeat = try {
            anonymous.heartbeat().body()
        } catch (e: Exception) {
            fail(e.message ?: "Could not reach the server"); return null
        }
        val capabilities = RomMCapabilities.from(heartbeat?.version)
        if (!capabilities.isSupportedVersion) {
            fail("Genkan needs RomM ${RomMCapabilities.MIN_SUPPORTED_VERSION} or newer (found ${heartbeat?.version ?: "none"})")
            return null
        }
        return capabilities
    }

    private suspend fun pollOnce(api: RomMApi, deviceCode: String, onDeviceId: (String) -> Unit): DeviceAuthPoll = try {
        val response = api.deviceAuthToken(RomMDeviceAuthTokenRequest(deviceCode))
        val body = response.body()
        if (response.isSuccessful && body != null) {
            onDeviceId(body.deviceId)
            DeviceAuthPoll.Approved(body.accessToken)
        } else {
            when (parseDetail(response.errorBody()?.string())) {
                "authorization_pending" -> DeviceAuthPoll.Pending
                "slow_down" -> DeviceAuthPoll.SlowDown
                "access_denied" -> DeviceAuthPoll.Denied
                "expired_token" -> DeviceAuthPoll.Expired
                else -> DeviceAuthPoll.Failed("Login failed (${response.code()})")
            }
        }
    } catch (e: Exception) {
        DeviceAuthPoll.Failed(e.message ?: "Connection failed")
    }

    private suspend fun registerDevice(api: RomMApi): String? = try {
        api.registerDevice(
            RomMDeviceRegistration(name = "Genkan on ${Build.MODEL}", clientVersion = BuildConfig.VERSION_NAME)
        ).body()?.deviceId
    } catch (e: Exception) {
        Log.w(TAG, "Device registration failed: ${e.message}")
        null
    }

    private suspend fun finishLogin(baseUrl: String, token: String, deviceId: String?, capabilities: RomMCapabilities) {
        val authed = RomMApiFactory.create(baseUrl, token, httpClient)
        val user = try {
            authed.getCurrentUser().body()
        } catch (e: Exception) {
            null
        }
        if (user == null) {
            fail("Signed in, but RomM did not return the user"); return
        }
        val account = RomMAccount(
            baseUrl = baseUrl,
            token = token,
            deviceId = deviceId,
            userId = user.id,
            username = user.username,
            serverVersion = capabilities.serverVersion,
        )
        store.save(account)
        activate(account)
        Log.i(TAG, "Signed in to $baseUrl as ${user.username}, device $deviceId")
    }

    private fun activate(saved: RomMAccount?) {
        account = saved
        api = saved?.let { RomMApiFactory.create(it.baseUrl, it.token, httpClient) }
        _state.value = saved?.let { RomMState.SignedIn(it) } ?: RomMState.SignedOut
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        _state.value = RomMState.Failed(message)
    }

    companion object {
        private const val TAG = "RomMSession"

        /** What Genkan needs: browse and download, covers, its device record, play sessions. */
        val SCOPES = listOf(
            "me.read", "roms.read", "platforms.read", "assets.read", "assets.write",
            "devices.read", "devices.write", "roms.user.read", "roms.user.write",
        )

        private val detailAdapter = Moshi.Builder().build().adapter(RomMDetailResponse::class.java)

        /** RFC 8628 error codes arrive as {"detail": "authorization_pending"}. */
        fun parseDetail(body: String?): String? = try {
            body?.let { detailAdapter.fromJson(it)?.detail }
        } catch (_: Exception) {
            null
        }
    }
}
