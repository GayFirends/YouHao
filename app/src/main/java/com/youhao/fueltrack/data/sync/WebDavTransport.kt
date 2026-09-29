package com.youhao.fueltrack.data.sync

import com.youhao.fueltrack.domain.error.AppErrorCode
import com.youhao.fueltrack.domain.error.AppException
import com.youhao.fueltrack.domain.model.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration

/** The parts of an HTTP response the sync protocol actually reads. */
data class WebDavResponse(
    val status: Int,
    val body: String,
    val etag: String? = null,
    val lastModified: String? = null,
    val serverDate: String? = null,
    val contentLength: Long = 0L,
) {
    val ok: Boolean get() = status in 200..299
}

/**
 * The three verbs the WebDAV protocol needs. An interface so [SyncEngine] can be tested against a
 * scripted server without a network stack.
 */
interface WebDavTransport {
    suspend fun get(url: String): WebDavResponse

    suspend fun put(url: String, body: String, headers: Map<String, String>): WebDavResponse

    suspend fun propfind(url: String, headers: Map<String, String>): WebDavResponse
}

/**
 * Port of the `request()` helper in `src/services/webdav.ts`, including the 30 s deadline: the
 * legacy code used an `AbortController` around the whole fetch, so the equivalent here is OkHttp's
 * call timeout rather than a per-phase timeout.
 */
class OkHttpWebDavTransport(
    config: WebDavConfig,
    private val client: OkHttpClient = defaultWebDavClient(),
) : WebDavTransport {

    private val authorization: String =
        Credentials.basic(config.username, config.password, Charsets.UTF_8)

    override suspend fun get(url: String): WebDavResponse = execute(
        Request.Builder().url(url).get().header(AUTHORIZATION, authorization).build(),
    )

    override suspend fun propfind(url: String, headers: Map<String, String>): WebDavResponse = execute(
        Request.Builder()
            .url(url)
            .method("PROPFIND", null)
            .header(AUTHORIZATION, authorization)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build(),
    )

    override suspend fun put(
        url: String,
        body: String,
        headers: Map<String, String>,
    ): WebDavResponse = execute(
        Request.Builder()
            .url(url)
            .put(body.toRequestBody(JSON_MEDIA_TYPE))
            .header(AUTHORIZATION, authorization)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build(),
    )

    private suspend fun execute(request: Request): WebDavResponse = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response -> response.toWebDavResponse() }
        } catch (error: SocketTimeoutException) {
            throw AppException(AppErrorCode.SYNC_TIMEOUT, "WebDAV 请求超时", error)
        } catch (error: IOException) {
            throw AppException(AppErrorCode.NETWORK_FAILED, error.message ?: "网络请求失败", error)
        }
    }

    private fun Response.toWebDavResponse(): WebDavResponse = WebDavResponse(
        status = code,
        body = body.string(),
        etag = header("ETag"),
        lastModified = header("Last-Modified"),
        serverDate = header("Date"),
        contentLength = header("Content-Length")?.toLongOrNull() ?: 0L,
    )

    private companion object {
        const val AUTHORIZATION = "Authorization"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** 30 s total, matching `REQUEST_TIMEOUT_MS` in the legacy implementation. */
const val WEBDAV_REQUEST_TIMEOUT_MS: Long = 30_000

fun defaultWebDavClient(): OkHttpClient = OkHttpClient.Builder()
    .callTimeout(Duration.ofMillis(WEBDAV_REQUEST_TIMEOUT_MS))
    .connectTimeout(Duration.ofMillis(WEBDAV_REQUEST_TIMEOUT_MS))
    .readTimeout(Duration.ofMillis(WEBDAV_REQUEST_TIMEOUT_MS))
    .writeTimeout(Duration.ofMillis(WEBDAV_REQUEST_TIMEOUT_MS))
    .build()
