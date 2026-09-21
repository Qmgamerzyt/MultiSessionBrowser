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
        ClosedTabEntity::class, ClosedGroupEntity::class, WorkspaceEntity::class, WorkspaceGroupEntity::class, WorkspaceItemEntity::class,
    ],
    version = 5,
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
    abstract fun closedTabs(): ClosedTabDao
    abstract fun closedGroups(): ClosedGroupDao
    abstract fun workspaces(): WorkspaceDao

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

        /**
         * v4 -> v5 (v2.1.0, Part 1): pinned + archived tabs, persisted recently-closed tabs/groups, workspaces.
         * Additive only: existing sessions / tabs / groups / permissions / downloads are untouched. New tab columns get
         * DEFAULT 0 (matching @ColumnInfo(defaultValue = "0")), so every existing tab is simply "not pinned, not archived".
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tabs ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tabs ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tabs ADD COLUMN archivedAt INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `closed_tabs` (" +
                        "`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `url` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`groupId` TEXT, `groupName` TEXT, `groupColor` INTEGER, `position` INTEGER NOT NULL, " +
                        "`pinned` INTEGER NOT NULL, `desktopMode` INTEGER NOT NULL, `sessionState` TEXT, `closedGroupId` TEXT, " +
                        "`closedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_closed_tabs_sessionId` ON `closed_tabs` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_closed_tabs_closedAt` ON `closed_tabs` (`closedAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `closed_groups` (" +
                        "`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `name` TEXT NOT NULL, `color` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, `collapsed` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `closedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_closed_groups_sessionId` ON `closed_groups` (`sessionId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workspaces` (" +
                        "`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workspaces_sessionId` ON `workspaces` (`sessionId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workspace_groups` (" +
                        "`id` TEXT NOT NULL, `workspaceId` TEXT NOT NULL, `name` TEXT NOT NULL, `color` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, `collapsed` INTEGER NOT NULL, `sourceGroupId` TEXT, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`workspaceId`) REFERENCES `workspaces`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workspace_groups_workspaceId` ON `workspace_groups` (`workspaceId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workspace_items` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `workspaceId` TEXT NOT NULL, `url` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, `desktopMode` INTEGER NOT NULL, `groupId` TEXT, " +
                        "`sessionState` TEXT, `sourceTabId` TEXT, " +
                        "FOREIGN KEY(`workspaceId`) REFERENCES `workspaces`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workspace_items_workspaceId` ON `workspace_items` (`workspaceId`)")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "browser.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                // Only reached for unknown future downgrades; every known upgrade path is migrated above.
                .fallbackToDestructiveMigrationOnDowngrade(true)
                .build()
    }
}
