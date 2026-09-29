package com.youhao.fueltrack.domain.time

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * ISO-8601 instant helpers matching the wire format used by `sync-validation.ts`:
 * timestamps are normalised to UTC with millisecond precision (`toISOString()`).
 */
object Timestamps {

    private val TIMESTAMP_RE = Regex(
        """^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$"""
    )

    fun isTimestamp(text: String): Boolean = TIMESTAMP_RE.matches(text)

    /** Parses [text] and normalises it, or returns null when it is not a valid instant. */
    fun parseOrNull(text: String): Instant? {
        if (!isTimestamp(text)) return null
        return try {
            OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private val ISO_MILLIS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)

    /** `toISOString()` equivalent: UTC, always millisecond precision, `Z` suffix. */
    fun format(instant: Instant): String = ISO_MILLIS.format(instant)

    fun nowIso(): String = format(Instant.now())
}
