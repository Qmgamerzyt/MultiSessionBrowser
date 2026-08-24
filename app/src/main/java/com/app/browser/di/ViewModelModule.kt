package com.app.browser.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.ViewModelProvider
import com.app.browser.omnibox.OmniboxViewModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.ActivityRetainedComponent

@Module
@InstallIn(ActivityRetainedComponent::class)
object ViewModelModule {
    
    @Provides
    fun provideOmniboxViewModel(): OmniboxViewModel {
        return OmniboxViewModel()
    }
}