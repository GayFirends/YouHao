package com.youhao.fueltrack.ui.overview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.youhao.fueltrack.appContainer
import com.youhao.fueltrack.domain.calc.ConsumptionInterval
import com.youhao.fueltrack.ui.components.*
import com.youhao.fueltrack.ui.*
import com.youhao.fueltrack.ui.theme.numeric

@Composable
fun OverviewScreen(
    onOpenRecord: (String) -> Unit, onOpenConflicts: () -> Unit,
    onAddRecord: (String?) -> Unit, onShowRecords: (String?) -> Unit,
    modifier: Modifier = Modifier, isActive: Boolean = true,
) {
    val container = LocalContext.current.appContainer
    val vm: OverviewViewModel = viewModel(factory = viewModelFactory { initializer { OverviewViewModel(container.store, container.settings) } })
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(isActive) { if (isActive) vm.refresh(); onPauseOrDispose {} }
    var vehicleMenu by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PageHeader("每一程，都心中有数", "油迹", Modifier.weight(1f))
            if (state.vehicles.isNotEmpty()) Box {
                TextButton(onClick = { vehicleMenu = true }) {
                    Text(state.selectedVehicleName ?: "选择车辆", modifier = Modifier.widthIn(max = 128.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = vehicleMenu, onDismissRequest = { vehicleMenu = false }) {
                    state.vehicles.forEach { vehicle ->
                        DropdownMenuItem(text = { Text(vehicle.name) }, onClick = { vm.selectVehicle(vehicle.id); vehicleMenu = false })
                    }
                }
            }
        }
        state.error?.let { MessageCard(it, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) }
        state.lastError?.let {
            MessageCard("上次同步失败：${it.code.guidance}", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer,
                trailing = { TextButton(onClick = vm::clearLastError) { Text("清除") } })
        }
        if (state.conflictCount > 0) MessageCard("${state.conflictCount} 条同步冲突待处理", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer,
            trailing = { TextButton(onClick = onOpenConflicts) { Text("处理") } })
        when {
            state.loading -> EmptyHint("正在载入…")
            state.vehicles.isEmpty() -> EmptyHint("请先到「车辆」页添加一辆车")
            else -> {
                Text("平均油耗", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
                Row(Modifier.padding(top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                    Text(if (state.averageConsumption > 0) formatConsumptionValue(state.averageConsumption) else "—",
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 80.sp, lineHeight = 92.sp, fontWeight = FontWeight.Normal, letterSpacing = (-4).sp).numeric())
                    Text("L / 100 km", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, bottom = 17.dp))
                }
                Text(if (state.intervalCount > 0) "基于 ${state.intervalCount} 个满箱区间 · ${formatNumber(state.measuredDistance)} km" else "记录两次满箱后，即可计算油耗",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PanelCard(Modifier.padding(top = 24.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            SummaryValue("本月油费", formatMoney(state.monthCost), "${state.monthRecordCount} 次加油")
                        }
                        Column(Modifier.weight(1f)) {
                            SummaryValue("记录里程", "${formatNumber(state.recordedDistance)} km", "累计 ${state.summary?.recordCount ?: 0} 次加油")
                        }
                    }
                }
                PrimaryButton("＋  记一笔加油", { onAddRecord(state.selectedVehicleId) }, Modifier.fillMaxWidth().padding(top = 18.dp))
                Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("最近加油", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { onShowRecords(state.selectedVehicleId) }) { Text("全部记录 →") }
                }
                if (state.recentRecords.isEmpty()) EmptyHint("第一笔加油，从这里开始")
                state.recentRecords.take(2).forEach { record -> RecordRow(record, onClick = { onOpenRecord(record.id) }) }
                ExpandableSection("油耗趋势 · 最近 ${state.intervals.size} 个区间", Modifier.padding(top = 16.dp)) {
                    if (state.intervals.isEmpty()) EmptyHint("连续两次加满后，显示油耗趋势和区间明细")
                    else {
                        ConsumptionTrend(state.intervals.reversed())
                        state.intervals.forEach { interval ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(formatShortDate(interval.record.date), style = MaterialTheme.typography.bodyMedium)
                                    Text("${formatOdometer(interval.distance)} · ${formatLiters(interval.liters)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(formatConsumption(interval.consumption), style = MaterialTheme.typography.bodyMedium.numeric())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryValue(label: String, value: String, caption: String) {
    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.titleLarge.numeric(), modifier = Modifier.padding(vertical = 6.dp))
    Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ConsumptionTrend(intervals: List<ConsumptionInterval>) {
    val line = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val low = (intervals.minOf { it.consumption } - 0.5).coerceAtLeast(0.0)
    val high = intervals.maxOf { it.consumption } + 0.5
    val description = intervals.joinToString("；") { "${it.record.date}：${formatConsumption(it.consumption)}" }
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        Text("${formatConsumptionValue(low)} – ${formatConsumptionValue(high)} L / 100 km", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(112.dp).padding(vertical = 12.dp)) {
            for (i in 0..2) drawLine(grid, Offset(0f, size.height * i / 2), Offset(size.width, size.height * i / 2), 1.dp.toPx())
            val points = intervals.mapIndexed { index, item ->
                Offset(if (intervals.size == 1) size.width / 2 else 5.dp.toPx() + (size.width - 10.dp.toPx()) * index / (intervals.size - 1),
                    (size.height * (1 - (item.consumption - low) / (high - low))).toFloat())
            }
            points.zipWithNext().forEach { (a, b) -> drawLine(line, a, b, 2.dp.toPx()) }
            points.forEach { drawCircle(line, 3.dp.toPx(), it) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatShortDate(intervals.first().record.date), style = MaterialTheme.typography.labelSmall)
            Text(formatShortDate(intervals.last().record.date), style = MaterialTheme.typography.labelSmall)
        }
    }
}
