package app.genkan.core

import android.app.Service
import android.content.Intent
import android.os.IBinder
import app.genkan.audio.GameAudioRecorder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Where the built-in player's process checks in with the reader: its session, and its sound. */
@AndroidEntryPoint
class CoreHostService : Service() {

    @Inject lateinit var player: BuiltInPlayer
    @Inject lateinit var audio: GameAudioRecorder

    private val binder = object : ICoreHost.Stub() {
        override fun attach(session: ICoreSession, romId: Long, coreId: String) {
            val core = CoreCatalog.cores.firstOrNull { it.id == coreId } ?: return
            player.attach(session, romId, core)
        }

        override fun audio(pcm: ByteArray, sampleRate: Int, endMs: Long) {
            audio.feed(pcm, sampleRate, endMs)
        }

        override fun layout(readJson: String) {
            player.layoutRead(readJson)
        }

        override fun overlayTap(text: String, offset: Int) {
            player.overlayTapped(text, offset)
        }
    }

    override fun onBind(intent: Intent): IBinder = binder
}
