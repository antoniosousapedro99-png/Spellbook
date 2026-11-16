package com.cybernomicon.grimoire.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Builds an authenticated [HubApi] for a given hub URL + bearer token.
 *
 * TLS: when the hub URL is https the platform trust store applies. For a
 * lab hub using a self-signed certificate, the operator installs that CA on
 * the device (Settings > Security > Install a certificate) rather than the
 * app disabling verification — the client never trusts all certificates.
 */
object HubClient {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun create(baseUrl: String, token: String): HubApi {
        val authInterceptor = Interceptor { chain ->
            val req = chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
            chain.proceed(req)
        }

        val ok = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS) // daemon runs can be long
            .build()

        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(ok)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(HubApi::class.java)
    }
}
