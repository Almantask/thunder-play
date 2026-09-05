package com.thunderplay.di

import android.content.Context
import com.squareup.moshi.Moshi
import com.thunderplay.BuildConfig
import com.thunderplay.drive.AssetServiceAccountKeyProvider
import com.thunderplay.drive.AuthInterceptor
import com.thunderplay.drive.DriveErrorInterceptor
import com.thunderplay.drive.DriveApi
import com.thunderplay.drive.ServiceAccountAuth
import com.thunderplay.drive.ServiceAccountKeyProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** The bare client used for the token exchange; it must not carry the auth interceptor. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TokenExchangeClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun moshi(): Moshi = Moshi.Builder().build()

    private fun baseClient(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
                )
            }
        }

    @Provides
    @Singleton
    @TokenExchangeClient
    fun tokenExchangeClient(): OkHttpClient = baseClient().build()

    @Provides
    @Singleton
    fun keyProvider(
        @ApplicationContext context: Context,
        moshi: Moshi,
    ): ServiceAccountKeyProvider = AssetServiceAccountKeyProvider(context, moshi)

    @Provides
    @Singleton
    fun serviceAccountAuth(
        keyProvider: ServiceAccountKeyProvider,
        @TokenExchangeClient client: OkHttpClient,
        moshi: Moshi,
    ): ServiceAccountAuth = ServiceAccountAuth(keyProvider, client, moshi)

    @Provides
    @Singleton
    fun driveClient(auth: ServiceAccountAuth): OkHttpClient = baseClient()
        // Order matters: the error interceptor sits OUTSIDE the auth one, so a 401 gets its
        // silent token refresh and retry before any failure is raised.
        .addInterceptor(DriveErrorInterceptor())
        .addInterceptor(AuthInterceptor(auth))
        .build()

    @Provides
    @Singleton
    fun driveApi(client: OkHttpClient, moshi: Moshi): DriveApi = Retrofit.Builder()
        .baseUrl(DriveApi.BASE_URL)
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(DriveApi::class.java)
}
