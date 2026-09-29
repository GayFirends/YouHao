package com.youhao.fueltrack.data.diagnostics

import android.os.Build
import com.youhao.fueltrack.BuildConfig
import com.youhao.fueltrack.domain.error.LastError
import com.youhao.fueltrack.domain.model.SchemaInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the diagnostic report contains. Ported from `src/services/diagnostics.ts`, with two
 * deliberate differences:
 *
 * - `platform` is the constant `"android"`; there is no web build to distinguish any more.
 * - `userAgent` is replaced by the OS release/API level and the device model. The legacy field was
 *   `navigator.userAgent`, which has no native equivalent and would have been noise.
 *
 * The report never carries records, WebDAV URLs, usernames, passwords or encryption passphrases —
 * [privacy] states that in the file itself, and [lastError] stores only the error *code*.
 */
@Serializable
data class DiagnosticReport(
    val generatedAt: String,
    val appVersion: String,
    val platform: String,
    val osVersion: String,
    val deviceModel: String,
    val database: SchemaInfo,
    val syncFormatVersions: List<Int>,
    val deviceId: String,
    val lastError: ReportedLastError?,
    val privacy: String,
)

/**
 * `LastError` with the code flattened to its wire name, so a code this build does not know about
 * still serialises instead of throwing.
 */
@Serializable
data class ReportedLastError(
    val code: String,
    val occurredAt: String,
)

object Diagnostics {

    const val PLATFORM: String = "android"

    const val PRIVACY: String =
        "This report never includes records, WebDAV credentials, URLs, or encryption passphrases."

    /** The sync document formats this build can read and write. */
    val SYNC_FORMAT_VERSIONS: List<Int> = listOf(1, 2)

    private val ReportJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun create(
        generatedAt: String,
        database: SchemaInfo,
        deviceId: String,
        lastError: LastError?,
    ): DiagnosticReport = DiagnosticReport(
        generatedAt = generatedAt,
        appVersion = BuildConfig.VERSION_NAME,
        platform = PLATFORM,
        osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        database = database,
        syncFormatVersions = SYNC_FORMAT_VERSIONS,
        deviceId = deviceId,
        lastError = lastError?.let { ReportedLastError(it.code.wireName, it.occurredAt) },
        privacy = PRIVACY,
    )

    fun render(report: DiagnosticReport): String = ReportJson.encodeToString(report)

    /** e.g. `fuel-track-diagnostics-2026-10-01T08-30-00.000Z.json` — colons are illegal on FAT. */
    fun fileName(generatedAt: String): String =
        "fuel-track-diagnostics-${generatedAt.replace(':', '-')}.json"
}
