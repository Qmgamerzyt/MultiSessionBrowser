package app.multisession.browser.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SessionEntity::class, TabEntity::class, HistoryEntity::class, BookmarkEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun tabs(): TabDao
    abstract fun history(): HistoryDao
    abstract fun bookmarks(): BookmarkDao

    companion object {
        /** v1 (WebView era) -> v2 (GeckoView): tabs gain a nullable sessionState column. No data is lost. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tabs ADD COLUMN sessionState TEXT")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "browser.db")
                .addMigrations(MIGRATION_1_2)
                // Only reached for unknown future downgrades; every known upgrade path is migrated above.
                .fallbackToDestructiveMigrationOnDowngrade(true)
                .build()
    }
}
