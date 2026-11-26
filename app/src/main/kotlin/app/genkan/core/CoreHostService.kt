package app.genkan.core

import android.app.Service
import android.content.Intent
import android.os.IBinder
import app.genkan.audio.GameAudioRecorder
import app.genkan.manual.PlayManual
import app.genkan.ui.BottomBack
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/** Where the built-in player's process checks in with the reader: its session, and its sound. */
@AndroidEntryPoint
class CoreHostService : Service() {

    @Inject lateinit var player: BuiltInPlayer
    @Inject lateinit var audio: GameAudioRecorder
    @Inject lateinit var manual: PlayManual

    private val binder = object : ICoreHost.Stub() {
        override fun attach(session: ICoreSession, romId: Long, coreId: String, look: String) {
            val core = CoreCatalog.byId(coreId) ?: return
            player.attach(session, romId, core, ScreenLook.of(look))
        }

        override fun audio(pcm: ByteArray, sampleRate: Int, endMs: Long) {
            audio.feed(pcm, sampleRate, endMs)
        }


        override fun scriptEvents(events: Array<String>) {
            player.scriptEvents(events.map { line -> line.substringBefore('\t').toInt() to line.substringAfter('\t') })
        }

        override fun scriptStateLoaded() {
            player.scriptStateLoaded()
        }

        override fun overlayTap(text: String, offset: Int) {
            player.overlayTapped(text, offset)
        }

        override fun hasManual(): Boolean = manual.game.value != null

        override fun manualOpen(): Boolean = manual.open.value != null

        override fun setManual(open: Boolean) {
            runBlocking(Dispatchers.Main) { if (open) manual.show() else manual.hide() }
        }

        // The bottom screen's viewers live on the main thread, as BottomBack wants.
        override fun bottomBack(): Boolean = runBlocking(Dispatchers.Main) { BottomBack.handle() }
    }

    override fun onBind(intent: Intent): IBinder = binder
}
