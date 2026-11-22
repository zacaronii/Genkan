package app.genkan

import android.content.Context
import androidx.room.Room
import app.genkan.anki.AnkiDao
import app.genkan.dictionary.DictionaryDao
import app.genkan.ocr.OcrRegionDao
import app.genkan.reader.ReaderDao
import app.genkan.stats.ReadingDao
import app.genkan.saves.SaveFileDao
import app.genkan.download.DownloadDao
import app.genkan.download.DownloadService
import app.genkan.download.DownloadStarter
import app.genkan.library.FavouriteDao
import app.genkan.library.LibraryDao
import app.genkan.manual.ManualSource
import app.genkan.manual.Manuals
import app.genkan.library.LibraryDatabase
import app.genkan.words.AnkiWordDao
import app.genkan.words.GameLookupDao
import app.genkan.words.LookupCountDao
import app.genkan.words.WordStatusDao
import app.genkan.play.PlaySessionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** One connection pool for the app; RomM clients and Coil derive from it. */
    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient()

    @Provides
    @Singleton
    fun libraryDatabase(@ApplicationContext context: Context): LibraryDatabase =
        Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db").build()

    @Provides
    fun libraryDao(db: LibraryDatabase): LibraryDao = db.library()

    @Provides
    fun manualSource(manuals: Manuals): ManualSource = manuals

    @Provides
    fun downloadDao(db: LibraryDatabase): DownloadDao = db.downloads()

    @Provides
    fun downloadStarter(@ApplicationContext context: Context): DownloadStarter = DownloadStarter { DownloadService.start(context) }

    @Provides
    fun playSessionDao(db: LibraryDatabase): PlaySessionDao = db.playSessions()

    @Provides
    fun favouriteDao(db: LibraryDatabase): FavouriteDao = db.favourites()

    @Provides
    fun dictionaryDao(db: LibraryDatabase): DictionaryDao = db.dictionaries()

    @Provides
    fun readerDao(db: LibraryDatabase): ReaderDao = db.reader()

    @Provides
    fun ocrRegionDao(db: LibraryDatabase): OcrRegionDao = db.ocrRegions()

    @Provides
    fun ankiDao(db: LibraryDatabase): AnkiDao = db.anki()

    @Provides
    fun readingDao(db: LibraryDatabase): ReadingDao = db.reading()

    @Provides
    fun saveFileDao(db: LibraryDatabase): SaveFileDao = db.saveFiles()

    @Provides
    fun wordStatusDao(db: LibraryDatabase): WordStatusDao = db.wordStatuses()

    @Provides
    fun lookupCountDao(db: LibraryDatabase): LookupCountDao = db.lookupCounts()

    @Provides
    fun gameLookupDao(db: LibraryDatabase): GameLookupDao = db.gameLookups()

    @Provides
    fun ankiWordDao(db: LibraryDatabase): AnkiWordDao = db.ankiWords()
}
