package app.genkan.library

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The 15 to 16 auto-migration (games gain their artwork and details) keeps the synced games and
 * gives the new columns their defaults. Runs against the exported schemas in app/schemas.
 */
@RunWith(AndroidJUnit4::class)
class LibraryMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LibraryDatabase::class.java)

    @Test
    fun games_keep_their_rows_from_15_to_16() {
        helper.createDatabase(DB, 15).use { db ->
            db.execSQL(
                "INSERT INTO games (id, platformId, platformSlug, name, sortTitle, fsName, fsSizeBytes, coverPath, " +
                    "hasMultipleFiles, summary) VALUES (1, 2, 'psx', 'Example Quest', 'example quest', NULL, 10, " +
                    "'/assets/romm/resources/roms/2/1/cover/small.png', 0, 'A made-up game.')"
            )
        }
        val db = helper.runMigrationsAndValidate(DB, 16, true)
        db.query("SELECT name, summary, physicalPath, discCount, backlogged, userRating, hltbMainStorySec FROM games").use { row ->
            assertTrue(row.moveToFirst())
            assertEquals("Example Quest", row.getString(0))
            assertEquals("A made-up game.", row.getString(1))
            assertTrue(row.isNull(2))
            assertTrue(row.isNull(3))
            assertEquals(0, row.getInt(4))
            assertEquals(0, row.getInt(5))
            assertTrue(row.isNull(6))
        }
        db.close()
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
