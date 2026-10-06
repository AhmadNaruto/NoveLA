package my.noveldokusha.feature.local_database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.driver.SupportSQLiteConnection
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Миграция БД 27 → 30 (манга-слой): ChapterPages, DownloadedPageChapter
 * и колонка Chapter.uploaded. Устройства на v27 (до-манга сборки) должны
 * обновиться без потери данных — это и проверяем на реальных schema-json.
 *
 * Конструктор helper выбран driver-based: на Windows `SupportSQLiteDriver`
 * (legacy-путь helper) падает из-за сравнения имён с '/' в абсолютном пути,
 * а `AndroidSQLiteDriver` не делает такой проверки и при этом оборачивает
 * соединение в `SupportSQLiteConnection`, поэтому миграции в `Migrations.kt`
 * (legacy API `SupportSQLiteDatabase`) выполняются без изменений.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationsTest {

    private val dbFile =
        InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("migration-test")

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        file = dbFile,
        driver = AndroidSQLiteDriver(),
        databaseClass = AppRoomDatabase::class,
    )

    @Test
    fun `v27 to v30 preserves data and creates page tables`() {
        dbFile.parentFile?.mkdirs()
        dbFile.delete()

        helper.createDatabase(27).use { connection ->
            val db = (connection as SupportSQLiteConnection).db
            // Данные, обязанные пережить миграцию
            db.execSQL(
                "INSERT INTO Book (url, title, completed, inLibrary, coverImageUrl, description, " +
                    "lastReadEpochTimeMilli, addedToLibraryEpochTimeMilli, lastUpdateEpochTimeMilli, " +
                    "category, genres, rating) " +
                    "VALUES ('https://book/1', 'Title', 0, 0, '', 'Desc', 0, 0, 0, '', '', '')"
            )
            db.execSQL(
                "INSERT INTO Chapter (title, url, bookUrl, position, read, lastReadPosition, lastReadOffset) " +
                    "VALUES ('Ch1', 'https://book/1/ch/1', 'https://book/1', 0, 0, 0, 0)"
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            30,
            databaseMigrations().toList()
        )
        migrated.use { connection ->
            val db = (connection as SupportSQLiteConnection).db
            // Старые данные на месте
            db.query("SELECT COUNT(*) FROM Book").use { c ->
                c.moveToFirst()
                assertTrue("book row lost", c.getInt(0) == 1)
            }
            db.query("SELECT COUNT(*) FROM Chapter").use { c ->
                c.moveToFirst()
                assertTrue("chapter row lost", c.getInt(0) == 1)
            }
            // Новые таблицы созданы
            db.query("SELECT COUNT(*) FROM ChapterPages").use { c ->
                c.moveToFirst()
                assertTrue("ChapterPages missing", c.getInt(0) == 0)
            }
            db.query("SELECT COUNT(*) FROM DownloadedPageChapter").use { c ->
                c.moveToFirst()
                assertTrue("DownloadedPageChapter missing", c.getInt(0) == 0)
            }
            // Новая колонка есть и null для старых записей
            db.query("SELECT uploaded FROM Chapter LIMIT 1").use { c ->
                c.moveToFirst()
                assertTrue("uploaded column missing", c.isNull(0))
            }
        }
    }

    @Test
    fun `v30 to v31 adds contentType column with empty default`() {
        dbFile.parentFile?.mkdirs()
        dbFile.delete()

        helper.createDatabase(30).use { connection ->
            val db = (connection as SupportSQLiteConnection).db
            // Данные, обязанные пережить миграцию
            db.execSQL(
                "INSERT INTO Book (url, title, completed, inLibrary, coverImageUrl, description, " +
                    "lastReadEpochTimeMilli, addedToLibraryEpochTimeMilli, lastUpdateEpochTimeMilli, " +
                    "category, genres, rating) " +
                    "VALUES ('https://book/1', 'Title', 0, 0, '', 'Desc', 0, 0, 0, '', '', '')"
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            31,
            databaseMigrations().toList()
        )
        migrated.use { connection ->
            val db = (connection as SupportSQLiteConnection).db
            // Старые данные на месте
            db.query("SELECT COUNT(*) FROM Book").use { c ->
                c.moveToFirst()
                assertTrue("book row lost", c.getInt(0) == 1)
            }
            // Новая колонка существует и для старых записей равна '' (NOVEL)
            db.query("SELECT contentType FROM Book LIMIT 1").use { c ->
                c.moveToFirst()
                assertTrue("contentType column missing", !c.isNull(0))
                assertTrue("contentType default is not empty", c.getString(0) == "")
            }
        }
    }

    @Test
    fun `v31 to v32 adds status and lastUpdateDate columns with empty default`() {
        dbFile.parentFile?.mkdirs()
        dbFile.delete()

        helper.createDatabase(31).use { connection ->
            val db = (connection as SupportSQLiteConnection).db
            // Данные, обязанные пережить миграцию
            db.execSQL(
                "INSERT INTO Book (url, title, completed, inLibrary, coverImageUrl, description, " +
                    "lastReadEpochTimeMilli, addedToLibraryEpochTimeMilli, lastUpdateEpochTimeMilli, " +
                    "category, genres, rating, contentType) " +
                    "VALUES ('https://book/1', 'Title', 0, 0, '', 'Desc', 0, 0, 0, '', '', '', 'NOVEL')"
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            32,
            databaseMigrations().toList()
        )
        migrated.use { connection ->
            val db = (connection as SupportSQLiteConnection).db
            // Старые данные на месте
            db.query("SELECT COUNT(*) FROM Book").use { c ->
                c.moveToFirst()
                assertTrue("book row lost", c.getInt(0) == 1)
            }
            // Новые колонки существуют и для старых записей равны ''
            db.query("SELECT status, lastUpdateDate FROM Book LIMIT 1").use { c ->
                c.moveToFirst()
                assertTrue("status column missing", !c.isNull(0))
                assertTrue("status default is not empty", c.getString(0) == "")
                assertTrue("lastUpdateDate column missing", !c.isNull(1))
                assertTrue("lastUpdateDate default is not empty", c.getString(1) == "")
            }
        }
    }

    @Test
    fun `v32 to v33 creates empty BookTranslation table`() {
        dbFile.parentFile?.mkdirs()
        dbFile.delete()

        helper.createDatabase(32).use { connection ->
            val db = (connection as SupportSQLiteConnection).db
            // Данные, обязанные пережить миграцию
            db.execSQL(
                "INSERT INTO Book (url, title, completed, inLibrary, coverImageUrl, description, " +
                    "lastReadEpochTimeMilli, addedToLibraryEpochTimeMilli, lastUpdateEpochTimeMilli, " +
                    "category, genres, rating, contentType, status, lastUpdateDate) " +
                    "VALUES ('https://book/1', 'Title', 0, 0, '', 'Desc', 0, 0, 0, '', '', '', 'NOVEL', '', '')"
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            33,
            databaseMigrations().toList()
        )
        migrated.use { connection ->
            val db = (connection as SupportSQLiteConnection).db
            // Старые данные на месте
            db.query("SELECT COUNT(*) FROM Book").use { c ->
                c.moveToFirst()
                assertTrue("book row lost", c.getInt(0) == 1)
            }
            // Новая таблица создана и пуста
            db.query("SELECT COUNT(*) FROM BookTranslation").use { c ->
                c.moveToFirst()
                assertTrue("BookTranslation missing", c.getInt(0) == 0)
            }
        }
    }

    @Test
    fun `v33 to v34 adds video position columns with zero default`() {
        dbFile.parentFile?.mkdirs()
        dbFile.delete()

        helper.createDatabase(33).use { connection ->
            val db = (connection as SupportSQLiteConnection).db
            // Данные, обязанные пережить миграцию (manga-позиция главы тоже)
            db.execSQL(
                "INSERT INTO Chapter (title, url, bookUrl, position, read, lastReadPosition, lastReadOffset) " +
                    "VALUES ('Ep1', 'https://book/1/ep/1', 'https://book/1', 0, 0, 7, 11)"
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            34,
            databaseMigrations().toList()
        )
        migrated.use { connection ->
            val db = (connection as SupportSQLiteConnection).db
            // Наличие колонок + значения по умолчанию для старых записей
            db.query(
                "SELECT url, lastReadPosition, lastReadOffset, videoPositionMs, videoDurationMs " +
                    "FROM Chapter LIMIT 1"
            ).use { c ->
                c.moveToFirst()
                assertTrue("chapter row lost", c.getString(0) == "https://book/1/ep/1")
                assertTrue("lastReadPosition damaged", c.getInt(1) == 7)
                assertTrue("lastReadOffset damaged", c.getInt(2) == 11)
                assertTrue("videoPositionMs missing or not 0", c.getInt(3) == 0)
                assertTrue("videoDurationMs missing or not 0", c.getInt(4) == 0)
            }
        }
    }

    @Test
    fun `v34 to v35 adds volume column`() {
        dbFile.parentFile?.mkdirs()
        dbFile.delete()

        helper.createDatabase(34).use { connection ->
            val db = (connection as SupportSQLiteConnection).db
            // Данные, обязанные пережить миграцию (в т.ч. видео-позиция главы)
            db.execSQL(
                "INSERT INTO Chapter (title, url, bookUrl, position, read, lastReadPosition, lastReadOffset, " +
                    "videoPositionMs, videoDurationMs) " +
                    "VALUES ('Ep1', 'https://book/1/ep/1', 'https://book/1', 0, 0, 7, 11, 5, 9)"
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            35,
            databaseMigrations().toList()
        )
        migrated.use { connection ->
            val db = (connection as SupportSQLiteConnection).db
            // Старые данные на месте, volume — NULL для существующих записей
            db.query(
                "SELECT url, lastReadPosition, lastReadOffset, videoPositionMs, videoDurationMs, volume " +
                    "FROM Chapter LIMIT 1"
            ).use { c ->
                c.moveToFirst()
                assertTrue("chapter row lost", c.getString(0) == "https://book/1/ep/1")
                assertTrue("lastReadPosition damaged", c.getInt(1) == 7)
                assertTrue("lastReadOffset damaged", c.getInt(2) == 11)
                assertTrue("videoPositionMs damaged", c.getInt(3) == 5)
                assertTrue("videoDurationMs damaged", c.getInt(4) == 9)
                assertTrue("volume column missing", c.getColumnIndex("volume") != -1)
                assertTrue("volume not NULL by default", c.isNull(5))
            }
        }
    }
}
