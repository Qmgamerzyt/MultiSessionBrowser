package com.app.browser.di

import android.content.Context
import com.app.browser.data.AppDatabase
import com.app.browser.engine.EngineProvider
import com.app.browser.engine.ProfileManager
import com.app.browser.engine.StorageCleaner
import com.app.browser.extension.ExtensionManager
import com.app.browser.session.SessionManager
import com.app.browser.tab.TabManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideAppDatabase(@dagger.hilt.android.qualifiers.ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getInstance(context)
    }
    
    @Provides
    @Singleton
    fun provideEngineProvider(@dagger.hilt.android.qualifiers.ApplicationContext context: Context): EngineProvider {
        return EngineProvider.getInstance(context)
    }
    
    @Provides
    @Singleton
    fun provideProfileManager(@dagger.hilt.android.qualifiers.ApplicationContext context: Context): ProfileManager {
        return ProfileManager(context)
    }
    
    @Provides
    @Singleton
    fun provideStorageCleaner(@dagger.hilt.android.qualifiers.ApplicationContext context: Context): StorageCleaner {
        return StorageCleaner(context)
    }
    
    @Provides
    @Singleton
    fun provideSessionManager(
        context: Context,
        db: AppDatabase,
        profileManager: ProfileManager,
        engineProvider: EngineProvider,
        storageCleaner: StorageCleaner
    ): SessionManager {
        return SessionManager(context, db, profileManager, engineProvider, storageCleaner)
    }
    
    @Provides
    @Singleton
    fun provideTabManager(db: AppDatabase, engineProvider: EngineProvider): TabManager {
        return TabManager(db, engineProvider)
    }
    
    @Provides
    @Singleton
    fun provideExtensionManager(
        context: Context,
        db: AppDatabase,
        engineProvider: EngineProvider
    ): ExtensionManager {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        return ExtensionManager(context, db, engineProvider, okHttpClient)
    }
}