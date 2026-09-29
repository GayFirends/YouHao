package com.youhao.fueltrack.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.youhao.fueltrack.domain.model.FuelRecord
import com.youhao.fueltrack.ui.*
import com.youhao.fueltrack.ui.theme.numeric

/** Receipt-like rows: a quiet date, aligned amount and a single divider. */
@Composable
fun RecordRow(record: FuelRecord, modifier: Modifier = Modifier, consumption: Double? = null, onClick: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "编辑加油记录", onClick = onClick) else Modifier)
                .padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.widthIn(min = 30.dp)) {
                Text(record.date.takeLast(2), style = MaterialTheme.typography.titleLarge.numeric())
                Text("${record.date.substringAfter('-').take(2).trimStart('0')}月", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(record.station.ifBlank { "加油记录" }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatMoney(record.amount), style = MaterialTheme.typography.titleSmall.numeric())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${formatLitersValue(record.liters)} L · ${if (record.isFull) "已加满" else "未加满"}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatOdometer(record.odometer), style = MaterialTheme.typography.labelSmall.numeric(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (consumption != null && consumption > 0) Text("区间油耗 ${formatConsumption(consumption)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (record.pumpAmount > record.amount) Text("优惠 ${formatMoney(record.pumpAmount - record.amount)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (record.note.isNotBlank()) Text(record.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
