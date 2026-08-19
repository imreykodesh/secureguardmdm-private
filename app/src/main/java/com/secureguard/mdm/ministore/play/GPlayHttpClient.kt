package com.secureguard.mdm.ministore.play

import com.aurora.gplayapi.data.models.PlayResponse
import com.aurora.gplayapi.network.IHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Singleton
class GPlayHttpClient @Inject constructor() : IHttpClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val authClient = client.newBuilder()
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val mutableResponseCode = MutableStateFlow(100)
    override val responseCode: StateFlow<Int> = mutableResponseCode.asStateFlow()

    @Throws(IOException::class)
    override fun post(url: String, headers: Map<String, String>, body: ByteArray): PlayResponse =
        execute(
            Request.Builder()
                .url(url)
                .headers(headers.toHeaders())
                .post(body.toRequestBody())
                .build(),
        )

    @Throws(IOException::class)
    override fun post(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>,
    ): PlayResponse = execute(
        Request.Builder()
            .url(buildUrl(url, params))
            .headers(headers.toHeaders())
            .post(ByteArray(0).toRequestBody())
            .build(),
    )

    @Throws(IOException::class)
    override fun get(url: String, headers: Map<String, String>): PlayResponse =
        get(url, headers, emptyMap())

    @Throws(IOException::class)
    override fun get(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>,
    ): PlayResponse = execute(
        Request.Builder()
            .url(buildUrl(url, params))
            .headers(headers.toHeaders())
            .get()
            .build(),
    )

    @Throws(IOException::class)
    override fun get(
        url: String,
        headers: Map<String, String>,
        paramString: String,
    ): PlayResponse = execute(
        Request.Builder()
            .url("$url$paramString")
            .headers(headers.toHeaders())
            .get()
            .build(),
    )

    @Throws(IOException::class)
    override fun getAuth(url: String): PlayResponse = execute(
        Request.Builder()
            .url(url)
            .header("User-Agent", authUserAgent)
            .get()
            .build(),
        authClient,
    )

    @Throws(IOException::class)
    override fun postAuth(url: String, body: ByteArray): PlayResponse = execute(
        Request.Builder()
            .url(url)
            .header("User-Agent", authUserAgent)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build(),
        authClient,
    )

    @Throws(IOException::class)
    suspend fun postPrivateAuth(
        url: String,
        clientToken: String,
        body: ByteArray,
    ): PlayResponse {
        require(clientToken.isNotBlank()) { "Missing Mini Store client credential" }
        for (attempt in 0..1) {
            try {
                val response = execute(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", authUserAgent)
                        .header("Authorization", "Bearer $clientToken")
                        .post(body.toRequestBody(JSON_MEDIA_TYPE))
                        .build(),
                    authClient,
                    maxResponseBytes = MAX_AUTH_RESPONSE_BYTES,
                )
                if (response.code !in 502..504 || attempt == 1) return response
            } catch (error: IOException) {
                if (error is java.net.ProtocolException || attempt == 1) throw error
            }
            kotlinx.coroutines.delay(3_000L)
        }
        error("Private authentication retry loop ended unexpectedly")
    }

    private fun buildUrl(url: String, params: Map<String, String>): HttpUrl =
        url.toHttpUrl().newBuilder().apply {
            params.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()

    @Throws(IOException::class)
    private fun execute(
        request: Request,
        requestClient: OkHttpClient = client,
        maxResponseBytes: Long? = null,
    ): PlayResponse {
        mutableResponseCode.value = 0
        return requestClient.newCall(request).execute().use { response ->
            val responseBody = response.body
            val contentLength = responseBody.contentLength()
            if (maxResponseBytes != null && contentLength > maxResponseBytes) {
                throw java.net.ProtocolException("Private authentication response is too large")
            }
            val responseBytes = if (maxResponseBytes == null) {
                responseBody.bytes()
            } else {
                val source = responseBody.source()
                val buffer = okio.Buffer()
                while (buffer.size <= maxResponseBytes) {
                    val read = source.read(
                        buffer,
                        minOf(8_192L, maxResponseBytes + 1L - buffer.size),
                    )
                    if (read == -1L) break
                }
                if (buffer.size > maxResponseBytes) {
                    throw java.net.ProtocolException("Private authentication response is too large")
                }
                buffer.readByteArray()
            }
            mutableResponseCode.value = response.code
            PlayResponse(
                responseBytes = responseBytes,
                errorString = if (response.isSuccessful) "" else response.message,
                isSuccessful = response.isSuccessful,
                code = response.code,
                type = response.header("Content-Type") ?: "application/octet-stream",
            )
        }
    }

    private val authUserAgent: String
        get() = AUTH_USER_AGENT

    companion object {
        private const val AUTH_USER_AGENT = "com.secureguard.mdm-mini-store/1"
        private const val MAX_AUTH_RESPONSE_BYTES = 64L * 1024L
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
