package com.biometric.app.di

import com.biometric.app.ai.GeminiService
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

@Module
@InstallIn(SingletonComponent::class)
object ApiModule {
    private const val GEMINI_API_KEY = "AIzaSyBptaB9GQdDhsWZ0u6dxnellgNJPhTK95Q"
    private const val BIOMETRIC_BASE_URL = "https://biometric-payroll.onrender.com/"

    @Provides
    @Singleton
    fun provideRetrofit(): Retrofit {
        val logging = HttpLoggingInterceptor()
        logging.setLevel(HttpLoggingInterceptor.Level.BODY)
        
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(BIOMETRIC_BASE_URL)
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
