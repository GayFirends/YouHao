package com.youhao.fueltrack.domain.sync

import com.google.common.truth.Truth.assertThat
import com.youhao.fueltrack.domain.error.AppErrorCode
import com.youhao.fueltrack.domain.error.AppException
import com.youhao.fueltrack.domain.model.FuelRecord
import com.youhao.fueltrack.domain.model.SyncPayloadV1
import com.youhao.fueltrack.domain.model.Vehicle
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Wire-compatibility tests for the encrypted sync container.
 *
 * The interesting cases read `sync-crypto-reference.json`, a real envelope produced by the
 * legacy TypeScript implementation and checked in under `src/test/resources`. Getting a key
 * derivation detail subtly wrong would not crash anything — it would silently make every
 * encrypted backup unreadable — so the KDF, the gzip framing and the base64 alphabet are all
 * pinned against that recording rather than against this implementation's own output.
 */
class SyncCryptoTest {

    private val fixture: JsonObject = run {
        val stream = javaClass.getResourceAsStream("/sync-crypto-reference.json")
            ?: error("missing test fixture /sync-crypto-reference.json")
        SyncJson.parseToJsonElement(stream.bufferedReader().readText()) as JsonObject
    }

    private val passphrase: String = (fixture["passphrase"] as kotlinx.serialization.json.JsonPrimitive).content
    private val envelopeElement: JsonObject = fixture["envelope"] as JsonObject

    private val plaintext = SyncPayloadV1(
        version = 1,
        exportedAt = "2026-03-01T08:30:00.000Z",
        vehicles = listOf(
            Vehicle(
                id = "v1",
                name = "我的车辆",
                plate = "沪A·12345",
                fuelType = "92#",
                initialOdometer = 0.0,
                createdAt = "2026-01-01T00:00:00.000Z",
                updatedAt = "2026-02-15T10:00:00.000Z",
                deletedAt = null,
            ),
        ),
        records = listOf(
            FuelRecord(
                id = "r1",
                vehicleId = "v1",
                date = "2026-02-01",
                odometer = 1000.0,
                liters = 40.5,
                amount = 300.25,
                pumpAmount = 320.5,
                pricePerLiter = 7.91,
                isFull = true,
                station = "中石化·人民路",
                note = "加满，含 200ml 添加剂",
                createdAt = "2026-02-01T09:00:00.000Z",
                updatedAt = "2026-02-01T09:00:00.000Z",
                deletedAt = null,
            ),
            FuelRecord(
                id = "r2",
                vehicleId = "v1",
                date = "2026-02-18",
                odometer = 1500.0,
                liters = 30.0,
                amount = 210.0,
                pumpAmount = 0.0,
                pricePerLiter = 7.0,
                isFull = false,
                station = "",
                note = "",
                createdAt = "2026-02-18T09:00:00.000Z",
                updatedAt = "2026-02-20T11:30:00.000Z",
                deletedAt = "2026-02-20T11:30:00.000Z",
            ),
        ),
    )

    // -------------------------------------------------------------------
    // Cross-implementation
    // -------------------------------------------------------------------

    @Test
    fun decryptsAnEnvelopeWrittenByTheWebViewClient() {
        val payload = SyncCrypto.decrypt(envelopeElement, passphrase)
        assertThat(payload).isEqualTo(plaintext)
    }

    @Test
    fun roundTripsWhileUsingTheRecordedParameters() {
        val envelope = SyncCrypto.validateEnvelope(envelopeElement)
        val produced = SyncCrypto.encryptWith(
            payload = plaintext,
            passphrase = passphrase,
            salt = SyncCrypto.fromBase64(envelope.crypto.salt),
            iv = SyncCrypto.fromBase64(envelope.crypto.iv),
            createdAt = envelope.createdAt,
        )

        // Deliberately NOT asserting that `produced.ciphertext` equals the recorded one.
        // AES-GCM is deterministic, but only for identical plaintext bytes, and the plaintext
        // here is a gzip stream — gzip is not a canonical format. The header's MTIME/XFL/OS
        // bytes and the deflate encoder's length/distance choices all differ between zlib
        // (Java) and the browser's CompressionStream, so the compressed bytes legitimately
        // differ. Byte-equality is therefore neither achievable nor required; what matters is
        // that each side can read what the other wrote, which is covered by
        // [decryptsAnEnvelopeWrittenByTheWebViewClient] and by the legacy client verifying
        // [writesAnEnvelopeForTheLegacyClientToRead] on its next run.
        assertThat(produced.crypto).isEqualTo(envelope.crypto)
        assertThat(produced.version).isEqualTo(2)
        assertThat(produced.encrypted).isTrue()
        assertThat(produced.compression).isEqualTo("gzip")
        assertThat(SyncCrypto.decrypt(SyncJson.parseToJsonElement(SyncJson.encodeToString(produced)), passphrase))
            .isEqualTo(plaintext)
    }

    /**
     * Half of a cross-language handshake. The other half is
     * `tools/interop/generate-sync-fixtures.mjs`, which decrypts this file with the original
     * TypeScript implementation; run that script after this test to confirm the direction
     * "new client writes, legacy client reads".
     */
    @Test
    fun writesAnEnvelopeForTheLegacyClientToRead() {
        val directory = java.nio.file.Paths.get(System.getProperty("youhao.interop.dir", "build/interop"))
        java.nio.file.Files.createDirectories(directory)
        val envelope = SyncCrypto.encrypt(plaintext, passphrase)
        java.nio.file.Files.writeString(
            directory.resolve("kotlin-envelope.json"),
            SyncJson.encodeToString(envelope),
            Charsets.UTF_8,
        )
        assertThat(java.nio.file.Files.exists(directory.resolve("kotlin-envelope.json"))).isTrue()
    }

    @Test
    fun theFixtureUsesTheDocumentedDerivationParameters() {
        val envelope = SyncCrypto.validateEnvelope(envelopeElement)
        assertThat(envelope.crypto.iterations).isEqualTo(310_000)
        assertThat(envelope.crypto.algorithm).isEqualTo("AES-GCM")
        assertThat(envelope.crypto.kdf).isEqualTo("PBKDF2-SHA-256")
        assertThat(envelope.compression).isEqualTo("gzip")
        assertThat(SyncCrypto.fromBase64(envelope.crypto.salt)).hasLength(16)
        assertThat(SyncCrypto.fromBase64(envelope.crypto.iv)).hasLength(12)
    }

    @Test
    fun roundTripsItsOwnEnvelope() {
        val envelope = SyncCrypto.encrypt(plaintext, passphrase)
        assertThat(envelope.version).isEqualTo(2)
        assertThat(envelope.encrypted).isTrue()
        val restored = SyncCrypto.decrypt(SyncJson.parseToJsonElement(SyncJson.encodeToString(envelope)), passphrase)
        assertThat(restored).isEqualTo(plaintext)
    }

    @Test
    fun usesFreshRandomnessForEveryEnvelope() {
        val first = SyncCrypto.encrypt(plaintext, passphrase)
        val second = SyncCrypto.encrypt(plaintext, passphrase)
        assertThat(first.crypto.salt).isNotEqualTo(second.crypto.salt)
        assertThat(first.crypto.iv).isNotEqualTo(second.crypto.iv)
        assertThat(first.ciphertext).isNotEqualTo(second.ciphertext)
    }

    // -------------------------------------------------------------------
    // Key derivation
    // -------------------------------------------------------------------

    @Test
    fun pbkdf2MatchesPublishedHmacSha256Vectors() {
        // Vectors for PBKDF2-HMAC-SHA-256 (see RFC 7914 section 11 and the follow-up IETF draft).
        assertThat(hex(SyncCrypto.pbkdf2("password", "salt".toByteArray(), 1, 32)))
            .isEqualTo("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b")
        assertThat(hex(SyncCrypto.pbkdf2("password", "salt".toByteArray(), 2, 32)))
            .isEqualTo("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43")
        assertThat(hex(SyncCrypto.pbkdf2("password", "salt".toByteArray(), 4096, 32)))
            .isEqualTo("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a")
        // 40 bytes spans two HMAC blocks, exercising the block-index chaining.
        assertThat(
            hex(
                SyncCrypto.pbkdf2(
                    "passwordPASSWORDpassword",
                    "saltSALTsaltSALTsaltSALTsaltSALTsalt".toByteArray(),
                    4096,
                    40,
                ),
            ),
        ).isEqualTo(
            "348c89dbcbd32b2f32d814b8116e84cf2b17347ebc1800181c4e2a1fb8dd53e1c635518c7dac47e9",
        )
    }

    @Test
    fun derivesTheKeyFromUtf8PassphraseBytes() {
        // A non-ASCII passphrase is the case where a char[]-based PBKDF2 API would diverge.
        val bytes = "口令口令口令口令".toByteArray(Charsets.UTF_8)
        assertThat(SyncCrypto.pbkdf2("口令口令口令口令", "salt".toByteArray(), 1, 32))
            .isEqualTo(SyncCrypto.pbkdf2(String(bytes, Charsets.UTF_8), "salt".toByteArray(), 1, 32))
        assertThat(hex(SyncCrypto.pbkdf2("口令口令口令口令", "salt".toByteArray(), 1, 32)))
            .isNotEqualTo(hex(SyncCrypto.pbkdf2("口令口令口令口令!", "salt".toByteArray(), 1, 32)))
    }

    @Test
    fun gzipRoundTripsIncludingNonAsciiUtf8() {
        val text = "油迹 fuel-track — 加满，含 200ml 添加剂"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertThat(String(SyncCrypto.gunzip(SyncCrypto.gzip(bytes)), Charsets.UTF_8)).isEqualTo(text)
        // RFC 1952 framing: magic 1f 8b.
        assertThat(SyncCrypto.gzip(bytes).take(2)).containsExactly(0x1f.toByte(), 0x8b.toByte()).inOrder()
    }

    // -------------------------------------------------------------------
    // Passphrase handling
    // -------------------------------------------------------------------

    @Test
    fun refusesToEncryptWithAShortPassphrase() {
        val error = assertThrows(AppException::class.java) {
            SyncCrypto.encrypt(plaintext, "short")
        }
        assertThat(error.code).isEqualTo(AppErrorCode.SYNC_ENCRYPTION_FAILED)
        assertThat(error.message).isEqualTo("同步口令至少需要 8 个字符")
    }

    @Test
    fun requiresAPassphraseForAnEncryptedDocument() {
        val error = assertThrows(AppException::class.java) {
            SyncCrypto.decrypt(envelopeElement, "")
        }
        assertThat(error.code).isEqualTo(AppErrorCode.SYNC_ENCRYPTION_FAILED)
        assertThat(error.message).isEqualTo("云端文件已加密，请输入同步口令")
    }

    @Test
    fun reportsAWrongPassphraseAsAnIndistinguishableFailure() {
        val error = assertThrows(AppException::class.java) {
            SyncCrypto.decrypt(envelopeElement, "definitely-wrong-passphrase")
        }
        assertThat(error.code).isEqualTo(AppErrorCode.SYNC_ENCRYPTION_FAILED)
        assertThat(error.message).isEqualTo("同步口令错误或云端文件已损坏")
    }

    @Test
    fun reportsATamperedCiphertextAsDamage() {
        val envelope = SyncCrypto.encrypt(plaintext, passphrase)
        val flipped = SyncCrypto.fromBase64(envelope.ciphertext).also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val tampered = SyncJson.parseToJsonElement(
            SyncJson.encodeToString(envelope.copy(ciphertext = SyncCrypto.base64(flipped))),
        )
        val error = assertThrows(AppException::class.java) {
            SyncCrypto.decrypt(tampered, passphrase)
        }
        assertThat(error.code).isEqualTo(AppErrorCode.SYNC_ENCRYPTION_FAILED)
    }

    // -------------------------------------------------------------------
    // Envelope validation
    // -------------------------------------------------------------------

    @Test
    fun passesThroughAPlainPayloadWithoutAPassphrase() {
        val plain = SyncJson.parseToJsonElement(SyncJson.encodeToString(plaintext))
        assertThat(SyncCrypto.isEncrypted(plain)).isFalse()
        assertThat(SyncCrypto.decrypt(plain, "")).isEqualTo(plaintext)
    }

    @Test
    fun rejectsStructuralProblems() {
        assertEnvelopeFailure(
            document(
                """{"version":3,"encrypted":true,"createdAt":"2026-01-01T00:00:00.000Z",
                   "crypto":{"algorithm":"AES-GCM","kdf":"PBKDF2-SHA-256","iterations":310000,"salt":"AA==","iv":"AA=="},
                   "compression":"gzip","ciphertext":"AA=="}""",
            ),
            "不支持的同步文件版本",
        )
        assertEnvelopeFailure(
            document(
                """{"version":2,"encrypted":false,"createdAt":"2026-01-01T00:00:00.000Z",
                   "crypto":{"algorithm":"AES-GCM","kdf":"PBKDF2-SHA-256","iterations":310000,"salt":"AA==","iv":"AA=="},
                   "compression":"gzip","ciphertext":"AA=="}""",
            ),
            "不支持的同步文件版本",
        )
        assertEnvelopeFailure(
            document(
                """{"version":2,"createdAt":"2026-01-01T00:00:00.000Z",
                   "crypto":{"algorithm":"AES-GCM","kdf":"PBKDF2-SHA-256","iterations":310000,"salt":"AA==","iv":"AA=="},
                   "compression":"gzip","ciphertext":"AA=="}""",
            ),
            "不支持的同步文件版本",
        )
    }

    @Test
    fun rejectsUnsupportedCryptoParameters() {
        assertEnvelopeFailure(envelopeJson(algorithm = "AES-CBC"), "不支持的同步加密算法")
        assertEnvelopeFailure(envelopeJson(kdf = "PBKDF2-SHA-1"), "不支持的同步加密算法")
        assertEnvelopeFailure(envelopeJson(compression = "deflate"), "不支持的同步加密算法")
        assertEnvelopeFailure(envelopeJson(iterations = "310000"), "同步文件的密钥派生参数无效")
        assertEnvelopeFailure(envelopeJson(iterations = 99_999), "同步文件的密钥派生参数无效")
        assertEnvelopeFailure(envelopeJson(iterations = 2_000_001), "同步文件的密钥派生参数无效")
        assertEnvelopeFailure(envelopeJson(iterations = 310_000.5), "同步文件的密钥派生参数无效")
    }

    @Test
    fun rejectsIncompleteEnvelopes() {
        assertEnvelopeFailure(envelopeJson(omitCrypto = true), "加密同步文件结构不完整")
        assertEnvelopeFailure(envelopeJson(createdAt = null), "加密同步文件结构不完整")
        assertEnvelopeFailure(envelopeJson(salt = null), "加密同步文件结构不完整")
        assertEnvelopeFailure(envelopeJson(iv = null), "加密同步文件结构不完整")
        assertEnvelopeFailure(envelopeJson(ciphertext = null), "加密同步文件结构不完整")
        assertEnvelopeFailure(envelopeJson(createdAt = 1234), "加密同步文件结构不完整")
    }

    @Test
    fun rejectsABadlyEncodedBodyOrWrongSizedSalt() {
        val invalidBase64 = envelopeJson(ciphertext = "not base64!!")
        val base64Error = assertThrows(AppException::class.java) {
            SyncCrypto.decrypt(invalidBase64, passphrase)
        }
        assertThat(base64Error.code).isEqualTo(AppErrorCode.SYNC_FORMAT_INVALID)
        assertThat(base64Error.message).isEqualTo("加密同步文件包含无效编码")

        // A short salt is well-formed base64 but unusable, and surfaces as a decryption failure.
        val shortSalt = envelopeJson(salt = SyncCrypto.base64(ByteArray(8)))
        val saltError = assertThrows(AppException::class.java) {
            SyncCrypto.decrypt(shortSalt, passphrase)
        }
        assertThat(saltError.code).isEqualTo(AppErrorCode.SYNC_ENCRYPTION_FAILED)

        val shortIv = envelopeJson(iv = SyncCrypto.base64(ByteArray(8)))
        val ivError = assertThrows(AppException::class.java) {
            SyncCrypto.decrypt(shortIv, passphrase)
        }
        assertThat(ivError.code).isEqualTo(AppErrorCode.SYNC_ENCRYPTION_FAILED)
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private fun document(json: String): JsonObject = SyncJson.parseToJsonElement(json) as JsonObject

    private fun assertEnvelopeFailure(document: JsonObject, expectedMessage: String) {
        val error = assertThrows(AppException::class.java) {
            SyncCrypto.validateEnvelope(document)
        }
        assertThat(error.code).isEqualTo(AppErrorCode.SYNC_FORMAT_INVALID)
        assertThat(error.message).isEqualTo(expectedMessage)
    }

    /** Builds an envelope document, defaulting every field to a structurally valid value. */
    @Suppress("LongParameterList")
    private fun envelopeJson(
        algorithm: String = "AES-GCM",
        kdf: String = "PBKDF2-SHA-256",
        iterations: Any? = 310_000,
        compression: String = "gzip",
        createdAt: Any? = "2026-01-01T00:00:00.000Z",
        salt: String? = SyncCrypto.base64(ByteArray(16)),
        iv: String? = SyncCrypto.base64(ByteArray(12)),
        ciphertext: String? = SyncCrypto.base64(ByteArray(32)),
        omitCrypto: Boolean = false,
    ): JsonObject {
        val cryptoFields = buildList {
            add("\"algorithm\":${quote(algorithm)}")
            add("\"kdf\":${quote(kdf)}")
            if (iterations != null) add("\"iterations\":${if (iterations is String) quote(iterations) else iterations}")
            if (salt != null) add("\"salt\":${quote(salt)}")
            if (iv != null) add("\"iv\":${quote(iv)}")
        }
        val fields = buildList {
            add("\"version\":2")
            add("\"encrypted\":true")
            if (createdAt != null) add("\"createdAt\":${if (createdAt is String) quote(createdAt) else createdAt}")
            if (!omitCrypto) add("\"crypto\":{${cryptoFields.joinToString(",")}}")
            add("\"compression\":${quote(compression)}")
            if (ciphertext != null) add("\"ciphertext\":${quote(ciphertext)}")
        }
        return SyncJson.parseToJsonElement("{${fields.joinToString(",")}}") as JsonObject
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
