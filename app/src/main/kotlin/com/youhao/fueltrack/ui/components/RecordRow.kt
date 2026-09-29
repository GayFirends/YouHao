package com.youhao.fueltrack.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.youhao.fueltrack.domain.model.FuelRecord
import com.youhao.fueltrack.ui.formatConsumption
import com.youhao.fueltrack.ui.formatLitersValue
import com.youhao.fueltrack.ui.formatMoney
import com.youhao.fueltrack.ui.formatNumber
import com.youhao.fueltrack.ui.formatPrice
import com.youhao.fueltrack.ui.formatShortDate
import com.youhao.fueltrack.ui.theme.MetricValueSmallStyle
import com.youhao.fueltrack.ui.theme.numeric

/**
 * 一条加油记录，对应 CSS 移动端的 `.record-card`：
 * 左侧日期徽章、中间加油站与规格、右侧金额与优惠。
 *
 * 与旧版的区别是把「日期 + 里程 + 加油站」三段并排改成了徽章 + 两行信息，
 * 金额永远固定在右边——列表纵向扫读时，钱的列是对齐的。
 */
@Composable
fun RecordRow(
    record: FuelRecord,
    modifier: Modifier = Modifier,
    consumption: Double? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (onClick != null) {
                    Modifier.pressable(onClick = onClick, scaleTo = 0.985f, onClickLabel = "编辑 ${record.date} 的加油记录")
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        RecordDateBadge(date = record.date)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.station.ifBlank { "加油记录" },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(formatLitersValue(record.liters))
                    append(" L · ")
                    append(formatPrice(record.pricePerLiter))
                    if (record.isFull) append(" · 满箱")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
            Text(
                text = "${formatNumber(record.odometer)} km",
                style = MaterialTheme.typography.bodySmall.numeric(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
            if (consumption != null && consumption > 0.0) {
                Text(
                    text = formatConsumption(consumption),
                    style = MaterialTheme.typography.bodySmall.numeric(),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (record.note.isNotBlank()) {
                Text(
                    text = record.note,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }

        // `.receipt-amount`: 金额在上，优惠/实付说明在下，右对齐并锁定最小宽度，金额列才拉得齐。
        Column(
            modifier = Modifier.widthIn(min = 72.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = formatMoney(record.amount),
                style = MetricValueSmallStyle.numeric(),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (record.pumpAmount > record.amount) {
                    "省 ${formatMoney(record.pumpAmount - record.amount)}"
                } else {
                    "实付金额"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** `.date-badge`：34×44 的强调色软底方块，右下角一个小缺口。 */
@Composable
private fun RecordDateBadge(date: String) {
    val month = date.substringAfter('-').take(2).trimStart('0').ifBlank { date }
    Column(
        modifier = Modifier
            .widthIn(min = 38.dp)
            .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomEnd = 10.dp, bottomStart = 3.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 徽章里的日期用 short 格式（`6月1日`）的第一段当「日」，避免再解析一遍字符串。
        Text(
            text = dayOf(date),
            style = MetricValueSmallStyle.numeric(),
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            text = "${month} 月",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private fun dayOf(date: String): String = date.takeLast(2).trimStart('0').ifBlank { formatShortDate(date) }
