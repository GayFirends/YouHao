package com.youhao.fueltrack.ui.records

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.youhao.fueltrack.appContainer
import com.youhao.fueltrack.domain.model.FuelRecord
import com.youhao.fueltrack.ui.components.EmptyHint
import com.youhao.fueltrack.ui.components.HeroCard
import com.youhao.fueltrack.ui.components.MessageCard
import com.youhao.fueltrack.ui.components.PageHeader
import com.youhao.fueltrack.ui.components.PrimaryButton
import com.youhao.fueltrack.ui.components.RecordRow
import com.youhao.fueltrack.ui.components.SecondaryButton
import com.youhao.fueltrack.ui.components.StaggeredAppear
import com.youhao.fueltrack.ui.components.pressable
import com.youhao.fueltrack.ui.components.youHaoFieldColors
import com.youhao.fueltrack.ui.formatMoney
import com.youhao.fueltrack.ui.theme.MetricValueStyle
import com.youhao.fueltrack.ui.theme.Signal
import com.youhao.fueltrack.ui.theme.PineHeroInk
import com.youhao.fueltrack.ui.theme.numeric

@Composable
fun RecordsScreen(
    onAddRecord: () -> Unit,
    onOpenRecord: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalContext.current.appContainer
    val viewModel: RecordsViewModel = viewModel(
        factory = viewModelFactory { initializer { RecordsViewModel(container.store) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    RecordsContent(
        state = state,
        onSelectVehicle = viewModel::selectVehicle,
        onSearch = viewModel::updateSearch,
        onMonth = viewModel::updateMonth,
        onCurrentMonth = viewModel::useCurrentMonth,
        onClearFilters = viewModel::clearFilters,
        onLoadMore = viewModel::loadMore,
        onAddRecord = onAddRecord,
        onOpenRecord = onOpenRecord,
        modifier = modifier,
    )
}

@Composable
private fun RecordsContent(
    state: RecordsUiState,
    onSelectVehicle: (String) -> Unit,
    onSearch: (String) -> Unit,
    onMonth: (String) -> Unit,
    onCurrentMonth: () -> Unit,
    onClearFilters: () -> Unit,
    onLoadMore: () -> Unit,
    onAddRecord: () -> Unit,
    onOpenRecord: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // `.record-month` 分组：记录本身已按日期倒序，`groupBy` 保留首次出现的顺序，所以月份也是倒序的。
    val groups = remember(state.records) { state.records.groupBy { it.date.take(7) } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.error?.let { message ->
            MessageCard(
                text = message,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        }

        PageHeader(eyebrow = "把每一次补给，记在这里", title = "加油账本")

        // 分行显示，给搜索文案和月份格式说明留出足够空间。
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.search,
                onValueChange = onSearch,
                placeholder = { Text("搜索加油站、日期或备注") },
                label = { Text("搜索记录") },
                singleLine = true,
                colors = youHaoFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.month,
                onValueChange = onMonth,
                placeholder = { Text("全部月份") },
                label = { Text("月份筛选") },
                supportingText = { Text("输入 YYYY-MM，例如 2026-09；留空显示全部") },
                singleLine = true,
                colors = youHaoFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            QuickFilter(text = "本月", onClick = onCurrentMonth)
            QuickFilter(text = "清除筛选", onClick = onClearFilters)
        }

        if (state.vehicles.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.vehicles.forEach { vehicle ->
                    VehicleChip(
                        name = vehicle.name,
                        selected = vehicle.id == state.selectedVehicleId,
                        onClick = { onSelectVehicle(vehicle.id) },
                    )
                }
            }
        }

        // 只有拿全了当前筛选结果才算汇总：分页没加载完时统计会少算，宁可先不显示。
        if (!state.hasMore && state.search.isBlank() && state.records.isNotEmpty()) {
            SummaryBanner(
                count = state.records.size,
                amount = state.records.sumOf { it.amount },
                saved = state.records.sumOf { (it.pumpAmount - it.amount).coerceAtLeast(0.0) },
                monthLabel = state.month.takeIf { it.isNotBlank() }?.let(::monthLabel),
            )
        }

        when {
            state.loading -> EmptyHint("正在载入…")

            state.records.isEmpty() -> {
                EmptyHint(
                    text = if (state.search.isNotBlank() || state.month.isNotBlank()) {
                        "没有找到这笔记录，换个关键词或月份试试。"
                    } else {
                        "账本的第一页，留给你：记录第一次加油，让花费与油耗清晰起来。"
                    },
                )
                if (state.search.isBlank() && state.month.isBlank()) {
                    PrimaryButton(text = "记录第一次加油", onClick = onAddRecord, modifier = Modifier.fillMaxWidth())
                }
            }

            else -> {
                var stagger = 0
                groups.forEach { (month, records) ->
                    MonthHeading(month = month, records = records)
                    records.forEach { record ->
                        StaggeredAppear(index = stagger++) {
                            RecordRow(
                                record = record,
                                consumption = state.consumptionByRecordId[record.id],
                                onClick = { onOpenRecord(record.id) },
                            )
                        }
                    }
                }
                if (state.hasMore) {
                    SecondaryButton(
                        text = "加载更多",
                        onClick = onLoadMore,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** `.records-summary`：深绿渐变横幅，左边说明、右边青柠大数字。 */
@Composable
private fun SummaryBanner(count: Int, amount: Double, saved: Double, monthLabel: String?) {
    HeroCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = (monthLabel?.let { "$it · " } ?: "") + "$count 笔记录 · 实付合计",
                    style = MaterialTheme.typography.bodySmall,
                    color = PineHeroInk,
                )
                if (saved > 0.0) {
                    Text(
                        text = "累计优惠 ${formatMoney(saved)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = PineHeroInk,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    Text(
                        text = "本页记录已全部加载",
                        style = MaterialTheme.typography.labelSmall,
                        color = PineHeroInk,
                    )
                }
            }
            Text(
                text = formatMoney(amount),
                style = MetricValueStyle.numeric(),
                color = Signal,
            )
        }
    }
}

/** `.month-heading`：`2026 年 6 月` 与「合计 · 笔数」。 */
@Composable
private fun MonthHeading(month: String, records: List<FuelRecord>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = monthLabel(month),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "${formatMoney(records.sumOf { it.amount })} · ${records.size} 笔",
            style = MaterialTheme.typography.labelMedium.numeric(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QuickFilter(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surface)
            .pressable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VehicleChip(name: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            )
            .pressable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** `2026-06` → `2026 年 6 月`；不是月份键就原样返回。 */
private fun monthLabel(month: String): String {
    if (month.length < 7) return month
    val year = month.take(4)
    val value = month.substring(5, 7).trimStart('0')
    return "$year 年 ${value.ifBlank { "0" }} 月"
}
