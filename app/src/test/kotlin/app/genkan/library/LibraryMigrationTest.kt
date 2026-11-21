package app.genkan.library

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * The 15 to 16 auto-migration (games gain their artwork and details) keeps the synced games and
 * gives the new columns their defaults. On the JVM, under Robolectric, so CI runs it: a version
 * 15 database is built from Room's own exported schema (app/schemas), then opened by the real
 * LibraryDatabase, which migrates it and checks the result against version 16's identity.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class LibraryMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun games_keep_their_rows_from_15_to_16() {
        val file = context.getDatabasePath(DB).apply { parentFile?.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            createFromSchema(db, 15)
            db.execSQL(
                "INSERT INTO games (id, platformId, platformSlug, name, sortTitle, fsName, fsSizeBytes, coverPath, " +
                    "hasMultipleFiles, summary) VALUES (1, 2, 'psx', 'Example Quest', 'example quest', NULL, 10, " +
                    "'/assets/romm/resources/roms/2/1/cover/small.png', 0, 'A made-up game.')",
            )
        }
        val library = Room.databaseBuilder(context, LibraryDatabase::class.java, DB).allowMainThreadQueries().build()
        try {
            library.openHelper.readableDatabase
                .query("SELECT name, summary, physicalPath, discCount, backlogged, userRating, hltbMainStorySec FROM games").use { row ->
                    assertTrue(row.moveToFirst())
                    assertEquals("Example Quest", row.getString(0))
                    assertEquals("A made-up game.", row.getString(1))
                    assertTrue(row.isNull(2))
                    assertTrue(row.isNull(3))
                    assertEquals(0, row.getInt(4))
                    assertEquals(0, row.getInt(5))
                    assertTrue(row.isNull(6))
                }
        } finally {
            library.close()
        }
    }

    /** The tables, indices and Room's own bookkeeping of [version], as its exported schema has them. */
    private fun createFromSchema(db: SQLiteDatabase, version: Int) {
        val schema = JSONObject(File(SCHEMAS, "$version.json").readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.version = version
    }

    private companion object {
        const val DB = "migration-test.db"

        /** Unit tests run in the module's folder. */
        val SCHEMAS = File("schemas/app.genkan.library.LibraryDatabase")
    }
}
