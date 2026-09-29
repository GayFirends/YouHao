package com.youhao.fueltrack.domain.time

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class TimestampsTest {

    @Test
    fun acceptsPlainAndOffsetTimestamps() {
        assertThat(Timestamps.isTimestamp("2024-01-01T00:00:00Z")).isTrue()
        assertThat(Timestamps.isTimestamp("2024-01-01T00:00:00.000Z")).isTrue()
        assertThat(Timestamps.isTimestamp("2024-01-01T08:00:00+08:00")).isTrue()
        assertThat(Timestamps.isTimestamp("2024-01-01")).isFalse()
        assertThat(Timestamps.isTimestamp("2024-01-01 00:00:00")).isFalse()
        assertThat(Timestamps.isTimestamp("")).isFalse()
    }

    @Test
    fun normalisesEveryOffsetToUtc() {
        assertThat(Timestamps.format(Timestamps.parseOrNull("2024-01-01T08:00:00+08:00")!!))
            .isEqualTo("2024-01-01T00:00:00.000Z")
        assertThat(Timestamps.format(Timestamps.parseOrNull("2024-01-01T00:00:00Z")!!))
            .isEqualTo("2024-01-01T00:00:00.000Z")
    }

    @Test
    fun alwaysEmitsMillisecondPrecision() {
        assertThat(Timestamps.format(Timestamps.parseOrNull("2024-01-01T00:00:00.5Z")!!))
            .isEqualTo("2024-01-01T00:00:00.500Z")
    }

    @Test
    fun returnsNullForUnusableInput() {
        assertThat(Timestamps.parseOrNull("not-a-time")).isNull()
        assertThat(Timestamps.parseOrNull("2024-13-01T00:00:00Z")).isNull()
    }

    @Test
    fun nowIsParseableByItsOwnParser() {
        val now = Timestamps.nowIso()

        assertThat(Timestamps.isTimestamp(now)).isTrue()
        assertThat(Timestamps.parseOrNull(now)).isNotNull()
    }
}
