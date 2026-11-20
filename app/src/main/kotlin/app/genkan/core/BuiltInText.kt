package app.genkan.core

import app.genkan.memory.MemoryText
import app.genkan.memory.MemoryTextStatus
import app.genkan.ocr.OcrReader
import app.genkan.reader.ReaderSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Text from the memory of the game in the built-in player, found and followed as for Azahar
 * ([MemoryText]). The player's memory is Genkan's own to read, so this needs no setting.
 */
@Singleton
class BuiltInText @Inject constructor(
    session: ReaderSession,
    ocr: OcrReader,
    private val player: BuiltInPlayer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val text = MemoryText(session, ocr, TAG)
    private var started = false

    val status: StateFlow<MemoryTextStatus> = text.status

    /** Lines handed in as if OCR had read them (debug builds). */
    internal val offered: MutableSharedFlow<String> get() = text.offered

    fun start() {
        if (started) return
        started = true
        scope.launch {
            player.game.collectLatest { game ->
                if (game != null) text.follow(player::memory) else text.off()
            }
        }
    }

    private companion object {
        const val TAG = "BuiltInText"
    }
}
