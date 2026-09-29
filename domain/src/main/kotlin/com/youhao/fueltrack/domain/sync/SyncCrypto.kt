package com.youhao.fueltrack.domain.sync

import com.youhao.fueltrack.domain.error.AppErrorCode
import com.youhao.fueltrack.domain.error.AppException
import com.youhao.fueltrack.domain.model.CryptoParams
import com.youhao.fueltrack.domain.model.EncryptedSyncEnvelopeV2
import com.youhao.fueltrack.domain.model.SyncPayloadV1
import com.youhao.fueltrack.domain.time.Timestamps
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Port of `src/services/sync-crypto.ts` — the AES-GCM container that wraps a sync payload
 * when the user enables encryption.
 *
 * Everything here is plain JDK (no Android classes) so the container can be unit-tested on the
 * JVM and, more importantly, cross-checked against envelopes produced by the old WebView client.
 * The formats must match exactly:
 *
 *  * key derivation — PBKDF2-HMAC-SHA-256 over the UTF-8 passphrase bytes, 310 000 iterations
 *  * body — gzip (RFC 1952), matching the browser's `CompressionStream('gzip')`
 *  * cipher — AES-256-GCM with a 128-bit tag, 16-byte salt, 12-byte IV
 *  * encoding — standard base64 with padding, exactly what `btoa` emits
 *
 * PBKDF2 is implemented here rather than delegated to `SecretKeyFactory` because that API takes
 * the passphrase as a `char[]` and leaves the byte encoding up to the provider. Controlling the
 * encoding explicitly is what guarantees the derived key matches the browser's.
 */
object SyncCrypto {

    const val PBKDF2_ITERATIONS: Int = 310_000
    const val SALT_LENGTH: Int = 16
    const val IV_LENGTH: Int = 12
    const val MIN_PASSPHRASE_LENGTH: Int = 8

    private const val MIN_ITERATIONS = 100_000
    private const val MAX_ITERATIONS = 2_000_000
    private const val KEY_LENGTH_BYTES = 32
    private const val GCM_TAG_BITS = 128
    private const val ALGORITHM = "AES-GCM"
    private const val KDF = "PBKDF2-SHA-256"
    private const val COMPRESSION = "gzip"

    private val random = SecureRandom()

    // -----------------------------------------------------------------------
    // Encrypt
    // -----------------------------------------------------------------------

    fun encrypt(payload: SyncPayloadV1, passphrase: String): EncryptedSyncEnvelopeV2 =
        encryptWith(
            payload = payload,
            passphrase = passphrase,
            salt = ByteArray(SALT_LENGTH).also(random::nextBytes),
            iv = ByteArray(IV_LENGTH).also(random::nextBytes),
            createdAt = Timestamps.nowIso(),
        )

    /**
     * Salt, IV and timestamp are injectable so [SyncCryptoTest] can reproduce a byte-exact
     * envelope recorded from the browser implementation. Production code never passes them.
     */
    internal fun encryptWith(
        payload: SyncPayloadV1,
        passphrase: String,
        salt: ByteArray,
        iv: ByteArray,
        createdAt: String,
    ): EncryptedSyncEnvelopeV2 {
        if (passphrase.length < MIN_PASSPHRASE_LENGTH) {
            throw AppException(AppErrorCode.SYNC_ENCRYPTION_FAILED, "同步口令至少需要 8 个字符")
        }
        require(salt.size == SALT_LENGTH) { "salt must be $SALT_LENGTH bytes" }
        require(iv.size == IV_LENGTH) { "iv must be $IV_LENGTH bytes" }

        val plaintext = SyncJson.encodeToString(SyncPayloadV1.serializer(), payload).toByteArray(Charsets.UTF_8)
        val key = deriveKey(passphrase, salt, PBKDF2_ITERATIONS)
        val ciphertext = cipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(gzip(plaintext))

        return EncryptedSyncEnvelopeV2(
            version = 2,
            encrypted = true,
            createdAt = createdAt,
            crypto = CryptoParams(
                algorithm = ALGORITHM,
                kdf = KDF,
                iterations = PBKDF2_ITERATIONS,
                salt = base64(salt),
                iv = base64(iv),
            ),
            compression = COMPRESSION,
            ciphertext = base64(ciphertext),
        )
    }

    // -----------------------------------------------------------------------
    // Decrypt
    // -----------------------------------------------------------------------

    /** Mirrors `isEncryptedSyncDocument`: only a v2 envelope flagged `encrypted` is encrypted. */
    fun isEncrypted(document: JsonElement): Boolean {
        val root = document as? JsonObject ?: return false
        val version = (root["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        val encrypted = (root["encrypted"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        return version == 2.0 && encrypted == true
    }

    /**
     * Unwraps whatever the server returned: a plain v1 payload passes straight through the
     * validator, an encrypted envelope is decrypted first.
     */
    fun decrypt(document: JsonElement, passphrase: String): SyncPayloadV1 {
        if (!isEncrypted(document)) return SyncPayloadValidator.validate(document)

        if (passphrase.isEmpty()) {
            throw AppException(AppErrorCode.SYNC_ENCRYPTION_FAILED, "云端文件已加密，请输入同步口令")
        }
        val envelope = validateEnvelope(document)

        return try {
            val salt = fromBase64(envelope.crypto.salt)
            val iv = fromBase64(envelope.crypto.iv)
            if (salt.size != SALT_LENGTH || iv.size != IV_LENGTH) {
                throw IllegalArgumentException("invalid parameters")
            }
            val key = deriveKey(passphrase, salt, envelope.crypto.iterations)
            val compressed = cipher(Cipher.DECRYPT_MODE, key, iv).doFinal(fromBase64(envelope.ciphertext))
            val text = String(gunzip(compressed), Charsets.UTF_8)
            SyncPayloadValidator.validate(SyncJson.parseToJsonElement(text))
        } catch (error: AppException) {
            throw error
        } catch (error: Throwable) {
            // Wrong passphrase and a corrupted body are indistinguishable at this layer, and
            // saying so is more useful than leaking a padding or GCM-tag failure.
            throw AppException(AppErrorCode.SYNC_ENCRYPTION_FAILED, "同步口令错误或云端文件已损坏", error)
        }
    }

    /** Mirrors `validateEnvelope`, raising `SYNC_FORMAT_INVALID` for every structural problem. */
    fun validateEnvelope(document: JsonElement): EncryptedSyncEnvelopeV2 {
        val root = document as? JsonObject ?: fail("不支持的同步文件版本")
        val version = (root["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        val encrypted = (root["encrypted"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        if (version != 2.0 || encrypted != true) fail("不支持的同步文件版本")

        val crypto = root["crypto"] as? JsonObject ?: fail("加密同步文件结构不完整")
        val algorithm = crypto.stringOrNull("algorithm")
        val kdf = crypto.stringOrNull("kdf")
        val compression = root.stringOrNull("compression")
        if (algorithm != ALGORITHM || kdf != KDF || compression != COMPRESSION) {
            fail("不支持的同步加密算法")
        }

        val iterations = (crypto["iterations"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        if (iterations == null ||
            iterations != Math.floor(iterations) ||
            iterations < MIN_ITERATIONS ||
            iterations > MAX_ITERATIONS
        ) {
            fail("同步文件的密钥派生参数无效")
        }

        val createdAt = root.stringOrNull("createdAt")
        val salt = crypto.stringOrNull("salt")
        val iv = crypto.stringOrNull("iv")
        val ciphertext = root.stringOrNull("ciphertext")
        if (createdAt == null || salt == null || iv == null || ciphertext == null) {
            fail("加密同步文件结构不完整")
        }

        return EncryptedSyncEnvelopeV2(
            version = 2,
            encrypted = true,
            createdAt = createdAt,
            crypto = CryptoParams(
                algorithm = algorithm,
                kdf = kdf,
                iterations = iterations.toInt(),
                salt = salt,
                iv = iv,
            ),
            compression = compression,
            ciphertext = ciphertext,
        )
    }

    // -----------------------------------------------------------------------
    // Primitives
    // -----------------------------------------------------------------------

    /** RFC 2898 PBKDF2 with HMAC-SHA-256, over explicit UTF-8 passphrase bytes. */
    fun pbkdf2(passphrase: String, salt: ByteArray, iterations: Int, keyLengthBytes: Int): ByteArray {
        require(iterations > 0) { "iterations must be positive" }
        require(keyLengthBytes > 0) { "keyLengthBytes must be positive" }

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(passphrase.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val macLength = mac.macLength
        val blockCount = (keyLengthBytes + macLength - 1) / macLength

        val output = ByteArray(blockCount * macLength)
        val seed = ByteArray(salt.size + 4)
        salt.copyInto(seed)

        for (blockIndex in 1..blockCount) {
            seed[salt.size] = (blockIndex ushr 24).toByte()
            seed[salt.size + 1] = (blockIndex ushr 16).toByte()
            seed[salt.size + 2] = (blockIndex ushr 8).toByte()
            seed[salt.size + 3] = blockIndex.toByte()

            var u = mac.doFinal(seed)
            val accumulator = u.copyOf()
            for (round in 2..iterations) {
                u = mac.doFinal(u)
                for (index in accumulator.indices) {
                    accumulator[index] = (accumulator[index].toInt() xor u[index].toInt()).toByte()
                }
            }
            accumulator.copyInto(output, (blockIndex - 1) * macLength)
        }
        return output.copyOf(keyLengthBytes)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec =
        SecretKeySpec(pbkdf2(passphrase, salt, iterations, KEY_LENGTH_BYTES), "AES")

    private fun cipher(mode: Int, key: SecretKeySpec, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, key, GCMParameterSpec(GCM_TAG_BITS, iv)) }

    fun gzip(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(input) }
        return out.toByteArray()
    }

    fun gunzip(input: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(input)).use { it.readBytes() }

    fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun fromBase64(text: String): ByteArray = try {
        Base64.getDecoder().decode(text)
    } catch (error: IllegalArgumentException) {
        throw AppException(AppErrorCode.SYNC_FORMAT_INVALID, "加密同步文件包含无效编码", error)
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun fail(message: String): Nothing =
        throw AppException(AppErrorCode.SYNC_FORMAT_INVALID, message)
}
