package app.genkan.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import app.genkan.dictionary.Dictionaries
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Dictionary checks from adb, logged under the "DictionaryDebug" tag:
 *   adb shell am broadcast -a app.genkan.debug.LOOKUP -n app.genkan/.debug.DictionaryDebugReceiver --es text 眺めた
 *   adb shell am broadcast -a app.genkan.debug.IMPORT_DICTIONARIES -n app.genkan/.debug.DictionaryDebugReceiver [--es folder PATH] [--ez fresh true]
 * (fresh removes every dictionary first, to time a whole import)
 */
@AndroidEntryPoint
class DictionaryDebugReceiver : BroadcastReceiver() {

    @Inject lateinit var dictionaries: Dictionaries

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    "app.genkan.debug.LOOKUP" -> lookup(intent.getStringExtra("text").orEmpty())
                    "app.genkan.debug.IMPORT_DICTIONARIES" -> {
                        if (intent.getBooleanExtra("fresh", false)) dictionaries.all.first().forEach { dictionaries.remove(it) }
                        dictionaries.importFrom(
                            intent.getStringExtra("folder")?.let(::File)
                                ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        )
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun lookup(text: String) {
        val started = System.nanoTime()
        val result = dictionaries.lookup(text)
        val ms = (System.nanoTime() - started) / 1_000_000
        Log.i(TAG, "\"$text\": ${result.hits.size} hits in $ms ms, longest match ${result.matchedLength}")
        result.hits.forEach { hit ->
            val freq = hit.frequencies.flatMap { f -> f.values.map { "${f.dictionary} ${it.display.ifEmpty { it.value.toString() }}" } }
            val pitch = hit.pitches.flatMap { p -> p.pitches.map { "${p.dictionary} [${it.position}]" } }
            Log.i(
                TAG,
                "  ${hit.matched} → ${hit.expression}【${hit.reading}】 via ${hit.trace.joinToString(" ") { it.name }.ifEmpty { "-" }}" +
                    "  dicts=${hit.glossaries.map { it.dictionary }.distinct()} freq=$freq pitch=$pitch",
            )
        }
    }

    private companion object {
        const val TAG = "DictionaryDebug"
    }
}
