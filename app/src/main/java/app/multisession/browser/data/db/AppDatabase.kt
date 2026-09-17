package app.multisession.browser.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SessionEntity::class, TabEntity::class, TabGroupEntity::class, HistoryEntity::class,
        BookmarkEntity::class, SitePermissionEntity::class, DownloadEntity::class,
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun tabs(): TabDao
    abstract fun tabGroups(): TabGroupDao
    abstract fun history(): HistoryDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun sitePermissions(): SitePermissionDao
    abstract fun downloads(): DownloadDao

    companion object {
        /** v1 (WebView era) -> v2 (GeckoView): tabs gain a nullable sessionState column. No data is lost. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tabs ADD COLUMN sessionState TEXT")
            }
        }

        /** v2 -> v3: new site_permissions table (per-session Allow/Block decisions). Purely additive: existing rows are untouched. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `site_permissions` (" +
                        "`sessionId` TEXT NOT NULL, `origin` TEXT NOT NULL, `permission` TEXT NOT NULL, " +
                        "`value` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`sessionId`, `origin`, `permission`), " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_site_permissions_sessionId` ON `site_permissions` (`sessionId`)")
            }
        }

        /**
         * v3 -> v4 (v2.0.2): tab groups, tab->group membership, explicit default session, app-owned downloads.
         * Additive only: every existing session / tab / cookie / permission row is kept. The first session in the
         * existing order becomes the default session (that is the one that was always listed first before).
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN isDefault INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE sessions SET isDefault = 1 WHERE id = (SELECT id FROM sessions ORDER BY sortOrder ASC, createdAt ASC LIMIT 1)")
                db.execSQL("ALTER TABLE tabs ADD COLUMN groupId TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tab_groups` (" +
                        "`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `name` TEXT NOT NULL, `color` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, `collapsed` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tab_groups_sessionId` ON `tab_groups` (`sessionId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `downloads` (" +
                        "`id` TEXT NOT NULL, `sessionId` TEXT, `url` TEXT NOT NULL, `sourcePage` TEXT, `fileName` TEXT NOT NULL, " +
                        "`mimeType` TEXT NOT NULL, `totalBytes` INTEGER NOT NULL, `downloadedBytes` INTEGER NOT NULL, `status` INTEGER NOT NULL, " +
                        "`contentUri` TEXT, `filePath` TEXT, `error` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_createdAt` ON `downloads` (`createdAt`)")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "browser.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                // Only reached for unknown future downgrades; every known upgrade path is migrated above.
                .fallbackToDestructiveMigrationOnDowngrade(true)
                .build()
    }
}
