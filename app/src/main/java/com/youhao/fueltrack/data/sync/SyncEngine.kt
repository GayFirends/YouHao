package com.youhao.fueltrack.data.sync

import com.youhao.fueltrack.data.prefs.SettingsRepository
import com.youhao.fueltrack.domain.error.AppErrorCode
import com.youhao.fueltrack.domain.error.AppException
import com.youhao.fueltrack.domain.model.SyncDeviceState
import com.youhao.fueltrack.domain.model.SyncMetadata
import com.youhao.fueltrack.domain.model.SyncPayloadV1
import com.youhao.fueltrack.domain.model.WebDavConfig
import com.youhao.fueltrack.domain.sync.SyncCrypto
import com.youhao.fueltrack.domain.sync.SyncJson
import com.youhao.fueltrack.domain.sync.detectSyncConflicts
import com.youhao.fueltrack.domain.sync.withoutConflicts
import com.youhao.fueltrack.domain.time.Timestamps
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Port of `src/services/webdav.ts`. */
class SyncEngine(
    private val store: SyncStore,
    private val settings: SettingsRepository,
    private val transportFactory: (WebDavConfig) -> WebDavTransport = { OkHttpWebDavTransport(it) },
    private val clock: () -> Instant = Instant::now,
    /** Non-fatal problems (a failed tombstone cleanup, say) surface here instead of via android.util.Log. */
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> },
) {

    /** `testWebDav`: PROPFIND on the sync directory, `Depth: 0`. */
    suspend fun testConnection(config: WebDavConfig) {
        validateConfig(config)
        val response = transportFactory(config)
            .propfind(SyncUrls.directory(config).toString(), mapOf("Depth" to "0"))
        when (response.status) {
            401, 403 -> throw AppException(AppErrorCode.SYNC_AUTH_FAILED, "账号或应用密码无效")
            404 -> throw AppException(AppErrorCode.SYNC_AUTH_FAILED, "WebDAV 同步目录不存在")
            else -> if (!response.ok && response.status != WEBDAV_MULTI_STATUS) {
                throw AppException(AppErrorCode.NETWORK_FAILED, "连接失败（HTTP ${response.status}）")
            }
        }
    }

    /** `syncWebDav`: download, merge, upload, then best-effort device-metadata bookkeeping. */
    suspend fun sync(config: WebDavConfig): Instant {
        validateConfig(config)
        val transport = transportFactory(config)
        val target = SyncUrls.target(config)
        val fileUrl = SyncUrls.syncFile(config).toString()

        for (attempt in 1..MAX_SYNC_ATTEMPTS) {
            val download = transport.get(fileUrl)
            assertClockSane(download)

            var etag: String? = null
            var lastModified: String? = null
            var remoteMissing = false

            if (download.ok) {
                etag = download.etag
                lastModified = download.lastModified
                val remotePayload = parseRemoteResponse(download, config.encryptionPassphrase.orEmpty())
                assertPayloadClock(remotePayload)
                val localBeforeMerge = store.exportData()
                val conflicts = detectSyncConflicts(
                    local = localBeforeMerge,
                    remote = remotePayload,
                    base = settings.syncBase(target),
                    detectedAt = Timestamps.format(clock()),
                )
                if (conflicts.isNotEmpty()) store.saveConflicts(conflicts)
                store.mergeData(withoutConflicts(remotePayload, conflicts))
            } else if (download.status == 404) {
                remoteMissing = true
            } else {
                if (download.status == 401 || download.status == 403) {
                    throw AppException(AppErrorCode.SYNC_AUTH_FAILED, "WebDAV 认证失败")
                }
                throw AppException(AppErrorCode.NETWORK_FAILED, "下载失败（HTTP ${download.status}）")
            }

            val conditionalHeaders = buildMap {
                when {
                    etag != null -> put("If-Match", etag)
                    lastModified != null -> put("If-Unmodified-Since", lastModified)
                    remoteMissing -> put("If-None-Match", "*")
                }
            }

            val localPayload = store.exportData()
            assertPayloadClock(localPayload)
            val body = if (config.encryptionEnabled == true) {
                SyncJson.encodeToString(SyncCrypto.encrypt(localPayload, config.encryptionPassphrase.orEmpty()))
            } else {
                SyncJson.encodeToString(localPayload)
            }

            val upload = transport.put(fileUrl, body, conditionalHeaders)
            if (upload.ok) {
                val completedAt = clock()
                settings.saveSyncBase(target, store.exportData())
                // The legacy app logged and swallowed a metadata failure: the payload is already
                // safe in the cloud, so tombstone housekeeping must not fail the whole sync.
                try {
                    syncDeviceMetadata(config, transport, target, Timestamps.format(completedAt))
                } catch (error: Throwable) {
                    onWarning("设备同步元数据同步失败，已跳过墓碑清理", error)
                }
                return completedAt
            }

            when {
                upload.status == 412 && attempt < MAX_SYNC_ATTEMPTS -> Unit // Someone wrote first; re-download and retry.
                upload.status == 409 ->
                    throw AppException(AppErrorCode.NETWORK_FAILED, "WebDAV 同步目录不存在，请先在服务器上创建该目录")

                upload.status == 401 || upload.status == 403 ->
                    throw AppException(AppErrorCode.SYNC_AUTH_FAILED, "WebDAV 没有写入权限")

                upload.status == 412 ->
                    throw AppException(AppErrorCode.SYNC_CONFLICT, "云端数据持续发生冲突，请稍后重试")

                else -> throw AppException(AppErrorCode.NETWORK_FAILED, "上传失败（HTTP ${upload.status}）")
            }
        }
        throw AppException(AppErrorCode.SYNC_CONFLICT, "同步重试次数已用尽")
    }

    /**
     * `syncDeviceMetadata`: publishes this device's acknowledgement and, once every known device has
     * acknowledged, drops tombstones nobody can still need.
     */
    private suspend fun syncDeviceMetadata(
        config: WebDavConfig,
        transport: WebDavTransport,
        target: String,
        acknowledgedThrough: String,
    ) {
        val url = SyncUrls.metadataFile(config).toString()
        for (attempt in 1..MAX_SYNC_ATTEMPTS) {
            val download = transport.get(url)
            var metadata = SyncMetadata(devices = emptyMap())
            val headers = mutableMapOf<String, String>()

            if (download.ok) {
                metadata = runCatching { SyncJson.decodeFromString<SyncMetadata>(download.body) }
                    .getOrElse { throw AppException(AppErrorCode.SYNC_FORMAT_INVALID, "设备同步元数据格式无效") }
                val etag = download.etag
                val lastModified = download.lastModified
                when {
                    etag != null -> headers["If-Match"] = etag
                    lastModified != null -> headers["If-Unmodified-Since"] = lastModified
                    else -> throw AppException(
                        AppErrorCode.SYNC_FORMAT_INVALID,
                        "服务器未提供设备元数据的 ETag 或 Last-Modified，已跳过安全清理",
                    )
                }
            } else if (download.status == 404) {
                headers["If-None-Match"] = "*"
            } else {
                throw AppException(
                    AppErrorCode.NETWORK_FAILED,
                    "设备同步元数据下载失败（HTTP ${download.status}）",
                )
            }

            val deviceId = settings.deviceId()
            val updated = metadata.copy(
                devices = metadata.devices + (
                    deviceId to SyncDeviceState(
                        deviceId = deviceId,
                        lastSeenAt = acknowledgedThrough,
                        acknowledgedThrough = acknowledgedThrough,
                    )
                    ),
            )

            val upload = transport.put(url, SyncJson.encodeToString(updated), headers)
            if (upload.ok) {
                settings.saveMetadataCache(target, updated)
                compactTombstonesIfEveryDeviceAcknowledged(updated)
                return
            }
            if (upload.status != 412 || attempt == MAX_SYNC_ATTEMPTS) {
                throw AppException(
                    AppErrorCode.NETWORK_FAILED,
                    "设备同步元数据上传失败（HTTP ${upload.status}）",
                )
            }
        }
        throw AppException(AppErrorCode.NETWORK_FAILED, "设备同步元数据重试次数已用尽")
    }

    private suspend fun compactTombstonesIfEveryDeviceAcknowledged(metadata: SyncMetadata) {
        val acknowledgements = metadata.devices.values.mapNotNull { Timestamps.parseOrNull(it.acknowledgedThrough) }
        // A device with an unparseable acknowledgement can still be holding a copy of a record we
        // are about to purge, so refuse to compact unless every device is accounted for.
        if (acknowledgements.isEmpty() || acknowledgements.size != metadata.devices.size) return
        val retentionCutoff = clock().minus(Duration.ofMillis(TOMBSTONE_RETENTION_MS))
        val safeCutoff = acknowledgements.min().let { if (it.isBefore(retentionCutoff)) it else retentionCutoff }
        store.compactTombstones(Timestamps.format(safeCutoff))
    }

    private fun parseRemoteResponse(response: WebDavResponse, passphrase: String): SyncPayloadV1 {
        if (response.contentLength > MAX_REMOTE_BYTES || response.body.length > MAX_REMOTE_BYTES) {
            throw AppException(AppErrorCode.SYNC_FORMAT_INVALID, "云端备份超过 20 MB，拒绝同步")
        }
        val element = runCatching { SyncJson.parseToJsonElement(response.body) }.getOrElse {
            throw AppException(AppErrorCode.SYNC_FORMAT_INVALID, "云端数据不是有效的 JSON")
        }
        return try {
            SyncCrypto.decrypt(element, passphrase)
        } catch (error: AppException) {
            throw error
        } catch (error: Throwable) {
            throw AppException(AppErrorCode.SYNC_FORMAT_INVALID, "云端数据格式不受支持", error)
        }
    }

    private fun assertClockSane(response: WebDavResponse) {
        val serverDate = response.serverDate ?: return
        val serverTime = runCatching {
            ZonedDateTime.parse(serverDate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        }.getOrNull() ?: return
        if (abs(Duration.between(clock(), serverTime).toMillis()) > MAX_CLOCK_SKEW_MS) {
            throw AppException(
                AppErrorCode.SYNC_FORMAT_INVALID,
                "设备时间与 WebDAV 服务器相差超过 5 分钟，请校准系统时间后同步",
            )
        }
    }

    private fun assertPayloadClock(payload: SyncPayloadV1) {
        val futureLimit = clock().plusMillis(MAX_CLOCK_SKEW_MS)
        val isFuture = { timestamp: String -> Timestamps.parseOrNull(timestamp)?.isAfter(futureLimit) ?: false }
        if (isFuture(payload.exportedAt)) throwFutureTimestamp()
        if (payload.vehicles.any { isFuture(it.updatedAt) }) throwFutureTimestamp()
        if (payload.records.any { isFuture(it.updatedAt) }) throwFutureTimestamp()
    }

    private fun throwFutureTimestamp(): Nothing = throw AppException(
        AppErrorCode.SYNC_FORMAT_INVALID,
        "同步数据包含未来时间，请先校准产生该数据的设备时间",
    )

    companion object {
        const val MAX_SYNC_ATTEMPTS: Int = 3
        const val MAX_CLOCK_SKEW_MS: Long = 5 * 60_000
        const val TOMBSTONE_RETENTION_MS: Long = 90L * 24 * 60 * 60_000
        const val MAX_REMOTE_BYTES: Int = 20 * 1024 * 1024
        private const val WEBDAV_MULTI_STATUS = 207

        /** `validateConfig`, shared by the settings screen and the sync path. */
        fun validateConfig(config: WebDavConfig) {
            if (config.url.isBlank()) throw AppException(AppErrorCode.UNKNOWN, "请填写 WebDAV 地址")
            val url = runCatching { config.url.trimEnd('/').toHttpUrlOrNull() }.getOrNull()
                ?: throw AppException(AppErrorCode.UNKNOWN, "WebDAV 地址格式无效")
            val host = url.host
            val localDevelopment = host == "localhost" || host == "127.0.0.1"
            if (url.scheme != "https" && !(url.scheme == "http" && localDevelopment)) {
                throw AppException(AppErrorCode.UNKNOWN, "WebDAV 必须使用 HTTPS（本机调试除外）")
            }
            val passphraseLength = config.encryptionPassphrase?.length ?: 0
            if (config.encryptionEnabled == true && passphraseLength < SyncCrypto.MIN_PASSPHRASE_LENGTH) {
                throw AppException(
                    AppErrorCode.SYNC_ENCRYPTION_FAILED,
                    "启用加密时，同步口令至少需要 ${SyncCrypto.MIN_PASSPHRASE_LENGTH} 个字符",
                )
            }
        }
    }
}
