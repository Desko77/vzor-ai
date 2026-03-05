package com.vzor.ai.di

import android.content.Context
import androidx.room.Room
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.vzor.ai.data.local.AppDatabase
import com.vzor.ai.data.local.ConversationDao
import com.vzor.ai.data.local.MemoryFactDao
import com.vzor.ai.data.local.MessageDao
import com.vzor.ai.data.local.SessionLogDao
import com.vzor.ai.data.remote.ClaudeApiService
import com.vzor.ai.data.remote.GlmApiService
import com.vzor.ai.data.remote.OllamaService
import com.vzor.ai.data.remote.OpenAiApiService
import com.vzor.ai.data.remote.TavilySearchService
import com.vzor.ai.data.repository.AiRepositoryImpl
import com.vzor.ai.data.repository.ConversationRepositoryImpl
import com.vzor.ai.data.repository.MemoryRepositoryImpl
import com.vzor.ai.data.repository.VisionRepositoryImpl
import com.vzor.ai.domain.repository.AiRepository
import com.vzor.ai.domain.repository.ConversationRepository
import com.vzor.ai.domain.repository.MemoryRepository
import com.vzor.ai.domain.repository.VisionRepository
import com.vzor.ai.speech.SttService
import com.vzor.ai.speech.WhisperSttService
import com.vzor.ai.tts.TtsManager
import com.vzor.ai.tts.TtsProvider
import com.vzor.ai.tts.TtsService
import com.vzor.ai.tts.YandexTtsProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.vzor.ai.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.HEADERS
            }
            redactHeader("Authorization")
            redactHeader("X-API-Key")
            redactHeader("x-api-key")
        })
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideClaudeApi(okHttpClient: OkHttpClient, moshi: Moshi): ClaudeApiService =
        Retrofit.Builder()
            .baseUrl("https://api.anthropic.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ClaudeApiService::class.java)

    @Provides
    @Singleton
    fun provideOpenAiApi(okHttpClient: OkHttpClient, moshi: Moshi): OpenAiApiService =
        Retrofit.Builder()
            .baseUrl("https://api.openai.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OpenAiApiService::class.java)

    @Provides
    @Singleton
    fun provideGlmApi(okHttpClient: OkHttpClient, moshi: Moshi): GlmApiService =
        Retrofit.Builder()
            .baseUrl("https://open.bigmodel.cn/api/paas/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GlmApiService::class.java)

    @Provides
    @Singleton
    fun provideTavilyApi(okHttpClient: OkHttpClient, moshi: Moshi): TavilySearchService =
        Retrofit.Builder()
            .baseUrl("https://api.tavily.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TavilySearchService::class.java)

    @Provides
    @Singleton
    fun provideOllamaService(okHttpClient: OkHttpClient, moshi: Moshi): OllamaService =
        OllamaService(okHttpClient, moshi)
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "vzor_db")
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideConversationDao(db: AppDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideSessionLogDao(db: AppDatabase): SessionLogDao = db.sessionLogDao()

    @Provides
    fun provideMemoryFactDao(db: AppDatabase): MemoryFactDao = db.memoryFactDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAiRepository(impl: AiRepositoryImpl): AiRepository

    @Binds
    @Singleton
    abstract fun bindConversationRepository(impl: ConversationRepositoryImpl): ConversationRepository

    @Binds
    @Singleton
    abstract fun bindVisionRepository(impl: VisionRepositoryImpl): VisionRepository

    @Binds
    @Singleton
    abstract fun bindMemoryRepository(impl: MemoryRepositoryImpl): MemoryRepository

    @Binds
    @Singleton
    abstract fun bindSttService(impl: WhisperSttService): SttService

    @Binds
    @Singleton
    abstract fun bindTtsProvider(impl: YandexTtsProvider): TtsProvider

    @Binds
    @Singleton
    abstract fun bindTtsService(impl: TtsManager): TtsService
}
