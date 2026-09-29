package com.youhao.fueltrack.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.youhao.fueltrack.appContainer
import com.youhao.fueltrack.domain.calc.ConsumptionInterval
import com.youhao.fueltrack.ui.components.EmptyHint
import com.youhao.fueltrack.ui.components.MessageCard
import com.youhao.fueltrack.ui.components.MetricCard
import com.youhao.fueltrack.ui.components.PageHeader
import com.youhao.fueltrack.ui.components.RecordRow
import com.youhao.fueltrack.ui.components.SectionTitle
import com.youhao.fueltrack.ui.components.SoftBadge
import com.youhao.fueltrack.ui.components.StaggeredAppear
import com.youhao.fueltrack.ui.components.pressable
import com.youhao.fueltrack.ui.formatConsumptionValue
import com.youhao.fueltrack.ui.formatLiters
import com.youhao.fueltrack.ui.formatMoney
import com.youhao.fueltrack.ui.formatMonthTitle
import com.youhao.fueltrack.ui.formatNumber
import com.youhao.fueltrack.ui.formatOdometer
import com.youhao.fueltrack.ui.formatShortDate
import com.youhao.fueltrack.ui.formatTodayTitle
import com.youhao.fueltrack.ui.theme.MetricValueSmallStyle
import com.youhao.fueltrack.ui.theme.numeric
import java.time.LocalDate

@Composable
fun OverviewScreen(
    onOpenRecord: (String) -> Unit,
    onOpenConflicts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalContext.current.appContainer
    val viewModel: OverviewViewModel = viewModel(
        factory = viewModelFactory {
            initializer { OverviewViewModel(container.store, container.settings) }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    OverviewContent(
        state = state,
        onSelectVehicle = viewModel::selectVehicle,
        onOpenRecord = onOpenRecord,
        onOpenConflicts = onOpenConflicts,
        onClearError = viewModel::clearLastError,
        modifier = modifier,
    )
}

@Composable
private fun OverviewContent(
    state: OverviewUiState,
    onSelectVehicle: (String) -> Unit,
    onOpenRecord: (String) -> Unit,
    onOpenConflicts: () -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        EmptyHint(text = "正在载入…", modifier = modifier)
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        state.lastError?.let { error ->
            StaggeredAppear(index = 0) {
                MessageCard(
                    text = "上次同步失败：${error.code.guidance}",
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    trailing = { TextButton(onClick = onClearError) { Text("清除") } },
                )
            }
        }

        if (state.conflictCount > 0) {
            StaggeredAppear(index = 1) {
                MessageCard(
                    text = "有 ${state.conflictCount} 条同步冲突待处理",
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    trailing = { TextButton(onClick = onOpenConflicts) { Text("处理") } },
                )
            }
        }

        if (state.vehicles.isEmpty()) {
            EmptyHint(text = "还没有车辆，请到「车辆」页添加第一辆车")
            return@Column
        }

        if (state.vehicles.size > 1) {
            StaggeredAppear(index = 2) { VehicleChips(state, onSelectVehicle) }
        }

        // `.page-title`：左边是 eyebrow + 主标题，右边挂当天日期。
        StaggeredAppear(index = 3) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                PageHeader(
                    eyebrow = "${formatMonthTitle()} · ${state.selectedVehicleName ?: "行驶概览"}",
                    title = "每一程，都心中有数。",
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatTodayTitle(LocalDate.now()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 22.dp),
                )
            }
        }

        // 仪表盘三块：油耗是主角，所以它是唯一带青柠网格与 LIVE 标记的深绿卡。
        StaggeredAppear(index = 4) {
            MetricCard(
                title = "平均油耗",
                value = formatConsumptionValue(state.averageConsumption),
                caption = if (state.intervals.isNotEmpty()) {
                    "基于 ${state.intervals.size} 个满箱区间 · ${formatNumber(state.measuredDistance)} km"
                } else {
                    "两次满箱后，计算更准确"
                },
                featured = true,
                liveLabel = "LIVE",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        StaggeredAppear(index = 5) {
            MetricCard(
                title = "本月油费",
                value = formatMoney(state.monthCost),
                caption = "本月 ${state.monthRecordCount} 次 · 累计 ${formatMoney(state.summary?.totalPaid ?: 0.0)}",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        StaggeredAppear(index = 6) {
            MetricCard(
                title = "记录里程",
                value = formatNumber(totalDistance(state)) + " km",
                caption = state.summary?.currentOdometer?.let { "当前里程 ${formatOdometer(it)}" }
                    ?: "从第一笔开始累计",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionTitle("满箱区间明细")
        if (state.intervals.isEmpty()) {
            EmptyHint("连续两次加满油之后就能算出油耗")
        } else {
            state.intervals.forEachIndexed { index, interval ->
                StaggeredAppear(index = index) { IntervalCard(interval) }
            }
        }

        SectionTitle("最近加油")
        if (state.recentRecords.isEmpty()) {
            EmptyHint("还没有加油记录")
        } else {
            state.recentRecords.forEachIndexed { index, record ->
                StaggeredAppear(index = index) {
                    RecordRow(record = record, onClick = { onOpenRecord(record.id) })
                }
            }
        }
    }
}

/** 没有初始里程时，只能拿「最早一笔的里程」当起点，和原 Vue 版的兜底一致。 */
private fun totalDistance(state: OverviewUiState): Double {
    val current = state.summary?.currentOdometer ?: return 0.0
    val start = state.selectedVehicleInitialOdometer
        ?: state.recentRecords.minOfOrNull { it.odometer }
        ?: return 0.0
    return (current - start).coerceAtLeast(0.0)
}

/** 车辆切换：选中项是强调色实底，未选中是纸白描边，比 M3 默认 chip 更贴设计源。 */
@Composable
private fun VehicleChips(state: OverviewUiState, onSelectVehicle: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.vehicles.forEach { vehicle ->
            val selected = vehicle.id == state.selectedVehicleId
            Box(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    )
                    .pressable(onClick = { onSelectVehicle(vehicle.id) })
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = vehicle.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun IntervalCard(interval: ConsumptionInterval) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SoftBadge(text = formatShortDate(interval.record.date))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatOdometer(interval.distance),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = formatLiters(interval.liters),
                style = MaterialTheme.typography.bodySmall.numeric(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = formatConsumptionValue(interval.consumption),
            style = MetricValueSmallStyle.numeric(),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "L/100km",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        )
    }
}
