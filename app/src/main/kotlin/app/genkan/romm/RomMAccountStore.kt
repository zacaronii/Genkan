package app.genkan.romm

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The signed-in RomM account. [token] is only ever held in memory decrypted. */
data class RomMAccount(
    val baseUrl: String,
    val token: String,
    val deviceId: String?,
    val userId: Long,
    val username: String,
    val serverVersion: String,
)

private val Context.rommStore: DataStore<Preferences> by preferencesDataStore(name = "romm_account")

@Singleton
class RomMAccountStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cipher: TokenCipher = KeystoreTokenCipher()

    val account: Flow<RomMAccount?> = context.rommStore.data.map { it.toAccount() }

    suspend fun current(): RomMAccount? = account.first()

    suspend fun save(account: RomMAccount) {
        context.rommStore.edit {
            it[BASE_URL] = account.baseUrl
            it[TOKEN] = cipher.encrypt(account.token)
            account.deviceId?.let { id -> it[DEVICE_ID] = id } ?: it.remove(DEVICE_ID)
            it[USER_ID] = account.userId
            it[USERNAME] = account.username
            it[SERVER_VERSION] = account.serverVersion
        }
    }

    suspend fun clear() {
        context.rommStore.edit {
            for (key in listOf(BASE_URL, TOKEN, DEVICE_ID, USERNAME, SERVER_VERSION)) it.remove(key)
            it.remove(USER_ID)
        }
    }

    /**
     * Stable per-install identifier sent as the device-code flow's client_device_identifier,
     * so signing in again reuses the same RomM device instead of piling up new ones.
     */
    suspend fun clientDeviceIdentifier(): String {
        context.rommStore.data.first()[CLIENT_ID]?.let { return it }
        val id = UUID.randomUUID().toString()
        context.rommStore.edit { it[CLIENT_ID] = id }
        return id
    }

    private fun Preferences.toAccount(): RomMAccount? {
        val baseUrl = this[BASE_URL] ?: return null
        val token = this[TOKEN]?.let(cipher::decrypt) ?: return null
        return RomMAccount(
            baseUrl = baseUrl,
            token = token,
            deviceId = this[DEVICE_ID],
            userId = this[USER_ID] ?: 0,
            username = this[USERNAME] ?: "",
            serverVersion = this[SERVER_VERSION] ?: "",
        )
    }

    private companion object {
        val BASE_URL = stringPreferencesKey("base_url")
        val TOKEN = stringPreferencesKey("token")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val USER_ID = longPreferencesKey("user_id")
        val USERNAME = stringPreferencesKey("username")
        val SERVER_VERSION = stringPreferencesKey("server_version")
        val CLIENT_ID = stringPreferencesKey("client_device_identifier")
    }
}
