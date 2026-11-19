package app.genkan

import android.content.Context
import androidx.room.Room
import app.genkan.anki.AnkiDao
import app.genkan.dictionary.DictionaryDao
import app.genkan.ocr.OcrRegionDao
import app.genkan.reader.ReaderDao
import app.genkan.stats.ReadingDao
import app.genkan.download.DownloadDao
import app.genkan.library.LibraryDao
import app.genkan.library.LibraryDatabase
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
    fun downloadDao(db: LibraryDatabase): DownloadDao = db.downloads()

    @Provides
    fun playSessionDao(db: LibraryDatabase): PlaySessionDao = db.playSessions()

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
}
