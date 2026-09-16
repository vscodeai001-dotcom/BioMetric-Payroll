package com.biometric.app.di

import android.content.Context
import android.provider.Settings
import com.biometric.app.ai.GeminiService
import com.biometric.app.BuildConfig
import com.biometric.app.api.ApiService
import com.biometric.app.api.MobileApiService
import com.biometric.app.api.OsrmApiService
import com.biometric.app.data.MainRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await

@Module
@InstallIn(SingletonComponent::class)
object ApiModule {
    private const val GEMINI_API_KEY = "AIzaSyBptaB9GQdDhsWZ0u6dxnellgNJPhTK95Q"

    @Provides
    @Singleton
    fun provideRetrofit(@ApplicationContext context: Context): Retrofit {
        val logging = HttpLoggingInterceptor()
        logging.setLevel(HttpLoggingInterceptor.Level.BODY)

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder()
                val firebaseUser = FirebaseAuth.getInstance().currentUser
                if (firebaseUser != null) {
                    runCatching {
                        val freshToken = runBlocking {
                            firebaseUser.getIdToken(false).await().token
                        }
                        if (!freshToken.isNullOrBlank()) {
                            builder.header("Authorization", "Bearer $freshToken")
                        }
                    }
                    val deviceId = Settings.Secure.getString(
                        context.contentResolver,
                        Settings.Secure.ANDROID_ID
                    )
                    if (!deviceId.isNullOrBlank()) {
                        builder.header("X-Android-Device-Id", deviceId)
                    }
                }
                chain.proceed(builder.build())
            }
            .addInterceptor(logging)
            .build()

        val baseUrl = BuildConfig.BIOMETRIC_API_BASE_URL
            .trim()
            .let { if (it.endsWith("/")) it else "$it/" }

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService = retrofit.create(ApiService::class.java)

    @Provides
    @Singleton
    fun provideMobileApiService(retrofit: Retrofit): MobileApiService = retrofit.create(MobileApiService::class.java)

    @Provides
    @Singleton
    fun provideOsrmApiService(): OsrmApiService {
        val client = OkHttpClient.Builder().build()
        return Retrofit.Builder()
            .baseUrl("https://router.project-osrm.org/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OsrmApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideGeminiService(): GeminiService = GeminiService(GEMINI_API_KEY)
}
