package com.youhao.fueltrack.domain.time

import java.time.LocalDate
import java.time.ZoneId

/**
 * Local-calendar-date keys.
 *
 * The original web implementation derived these from the browser's local calendar
 * (`getFullYear` / `getMonth` / `getDate`). `LocalDate.now()` uses the platform default
 * zone, so it preserves that behaviour — including the DST/UTC-offset edge cases that the
 * Vue version got wrong.
 */
object LocalDateKeys {

    fun localDateKey(date: LocalDate = LocalDate.now()): String = date.toString()

    fun localMonthKey(date: LocalDate = LocalDate.now()): String =
        "%04d-%02d".format(date.year, date.monthValue)

    /** Local calendar date for [epochMillis] in [zone]. */
    fun dateKeyOf(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        java.time.Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toString()
}
