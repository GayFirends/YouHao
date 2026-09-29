package com.youhao.fueltrack.data.sync

import com.google.common.truth.Truth.assertThat
import com.youhao.fueltrack.data.prefs.DEFAULT_SYNC_FILE_NAME
import com.youhao.fueltrack.data.prefs.SessionSecrets
import com.youhao.fueltrack.data.prefs.SettingsRepository
import com.youhao.fueltrack.domain.error.AppErrorCode
import com.youhao.fueltrack.domain.error.AppException
import com.youhao.fueltrack.domain.model.FuelRecord
import com.youhao.fueltrack.domain.model.SyncDeviceState
import com.youhao.fueltrack.domain.model.SyncMetadata
import com.youhao.fueltrack.domain.model.SyncPayloadV1
import com.youhao.fueltrack.domain.model.Vehicle
import com.youhao.fueltrack.domain.model.WebDavConfig
import com.youhao.fueltrack.domain.sync.DetectedConflict
import com.youhao.fueltrack.domain.sync.SyncBase
import com.youhao.fueltrack.domain.sync.SyncCrypto
import com.youhao.fueltrack.domain.sync.SyncJson
import com.youhao.fueltrack.domain.sync.buildSyncBase
import com.youhao.fueltrack.domain.time.Timestamps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

/**
 * Exercises the WebDAV protocol against a scripted server. The legacy implementation was verified
 * by hand against a real server; these tests pin the parts that are easy to get subtly wrong —
 * conditional headers, the 412 retry loop, conflict detection and the tombstone cutoff.
 */
class SyncEngineTest {

    private val now: Instant = Instant.parse("2026-06-01T12:00:00.000Z")
    private val passphrase = "youhao-sync-test-passphrase"

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private fun config(
        url: String = "https://dav.example.com/remote.php/dav/fueltrack",
        encryptionEnabled: Boolean = false,
        encryptionPassphrase: String? = if (encryptionEnabled) passphrase else null,
    ) = WebDavConfig(
        url = url,
        username = "user",
        password = "app-password",
        fileName = DEFAULT_SYNC_FILE_NAME,
        encryptionEnabled = encryptionEnabled,
        encryptionPassphrase = encryptionPassphrase,
        rememberEncryptionPassphrase = false,
    )

    private fun engine(
        store: FakeStore,
        settings: FakeSettings,
        transport: ScriptedTransport,
        onWarning: (String, Throwable) -> Unit = { _, _ -> },
    ) = SyncEngine(
        store = store,
        settings = settings,
        transportFactory = { transport },
        clock = { now },
        onWarning = onWarning,
    )

    private fun payload(
        vehicleName: String = "我的车辆",
        vehicleId: String = "v1",
        exportedAt: String = "2026-06-01T11:00:00.000Z",
    ) = SyncPayloadV1(
        version = 1,
        exportedAt = exportedAt,
        vehicles = listOf(
            Vehicle(
                id = vehicleId,
                name = vehicleName,
                plate = "",
                fuelType = "92#",
                initialOdometer = 0.0,
                createdAt = "2026-01-01T00:00:00.000Z",
                updatedAt = "2026-05-01T00:00:00.000Z",
            ),
        ),
        records = emptyList(),
    )

    /** Runs [block] and returns the [AppException] it must throw. */
    private suspend fun expectAppException(block: suspend () -> Unit): AppException {
        try {
            block()
        } catch (error: AppException) {
            return error
        }
        throw AssertionError("expected an AppException, but nothing was thrown")
    }

    // -----------------------------------------------------------------------
    // Happy paths
    // -----------------------------------------------------------------------

    @Test
    fun createsTheRemoteFileWhenItDoesNotExistYet() = runTest {
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(status = 404, body = "")
        transport.putQueue += WebDavResponse(status = 201, body = "")
        val settings = FakeSettings()

        val completedAt = engine(FakeStore(payload()), settings, transport).sync(config())

        assertThat(completedAt).isEqualTo(now)
        val put = transport.payloadPut()
        // The legacy client guarded a first write with If-None-Match: * so two devices racing to
        // create the file cannot silently overwrite each other.
        assertThat(put.headers).containsEntry("If-None-Match", "*")
        assertThat(settings.savedBase).isEqualTo(buildSyncBase(payload()))
    }

    @Test
    fun mergesTheRemotePayloadBeforeUploading() = runTest {
        // A different id means the remote vehicle is new to this device, so nothing conflicts.
        val remote = payload(vehicleName = "远端车辆", vehicleId = "v2")
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(status = 200, body = SyncJson.encodeToString(remote), etag = "\"v1\"")
        transport.putQueue += WebDavResponse(status = 204, body = "")
        val store = FakeStore(payload())

        engine(store, FakeSettings(base = SyncBase.EMPTY), transport).sync(config())

        assertThat(store.merged).isEqualTo(remote)
        assertThat(store.savedConflicts).isEmpty()
    }

    @Test
    fun holdsBackAConflictingEntityUntilTheUserPicksASide() = runTest {
        // `base` records what this device last agreed with the server. A stale hash that matches
        // neither side is what the legacy three-way rule calls a conflict.
        val staleHash = "0".repeat(64)
        val remote = payload(vehicleName = "远端车辆")
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(status = 200, body = SyncJson.encodeToString(remote), etag = "\"v1\"")
        transport.putQueue += WebDavResponse(status = 204, body = "")
        val store = FakeStore(payload())

        engine(store, FakeSettings(base = SyncBase(vehicles = mapOf("v1" to staleHash))), transport)
            .sync(config())

        val conflict = store.savedConflicts.single()
        assertThat(conflict.id).isEqualTo("vehicle:v1")
        // The disputed vehicle must not be merged while it is unresolved; the rest of the payload
        // still flows through.
        assertThat(store.merged?.vehicles).isEmpty()
        assertThat(store.merged?.exportedAt).isEqualTo(remote.exportedAt)
    }

    @Test
    fun usesIfMatchWhenTheServerHasAnEtag() = runTest {
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(status = 200, body = SyncJson.encodeToString(payload()), etag = "\"abc\"")
        transport.putQueue += WebDavResponse(status = 204, body = "")

        engine(FakeStore(payload()), FakeSettings(), transport).sync(config())

        val put = transport.payloadPut()
        assertThat(put.headers).containsEntry("If-Match", "\"abc\"")
        assertThat(put.headers).doesNotContainKey("If-None-Match")
    }

    @Test
    fun fallsBackToIfUnmodifiedSinceWithoutAnEtag() = runTest {
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(
            status = 200,
            body = SyncJson.encodeToString(payload()),
            lastModified = "Fri, 01 May 2026 00:00:00 GMT",
        )
        transport.putQueue += WebDavResponse(status = 204, body = "")

        engine(FakeStore(payload()), FakeSettings(), transport).sync(config())

        assertThat(transport.payloadPut().headers)
            .containsEntry("If-Unmodified-Since", "Fri, 01 May 2026 00:00:00 GMT")
    }

    // -----------------------------------------------------------------------
    // Retry loop
    // -----------------------------------------------------------------------

    @Test
    fun reDownloadsAndRetriesAfterA412() = runTest {
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(status = 200, body = SyncJson.encodeToString(payload()), etag = "\"v1\"")
        transport.putQueue += WebDavResponse(status = 412, body = "")
        transport.getQueue += WebDavResponse(status = 200, body = SyncJson.encodeToString(payload()), etag = "\"v2\"")
        transport.putQueue += WebDavResponse(status = 201, body = "")

        engine(FakeStore(payload()), FakeSettings(), transport).sync(config())

        assertThat(transport.requests.count { it.method == "GET" && it.url.endsWith(DEFAULT_SYNC_FILE_NAME) })
            .isEqualTo(2)
        val secondPut = transport.requests
            .filter { it.method == "PUT" && it.url.endsWith(DEFAULT_SYNC_FILE_NAME) }[1]
        assertThat(secondPut.headers).containsEntry("If-Match", "\"v2\"")
    }

    @Test
    fun givesUpAfterThreeConsecutivePreconditionFailures() = runTest {
        val transport = ScriptedTransport()
        repeat(SyncEngine.MAX_SYNC_ATTEMPTS) {
            transport.getQueue += WebDavResponse(status = 200, body = SyncJson.encodeToString(payload()), etag = "\"v1\"")
            transport.putQueue += WebDavResponse(status = 412, body = "")
        }

        val error = expectAppException { engine(FakeStore(payload()), FakeSettings(), transport).sync(config()) }

        assertThat(error.code).isEqualTo(AppErrorCode.SYNC_CONFLICT)
        assertThat(error.message).isEqualTo("云端数据持续发生冲突，请稍后重试")
    }

    @Test
    fun reportsAMissingDirectoryWhenTheServerAnswers409() = runTest {
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(status = 404, body = "")
        transport.putQueue += WebDavResponse(status = 409, body = "")

        val error = expectAppException { engine(FakeStore(payload()), FakeSettings(), transport).sync(config()) }

        assertThat(error.message).isEqualTo("WebDAV 同步目录不存在，请先在服务器上创建该目录")
    }

    @Test
    fun reportsMissingWritePermission() = runTest {
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(status = 404, body = "")
        transport.putQueue += WebDavResponse(status = 403, body = "")

        val error = expectAppException { engine(FakeStore(payload()), FakeSettings(), transport).sync(config()) }

        assertThat(error.code).isEqualTo(AppErrorCode.SYNC_AUTH_FAILED)
        assertThat(error.message).isEqualTo("WebDAV 没有写入权限")
    }

    // -----------------------------------------------------------------------
    // Guards
    // -----------------------------------------------------------------------

    @Test
    fun refusesToRunWhenTheServerClockDisagrees() = runTest {
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(
            status = 200,
            body = SyncJson.encodeToString(payload()),
            serverDate = "Mon, 01 Jun 2026 13:00:00 GMT", // an hour ahead of `now`
        )

        val error = expectAppException { engine(FakeStore(payload()), FakeSettings(), transport).sync(config()) }

        assertThat(error.message).isEqualTo("设备时间与 WebDAV 服务器相差超过 5 分钟，请校准系统时间后同步")
        assertThat(transport.requests.none { it.method == "PUT" }).isTrue()
    }

    @Test
    fun acceptsAServerDateWithinFiveMinutes() = runTest {
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(
            status = 200,
            body = SyncJson.encodeToString(payload()),
            serverDate = "Mon, 01 Jun 2026 12:04:59 GMT",
        )
        transport.putQueue += WebDavResponse(status = 204, body = "")

        engine(FakeStore(payload()), FakeSettings(), transport).sync(config())

        assertThat(transport.requests.any { it.method == "PUT" }).isTrue()
    }

    @Test
    fun refusesAFutureTimestampedPayload() = runTest {
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(
            status = 200,
            body = SyncJson.encodeToString(payload(exportedAt = "2027-01-01T00:00:00.000Z")),
        )

        val error = expectAppException { engine(FakeStore(payload()), FakeSettings(), transport).sync(config()) }

        assertThat(error.message).isEqualTo("同步数据包含未来时间，请先校准产生该数据的设备时间")
    }

    @Test
    fun refusesALocalRecordWithAFutureTimestamp() = runTest {
        val local = payload().copy(records = listOf(FutureRecord))
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(status = 404, body = "")

        val error = expectAppException { engine(FakeStore(local), FakeSettings(), transport).sync(config()) }

        assertThat(error.message).isEqualTo("同步数据包含未来时间，请先校准产生该数据的设备时间")
        assertThat(transport.requests.none { it.method == "PUT" }).isTrue()
    }

    @Test
    fun refusesAnOversizedRemotePayload() = runTest {
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(
            status = 200,
            body = "{}",
            contentLength = SyncEngine.MAX_REMOTE_BYTES + 1L,
        )

        val error = expectAppException { engine(FakeStore(payload()), FakeSettings(), transport).sync(config()) }

        assertThat(error.message).isEqualTo("云端备份超过 20 MB，拒绝同步")
    }

    @Test
    fun refusesRemoteTextThatIsNotJson() = runTest {
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(status = 200, body = "<html>404 Not Found</html>")

        val error = expectAppException { engine(FakeStore(payload()), FakeSettings(), transport).sync(config()) }

        assertThat(error.message).isEqualTo("云端数据不是有效的 JSON")
    }

    @Test
    fun reportsAnAuthenticationFailureOnDownload() = runTest {
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(status = 401, body = "")

        val error = expectAppException { engine(FakeStore(payload()), FakeSettings(), transport).sync(config()) }

        assertThat(error.code).isEqualTo(AppErrorCode.SYNC_AUTH_FAILED)
        assertThat(error.message).isEqualTo("WebDAV 认证失败")
    }

    // -----------------------------------------------------------------------
    // Encryption
    // -----------------------------------------------------------------------

    @Test
    fun decryptsAnEncryptedRemoteFile() = runTest {
        val encrypted = SyncJson.encodeToString(SyncCrypto.encrypt(payload(vehicleName = "云端加密车"), passphrase))
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(status = 200, body = encrypted)
        transport.putQueue += WebDavResponse(status = 204, body = "")
        val store = FakeStore(payload())

        engine(store, FakeSettings(), transport).sync(config(encryptionEnabled = true))

        assertThat(store.merged?.vehicles?.single()?.name).isEqualTo("云端加密车")
    }

    @Test
    fun uploadsAnEnvelopeWhenEncryptionIsOn() = runTest {
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(status = 404, body = "")
        transport.putQueue += WebDavResponse(status = 201, body = "")

        engine(FakeStore(payload()), FakeSettings(), transport).sync(config(encryptionEnabled = true))

        val element = SyncJson.parseToJsonElement(transport.payloadPut().body)
        assertThat(SyncCrypto.isEncrypted(element)).isTrue()
        assertThat(SyncCrypto.decrypt(element, passphrase).vehicles.single().name).isEqualTo("我的车辆")
    }

    @Test
    fun uploadsPlainJsonWhenEncryptionIsOff() = runTest {
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(status = 404, body = "")
        transport.putQueue += WebDavResponse(status = 201, body = "")

        engine(FakeStore(payload()), FakeSettings(), transport).sync(config())

        val element = SyncJson.parseToJsonElement(transport.payloadPut().body)
        assertThat(SyncCrypto.isEncrypted(element)).isFalse()
        // kotlinx.serialization must emit the defaults, otherwise the version field would vanish and
        // the legacy validator would reject the document.
        assertThat(element.toString()).contains("\"version\":1")
    }

    @Test
    fun reportsAWrongPassphraseAsAnEncryptionFailure() = runTest {
        val encrypted = SyncJson.encodeToString(SyncCrypto.encrypt(payload(), passphrase))
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(status = 200, body = encrypted)

        val error = expectAppException {
            engine(FakeStore(payload()), FakeSettings(), transport)
                .sync(config(encryptionEnabled = true, encryptionPassphrase = "wrong-passphrase"))
        }

        assertThat(error.code).isEqualTo(AppErrorCode.SYNC_ENCRYPTION_FAILED)
        assertThat(error.message).isEqualTo("同步口令错误或云端文件已损坏")
    }

    // -----------------------------------------------------------------------
    // Device metadata and tombstone retention
    // -----------------------------------------------------------------------

    @Test
    fun compactsTombstonesOnlyUpToTheOldestAcknowledgement() = runTest {
        val remoteMetadata = SyncMetadata(
            version = 1,
            devices = mapOf(
                "device-b" to SyncDeviceState("device-b", "2026-01-01T00:00:00.000Z", "2026-01-01T00:00:00.000Z"),
            ),
        )
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(status = 404, body = "")
        transport.putQueue += WebDavResponse(status = 201, body = "")
        transport.getQueue += WebDavResponse(status = 200, body = SyncJson.encodeToString(remoteMetadata), etag = "\"m1\"")
        transport.putQueue += WebDavResponse(status = 204, body = "")
        val store = FakeStore(payload())

        engine(store, FakeSettings(deviceIdValue = "device-a"), transport).sync(config())

        // now - 90 days is 2026-03-03, but device-b has only acknowledged through 2026-01-01, so the
        // older acknowledgement must win.
        assertThat(store.compactedCutoffs).containsExactly("2026-01-01T00:00:00.000Z")
    }

    @Test
    fun usesTheRetentionWindowWhenEveryDeviceIsRecent() = runTest {
        val remoteMetadata = SyncMetadata(
            version = 1,
            devices = mapOf(
                "device-b" to SyncDeviceState("device-b", "2026-05-30T00:00:00.000Z", "2026-05-30T00:00:00.000Z"),
            ),
        )
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(status = 404, body = "")
        transport.putQueue += WebDavResponse(status = 201, body = "")
        transport.getQueue += WebDavResponse(status = 200, body = SyncJson.encodeToString(remoteMetadata), etag = "\"m1\"")
        transport.putQueue += WebDavResponse(status = 204, body = "")
        val store = FakeStore(payload())

        engine(store, FakeSettings(deviceIdValue = "device-a"), transport).sync(config())

        assertThat(store.compactedCutoffs)
            .containsExactly(Timestamps.format(now.minusSeconds(90L * 24 * 60 * 60)))
    }

    @Test
    fun skipsCompactionWhenADeviceAcknowledgementIsUnreadable() = runTest {
        val remoteMetadata = SyncMetadata(
            version = 1,
            devices = mapOf("device-b" to SyncDeviceState("device-b", "not-a-date", "not-a-date")),
        )
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(status = 404, body = "")
        transport.putQueue += WebDavResponse(status = 201, body = "")
        transport.getQueue += WebDavResponse(status = 200, body = SyncJson.encodeToString(remoteMetadata), etag = "\"m1\"")
        transport.putQueue += WebDavResponse(status = 204, body = "")
        val store = FakeStore(payload())

        engine(store, FakeSettings(deviceIdValue = "device-a"), transport).sync(config())

        // device-b might still hold a copy of any tombstone we are about to purge.
        assertThat(store.compactedCutoffs).isEmpty()
    }

    @Test
    fun publishesThisDevicesAcknowledgement() = runTest {
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(status = 404, body = "")
        transport.putQueue += WebDavResponse(status = 201, body = "")
        transport.getQueue += WebDavResponse(status = 404, body = "")
        transport.putQueue += WebDavResponse(status = 201, body = "")
        val settings = FakeSettings(deviceIdValue = "device-a")

        engine(FakeStore(payload()), settings, transport).sync(config())

        val metadataPut = transport.requests
            .filter { it.method == "PUT" && it.url.endsWith("$DEFAULT_SYNC_FILE_NAME.meta.json") }
            .single()
        assertThat(metadataPut.headers).containsEntry("If-None-Match", "*")
        assertThat(metadataPut.body).contains("\"device-a\"")
        assertThat(settings.cachedMetadata?.devices?.get("device-a")?.acknowledgedThrough)
            .isEqualTo("2026-06-01T12:00:00.000Z")
    }

    @Test
    fun aFailedMetadataSyncStillLeavesThePayloadUploaded() = runTest {
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(status = 404, body = "")
        transport.putQueue += WebDavResponse(status = 201, body = "")
        transport.getQueue += WebDavResponse(status = 500, body = "")
        val settings = FakeSettings()
        val warnings = mutableListOf<String>()

        val completedAt = engine(
            store = FakeStore(payload()),
            settings = settings,
            transport = transport,
            onWarning = { message, _ -> warnings += message },
        ).sync(config())

        assertThat(completedAt).isEqualTo(now)
        assertThat(settings.savedBase).isNotNull()
        assertThat(warnings).hasSize(1)
    }

    @Test
    fun rejectsMetadataTheServerRefusesToGuardWithACondition() = runTest {
        val transport = ScriptedTransport()
        transport.getQueue += WebDavResponse(status = 404, body = "")
        transport.putQueue += WebDavResponse(status = 201, body = "")
        // 200 with neither ETag nor Last-Modified: the legacy client bailed out rather than risk a
        // last-writer-wins overwrite of another device's acknowledgements.
        transport.getQueue += WebDavResponse(status = 200, body = """{"version":1,"devices":{}}""")
        val store = FakeStore(payload())

        engine(store, FakeSettings(), transport).sync(config())

        assertThat(store.compactedCutoffs).isEmpty()
    }

    // -----------------------------------------------------------------------
    // testConnection and config validation
    // -----------------------------------------------------------------------

    @Test
    fun acceptsAMultiStatusPropfindResponse() = runTest {
        val transport = ScriptedTransport()
        transport.propfindQueue += WebDavResponse(status = 207, body = "")

        engine(FakeStore(payload()), FakeSettings(), transport).testConnection(config())

        val propfind = transport.requests.single { it.method == "PROPFIND" }
        assertThat(propfind.headers).containsEntry("Depth", "0")
        assertThat(propfind.url).endsWith("/fueltrack/")
    }

    @Test
    fun reportsBadCredentialsFromPropfind() = runTest {
        val transport = ScriptedTransport()
        transport.propfindQueue += WebDavResponse(status = 401, body = "")

        val error = expectAppException {
            engine(FakeStore(payload()), FakeSettings(), transport).testConnection(config())
        }

        assertThat(error.code).isEqualTo(AppErrorCode.SYNC_AUTH_FAILED)
        assertThat(error.message).isEqualTo("账号或应用密码无效")
    }

    @Test
    fun reportsAMissingDirectoryFromPropfind() = runTest {
        val transport = ScriptedTransport()
        transport.propfindQueue += WebDavResponse(status = 404, body = "")

        val error = expectAppException {
            engine(FakeStore(payload()), FakeSettings(), transport).testConnection(config())
        }

        assertThat(error.message).isEqualTo("WebDAV 同步目录不存在")
    }

    @Test
    fun rejectsPlainHttpExceptOnLoopback() {
        val error = assertThrows(AppException::class.java) {
            SyncEngine.validateConfig(config(url = "http://dav.example.com/fueltrack"))
        }
        assertThat(error.message).isEqualTo("WebDAV 必须使用 HTTPS（本机调试除外）")

        SyncEngine.validateConfig(config(url = "http://localhost:8080/dav"))
        SyncEngine.validateConfig(config(url = "http://127.0.0.1:8080/dav"))
    }

    @Test
    fun rejectsABlankOrMalformedUrl() {
        val blank = assertThrows(AppException::class.java) { SyncEngine.validateConfig(config(url = "")) }
        assertThat(blank.message).isEqualTo("请填写 WebDAV 地址")

        val malformed = assertThrows(AppException::class.java) {
            SyncEngine.validateConfig(config(url = "not a url"))
        }
        assertThat(malformed.message).isEqualTo("WebDAV 地址格式无效")
    }

    @Test
    fun rejectsEncryptionWithAShortPassphrase() {
        val error = assertThrows(AppException::class.java) {
            SyncEngine.validateConfig(config(encryptionEnabled = true, encryptionPassphrase = "short"))
        }
        assertThat(error.code).isEqualTo(AppErrorCode.SYNC_ENCRYPTION_FAILED)
        assertThat(error.message).isEqualTo("启用加密时，同步口令至少需要 8 个字符")
    }

    @Test
    fun targetIsStableAndDistinctPerFileName() {
        assertThat(SyncUrls.target(config())).isEqualTo("https://dav.example.com/remote.php/dav/fueltrack/$DEFAULT_SYNC_FILE_NAME")
        assertThat(SyncUrls.metadataFile(config()).toString()).endsWith("$DEFAULT_SYNC_FILE_NAME.meta.json")
        assertThat(SyncUrls.target(config(url = "https://dav.example.com/remote.php/dav/fueltrack/")))
            .isEqualTo(SyncUrls.target(config()))
    }

    // -----------------------------------------------------------------------
    // Fakes
    // -----------------------------------------------------------------------

    private data class RecordedRequest(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private class ScriptedTransport : WebDavTransport {
        val getQueue = ArrayDeque<WebDavResponse>()
        val putQueue = ArrayDeque<WebDavResponse>()
        val propfindQueue = ArrayDeque<WebDavResponse>()
        val requests = mutableListOf<RecordedRequest>()

        override suspend fun get(url: String): WebDavResponse {
            requests += RecordedRequest("GET", url, emptyMap(), "")
            return getQueue.removeFirstOrNull() ?: error("unexpected GET $url")
        }

        override suspend fun put(url: String, body: String, headers: Map<String, String>): WebDavResponse {
            requests += RecordedRequest("PUT", url, headers, body)
            return putQueue.removeFirstOrNull() ?: error("unexpected PUT $url")
        }

        override suspend fun propfind(url: String, headers: Map<String, String>): WebDavResponse {
            requests += RecordedRequest("PROPFIND", url, headers, "")
            return propfindQueue.removeFirstOrNull() ?: error("unexpected PROPFIND $url")
        }
    }

    private fun ScriptedTransport.payloadPut(): RecordedRequest =
        requests.single { it.method == "PUT" && it.url.endsWith(DEFAULT_SYNC_FILE_NAME) }

    private class FakeStore(var stored: SyncPayloadV1) : SyncStore {
        var merged: SyncPayloadV1? = null
        var savedConflicts: List<DetectedConflict> = emptyList()
        val compactedCutoffs = mutableListOf<String>()

        override suspend fun exportData(): SyncPayloadV1 = stored

        override suspend fun mergeData(remote: SyncPayloadV1) {
            merged = remote
        }

        override suspend fun saveConflicts(conflicts: List<DetectedConflict>) {
            savedConflicts = conflicts
        }

        override suspend fun compactTombstones(cutoff: String): Int {
            compactedCutoffs += cutoff
            return 0
        }
    }

    private class FakeSettings(
        var base: SyncBase = SyncBase.EMPTY,
        var deviceIdValue: String = "device-a",
    ) : SettingsRepository {
        private val secretsFlow = MutableStateFlow(SessionSecrets())
        var savedBase: SyncBase? = null
        var cachedMetadata: SyncMetadata? = null

        override suspend fun config() = WebDavConfig(
            url = "",
            username = "",
            password = "",
            fileName = DEFAULT_SYNC_FILE_NAME,
        )

        override suspend fun saveConfig(config: WebDavConfig) = Unit

        override suspend fun deviceId() = deviceIdValue

        override suspend fun syncBase(target: String) = base

        override suspend fun saveSyncBase(target: String, payload: SyncPayloadV1) {
            savedBase = buildSyncBase(payload)
        }

        override suspend fun metadataCache(target: String) = cachedMetadata

        override suspend fun saveMetadataCache(target: String, metadata: SyncMetadata) {
            cachedMetadata = metadata
        }

        override suspend fun lastError() = null

        override suspend fun recordLastError(error: Throwable) = Unit

        override suspend fun clearLastError() = Unit

        override val secrets: StateFlow<SessionSecrets> get() = secretsFlow

        override suspend fun rememberedPassphrase(): String? = null
    }

    private companion object {
        val FutureRecord = FuelRecord(
            id = "r1",
            vehicleId = "v1",
            date = "2027-01-01",
            odometer = 1000.0,
            liters = 40.0,
            amount = 300.0,
            pumpAmount = 300.0,
            pricePerLiter = 7.5,
            isFull = true,
            station = "",
            note = "",
            createdAt = "2027-01-01T00:00:00.000Z",
            updatedAt = "2027-01-01T00:00:00.000Z",
        )
    }
}
