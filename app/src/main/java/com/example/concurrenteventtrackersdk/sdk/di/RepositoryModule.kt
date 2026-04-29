package com.example.concurrenteventtrackersdk.sdk.di

import com.example.concurrenteventtrackersdk.sdk.data.repository.EventRepositoryImpl
import com.example.concurrenteventtrackersdk.sdk.domain.repository.EventRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindEventRepository(impl: EventRepositoryImpl): EventRepository
}