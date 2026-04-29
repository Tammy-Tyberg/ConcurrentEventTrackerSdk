package com.example.concurrenteventtrackersdk.sdk.di

import com.example.concurrenteventtrackersdk.sdk.ConcurrentEventTracker
import com.example.concurrenteventtrackersdk.sdk.ConcurrentEventTrackerImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class TrackerModule {

    @Binds
    @Singleton
    abstract fun bindConcurrentEventTracker(impl: ConcurrentEventTrackerImpl): ConcurrentEventTracker

    companion object {
        @Provides
        @Named("flushInterval")
        fun provideFlushInterval(): Long = 10_000L
    }
}