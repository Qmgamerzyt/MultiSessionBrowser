package com.app.browser.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.app.browser.data.dao.*
import com.app.browser.data.entities.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        SessionEntity::class,
        TabEntity::class,
        BookmarkEntity::class,
        HistoryEntity::class,
        ExtensionEntity::class,
        MatrixEntity::class,
        SettingEntity::class,
        UserScriptEntity::class,
        DownloadEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters()
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun tabDao(): TabDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
    abstract fun extensionDao(): ExtensionDao
    abstract fun matrixDao(): MatrixDao
    abstract fun settingDao(): SettingDao
    abstract fun userScriptDao(): UserScriptDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "browser.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }

        fun clearInstance() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}

// Pre-populate database with default data
fun initializeDatabase(context: Context) {
    CoroutineScope(Dispatchers.IO).launch {
        val db = AppDatabase.getInstance(context)
        
        // Insert default search engine setting if not exists
        val settings = db.settingDao().getAll()
        if (settings.none { it.key == "search_engine" }) {
            db.settingDao().insert(SettingEntity("search_engine", "https://duckduckgo.com/?q=%s"))
        }
        if (settings.none { it.key == "homepage" }) {
            db.settingDao().insert(SettingEntity("homepage", "https://duckduckgo.com"))
        }
        if (settings.none { it.key == "dark_mode" }) {
            db.settingDao().insert(SettingEntity("dark_mode", "system"))
        }
        if (settings.none { it.key == "javascript_enabled" }) {
            db.settingDao().insert(SettingEntity("javascript_enabled", "true"))
        }
    }
}