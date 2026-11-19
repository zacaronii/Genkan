package app.genkan

import android.content.Context
import androidx.room.Room
import app.genkan.library.LibraryDao
import app.genkan.library.LibraryDatabase
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
}
