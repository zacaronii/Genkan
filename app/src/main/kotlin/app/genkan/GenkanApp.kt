package app.genkan

import kotlinx.coroutines.launch
import android.app.Application
import app.genkan.art.ArtLoaders
import app.genkan.art.LibraryArtFetch
import app.genkan.download.DownloadQueue
import app.genkan.manual.ManualPrefetch
import app.genkan.anki.AnkiMiner
import app.genkan.reader.ReaderSession
import app.genkan.words.HachidoriLink
import app.genkan.words.WordMarks
import app.genkan.stats.ReadingLogPusher
import app.genkan.saves.SaveSync
import app.genkan.armsx2.ArmsxText
import app.genkan.azahar.AzaharText
import app.genkan.core.BuiltInText
import app.genkan.ppsspp.PpssppText
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GenkanApp : Application(), SingletonImageLoader.Factory {

    // Lazy, so the built-in player's process (which shares this class) builds none of them.
    @Inject lateinit var readerSession: Lazy<ReaderSession>
    @Inject lateinit var ankiMiner: Lazy<AnkiMiner>
    @Inject lateinit var readingLog: Lazy<ReadingLogPusher>
    @Inject lateinit var saveSync: Lazy<SaveSync>
    @Inject lateinit var ppssppText: Lazy<PpssppText>
    @Inject lateinit var azaharText: Lazy<AzaharText>
    @Inject lateinit var armsxText: Lazy<ArmsxText>
    @Inject lateinit var builtInText: Lazy<BuiltInText>
    @Inject lateinit var downloadQueue: Lazy<DownloadQueue>
    @Inject lateinit var wordMarks: Lazy<WordMarks>
    @Inject lateinit var hachidori: Lazy<HachidoriLink>
    @Inject lateinit var artLoaders: Lazy<ArtLoaders>
    @Inject lateinit var libraryArt: Lazy<LibraryArtFetch>
    @Inject lateinit var manualPrefetch: Lazy<ManualPrefetch>
    @Inject lateinit var playerSettings: Lazy<app.genkan.core.PlayerSettings>

    override fun onCreate() {
        super.onCreate()
        // The built-in player's process runs one game and nothing of the launcher's.
        if (getProcessName().endsWith(":core")) return
        // What plays each console, as Settings keeps it, for the launcher, the details and the save
        // sync to read without waiting (the player's process is told its core by the launcher).
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
            playerSettings.get().coreChoice.collect { app.genkan.core.CoreCatalog.choice = it }
        }
        // The reader listens for a texthooker from the start: a game's lines can arrive while
        // the bottom screen is released or not yet placed.
        readerSession.get().start()
        // Cards made offline go to Anki once it answers.
        ankiMiner.get().start()
        readingLog.get().start()
        saveSync.get().start()
        ppssppText.get().start()
        azaharText.get().start()
        armsxText.get().start()
        builtInText.get().start()
        // Which words Anki has, for the backlog's marks; asked again after each card reaches Anki.
        wordMarks.get().start(ankiMiner.get().deliveries)
        // Hand marks and lookup counts shared with a Hachidori host, when one is set.
        hachidori.get().start()
        downloadQueue.get().resume()
        artLoaders.get().start()
        // After each library sync, the whole library's shelf art, in the background.
        libraryArt.get().start()
        // After each sync and each finished download, the installed games' manuals.
        manualPrefetch.get().start()
    }

    /** The screen's image loader: see [ArtLoaders]. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = artLoaders.get().screen
}
