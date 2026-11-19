package app.genkan.privileged

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import app.genkan.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shizuku: user-granted, adb-level access for the few places Android keeps from Genkan, mainly
 * emulators' saves in their own Android/data folders. Shizuku has to be installed and started
 * (it stops at every reboot) and Genkan allowed once.
 */
@Singleton
class Privileged @Inject constructor() {

    enum class State { MISSING, STOPPED, NOT_ALLOWED, READY }

    @Volatile private var service: IFileService? = null

    fun state(): State = try {
        when {
            !Shizuku.pingBinder() -> State.STOPPED
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> State.NOT_ALLOWED
            else -> State.READY
        }
    } catch (_: Throwable) {
        State.MISSING
    }

    /** Shows Shizuku's own prompt to allow Genkan. */
    fun requestPermission() {
        if (state() == State.NOT_ALLOWED) Shizuku.requestPermission(REQUEST_CODE)
    }

    /** The file helper, started on first use; null when Shizuku isn't ready. */
    suspend fun files(): IFileService? {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        if (state() != State.READY) return null
        val bound = CompletableDeferred<IFileService?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                bound.complete(binder?.takeIf { it.pingBinder() }?.let(IFileService.Stub::asInterface))
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        val args = Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, FileService::class.java.name))
            .daemon(false)
            .processNameSuffix("files")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        return try {
            Shizuku.bindUserService(args, connection)
            withTimeoutOrNull(BIND_TIMEOUT_MS) { bound.await() }.also { service = it }
        } catch (e: Throwable) {
            Log.w(TAG, "Couldn't start the file helper", e)
            null
        }
    }

    private companion object {
        const val TAG = "Privileged"
        const val REQUEST_CODE = 4210
        const val BIND_TIMEOUT_MS = 10_000L
    }
}
