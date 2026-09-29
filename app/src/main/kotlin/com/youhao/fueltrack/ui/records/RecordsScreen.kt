package com.youhao.fueltrack.ui.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.youhao.fueltrack.appContainer
import com.youhao.fueltrack.ui.components.*
import com.youhao.fueltrack.ui.formatMoney
import com.youhao.fueltrack.ui.theme.MetricValueStyle
import com.youhao.fueltrack.ui.theme.numeric
import java.time.YearMonth

@Composable
fun RecordsScreen(
    onAddRecord: (String?) -> Unit, onOpenRecord: (String) -> Unit,
    modifier: Modifier = Modifier, requestedVehicleId: String? = null, requestKey: Int = 0, isActive: Boolean = true,
) {
    val container = LocalContext.current.appContainer
    val vm: RecordsViewModel = viewModel(factory = viewModelFactory { initializer { RecordsViewModel(container.store) } })
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(requestKey) { requestedVehicleId?.let { vm.selectVehicle(it); vm.clearFilters() } }
    LifecycleResumeEffect(isActive) { if (isActive) vm.reload(); onPauseOrDispose {} }
    var monthDialog by rememberSaveable { mutableStateOf(false) }
    var monthDraft by rememberSaveable { mutableStateOf("") }
    var vehicleMenu by remember { mutableStateOf(false) }
    val groups = remember(state.records) { state.records.groupBy { it.date.take(7) } }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PageHeader("每一程，都心中有数", "加油账本", Modifier.weight(1f))
                Box {
                    TextButton(onClick = { vehicleMenu = true }) {
                        Text(state.vehicles.firstOrNull { it.id == state.selectedVehicleId }?.name ?: "车辆", modifier = Modifier.widthIn(max = 112.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(vehicleMenu, onDismissRequest = { vehicleMenu = false }) {
                        state.vehicles.forEach { vehicle -> DropdownMenuItem(text = { Text(vehicle.name) }, onClick = { vm.selectVehicle(vehicle.id); vehicleMenu = false }) }
                    }
                }
            }
            state.error?.let { MessageCard(it, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) }
            OutlinedTextField(state.search, vm::updateSearch, Modifier.fillMaxWidth(), placeholder = { Text("搜索加油站、日期或备注") }, singleLine = true, colors = youHaoFieldColors())
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(state.month.ifBlank { "选择月份" }, { monthDraft = state.month.ifBlank { YearMonth.now().toString() }; monthDialog = true }, compact = true)
                TextButton(onClick = vm::clearFilters) { Text("全部记录", color = MaterialTheme.colorScheme.onSurface) }
            }
            Text("${if (state.hasMore) "已加载合计" else "实付合计"} · ${state.records.size} 笔", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatMoney(state.records.sumOf { it.amount }), style = MetricValueStyle.numeric(), modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        when {
            state.loading -> item { EmptyHint("正在载入…") }
            state.records.isEmpty() -> item { EmptyHint(if (state.search.isNotBlank() || state.month.isNotBlank()) "没有找到记录，换个关键词或月份试试" else "第一笔加油，从这里开始") }
            else -> groups.forEach { (month, records) ->
                item(key = "month-$month") {
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(month, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${records.size} 笔", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(records, key = { it.id }) { record -> RecordRow(record, consumption = state.consumptionByRecordId[record.id], onClick = { onOpenRecord(record.id) }) }
            }
        }
        if (state.hasMore) item { SecondaryButton("加载更多", vm::loadMore, Modifier.fillMaxWidth().padding(top = 12.dp)) }
        item { PrimaryButton("＋  记一笔加油", { onAddRecord(state.selectedVehicleId) }, Modifier.fillMaxWidth().padding(top = 18.dp), enabled = state.selectedVehicleId != null) }
    }
    if (monthDialog) {
        val validMonth = runCatching { YearMonth.parse(monthDraft) }.isSuccess
        AlertDialog(
            onDismissRequest = { monthDialog = false }, title = { Text("选择月份") },
            text = { Column {
                OutlinedTextField(monthDraft, { monthDraft = it }, label = { Text("年份与月份") }, placeholder = { Text("2026-09") }, supportingText = { Text("格式：YYYY-MM") }, singleLine = true, isError = !validMonth, colors = youHaoFieldColors())
                TextButton(onClick = { monthDraft = YearMonth.now().toString() }) { Text("回到本月") }
            } },
            confirmButton = { TextButton(enabled = validMonth, onClick = { vm.updateMonth(monthDraft); monthDialog = false }) { Text("应用") } },
            dismissButton = { TextButton(onClick = { monthDialog = false }) { Text("取消") } },
        )
    }
}
