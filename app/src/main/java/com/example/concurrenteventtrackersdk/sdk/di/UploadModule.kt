package com.example.concurrenteventtrackersdk.sdk.di

import android.content.Context
import com.example.concurrenteventtrackersdk.sdk.data.upload.EventPayloadWriter
import com.example.concurrenteventtrackersdk.sdk.data.upload.GzipCompressor
import com.example.concurrenteventtrackersdk.sdk.data.upload.OkHttpEventUploader
import com.example.concurrenteventtrackersdk.sdk.data.upload.TempFileManager
import com.example.concurrenteventtrackersdk.sdk.domain.upload.EventUploader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object UploadModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Provides
    @Singleton
    fun provideTempFileManager(@ApplicationContext context: Context): TempFileManager =
        TempFileManager(context.cacheDir)

    @Provides
    @Singleton
    fun provideEventPayloadWriter(json: Json): EventPayloadWriter = EventPayloadWriter(json)

    @Provides
    @Singleton
    fun provideGzipCompressor(): GzipCompressor = GzipCompressor()

    @Provides
    @Singleton
    fun provideEventUploader(
        client: OkHttpClient,
        json: Json,
        @IoDispatcher ioDispatcher: CoroutineDispatcher
    ): EventUploader = OkHttpEventUploader(client, json, ioDispatcher)
}
