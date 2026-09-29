package com.youhao.fueltrack.ui

import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Display formatting for the UI.
 *
 * The legacy web app leaned on the browser's `Intl`/`toLocaleString`; these helpers reproduce the
 * handful of shapes it actually used, and nothing more.
 *
 * Note that the UI deliberately does *not* attempt to render an icon font: Material3 no longer
 * pulls in `material-icons-core`, and pulling in a deprecated icon artifact for a five-screen app
 * is a poor trade. Actions are labelled with text instead, which also reads better in Chinese.
 */

private const val MISSING = "—"

fun formatLiters(value: Double): String = String.format(Locale.getDefault(), "%.2f L", value)

fun formatMoney(value: Double): String = String.format(Locale.getDefault(), "¥%.2f", value)

fun formatPrice(value: Double): String = String.format(Locale.getDefault(), "¥%.2f/L", value)

fun formatConsumption(value: Double): String =
    if (value > 0.0) String.format(Locale.getDefault(), "%.2f L/100km", value) else MISSING

/** Thousands separators, and no decimal point when the odometer is a whole number. */
fun formatOdometer(value: Double): String {
    val format = NumberFormat.getNumberInstance(Locale.getDefault())
    format.minimumFractionDigits = 0
    format.maximumFractionDigits = if (value % 1.0 == 0.0) 0 else 1
    return "${format.format(value)} km"
}

/** `2026-06-01` as stored; shown verbatim so it never disagrees with the record's own date field. */
fun formatDate(date: String): String = date.ifBlank { MISSING }

fun String?.orDash(): String = if (this.isNullOrBlank()) MISSING else this

/** A text field shows an empty string rather than `null`, and rejects partial input like `12.`. */
fun Double?.toFieldText(): String = when {
    this == null -> ""
    this % 1.0 == 0.0 -> this.toLong().toString()
    else -> toString()
}

fun String.toDoubleOrNullField(): Double? = trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()

// ---------------------------------------------------------------------------
// 原 Vue 版把数字和单位拆开排版（`<strong>{{ value }}<small>L / 100 km</small></strong>`），
// 所以这里额外提供「不带单位」的版本，让调用点自己决定单位的位置和字号。
// ---------------------------------------------------------------------------

/** `Intl.NumberFormat('zh-CN', { maximumFractionDigits: 1 })` 的等价物。 */
fun formatNumber(value: Double, maximumFractionDigits: Int = 1): String {
    val format = NumberFormat.getNumberInstance(Locale.getDefault())
    format.minimumFractionDigits = 0
    format.maximumFractionDigits = maximumFractionDigits
    return format.format(value)
}

/** 油耗数值本体；没有可算的区间时给破折号，而不是 `0.0`。 */
fun formatConsumptionValue(value: Double): String =
    if (value > 0.0) String.format(Locale.getDefault(), "%.1f", value) else MISSING

/** 里程数值本体（不带 `km`）。 */
fun formatOdometerValue(value: Double): String = formatNumber(value, if (value % 1.0 == 0.0) 0 else 1)

/** 油量数值本体（不带 `L`）。 */
fun formatLitersValue(value: Double): String = formatNumber(value, 2)

/** 概览页 eyebrow 用：`2026年6月`。 */
fun formatMonthTitle(date: LocalDate = LocalDate.now()): String =
    "${date.year}年${date.monthValue}月"

/** 概览页右上角用：`6月1日 星期一`。 */
fun formatTodayTitle(date: LocalDate = LocalDate.now()): String =
    "${date.monthValue}月${date.dayOfMonth}日 ${weekdayName(date)}"

/** 列表里把 `2026-06-01` 压成 `6月1日`；解析不出来就原样返回，避免静默改成别的日期。 */
fun formatShortDate(date: String): String = runCatching {
    val parsed = LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE)
    "${parsed.monthValue}月${parsed.dayOfMonth}日"
}.getOrDefault(date.ifBlank { MISSING })

private fun weekdayName(date: LocalDate): String =
    when (date.dayOfWeek.value) {
        1 -> "星期一"
        2 -> "星期二"
        3 -> "星期三"
        4 -> "星期四"
        5 -> "星期五"
        6 -> "星期六"
        else -> "星期日"
    }
