package com.youhao.fueltrack.ui.conflicts

import androidx.compose.foundation.background
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
import com.youhao.fueltrack.data.ConflictChoice
import com.youhao.fueltrack.data.ConflictDetail
import com.youhao.fueltrack.data.ConflictValue
import com.youhao.fueltrack.domain.model.FuelRecord
import com.youhao.fueltrack.domain.model.SyncEntityType
import com.youhao.fueltrack.domain.model.Vehicle
import com.youhao.fueltrack.domain.sync.wireName
import com.youhao.fueltrack.ui.components.EmptyHint
import com.youhao.fueltrack.ui.components.MessageCard
import com.youhao.fueltrack.ui.components.PageHeader
import com.youhao.fueltrack.ui.components.PrimaryButton
import com.youhao.fueltrack.ui.components.SoftBadge
import com.youhao.fueltrack.ui.components.StaggeredAppear
import com.youhao.fueltrack.ui.components.pressable
import com.youhao.fueltrack.ui.components.softCardModifier
import com.youhao.fueltrack.ui.formatDate
import com.youhao.fueltrack.ui.formatLiters
import com.youhao.fueltrack.ui.formatMoney
import com.youhao.fueltrack.ui.formatOdometer
import com.youhao.fueltrack.ui.orDash
import com.youhao.fueltrack.ui.theme.numeric

/**
 * 同步冲突页：逐条展示本地与云端的差异，让用户选择保留哪一侧。
 *
 * No [androidx.compose.material3.Scaffold] and no top bar here — [com.youhao.fueltrack.ui.YouHaoApp]
 * already wraps every destination in one, including the 返回 button.
 */
@Composable
fun ConflictsScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalContext.current.appContainer
    val viewModel: ConflictsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ConflictsViewModel(container.store) }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

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

        PageHeader(
            eyebrow = "本地与云端各有一份",
            title = if (state.items.isEmpty()) "没有待处理的冲突" else "选一份留下",
            subtitle = if (state.resolvedCount > 0) {
                "本次已处理 ${state.resolvedCount} 条"
            } else {
                "冲突只会在两端同时改过同一条时出现，选完即完成同步。"
            },
        )

        when {
            state.items.isNotEmpty() -> state.items.forEachIndexed { index, detail ->
                StaggeredAppear(index = index) {
                    ConflictCard(
                        detail = detail,
                        onKeepLocal = { viewModel.resolve(detail.id, ConflictChoice.LOCAL) },
                        onKeepRemote = { viewModel.resolve(detail.id, ConflictChoice.REMOTE) },
                    )
                }
            }

            state.loaded -> {
                EmptyHint("没有待处理的同步冲突")
                PrimaryButton(text = "完成", onClick = onDone, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** One conflict: what it is, when it was detected, both candidate values, and the two decisions. */
@Composable
private fun ConflictCard(
    detail: ConflictDetail,
    onKeepLocal: () -> Unit,
    onKeepRemote: () -> Unit,
) {
    Column(
        modifier = softCardModifier(Modifier.fillMaxWidth())
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = entityLabel(detail.entityType),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SoftBadge(text = detail.entityType.wireName)
        }
        Text(
            text = "检测时间：${formatDate(detail.detectedAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        ConflictSide(label = "本地版本", value = detail.local, modifier = Modifier.padding(top = 12.dp))
        ConflictSide(label = "云端版本", value = detail.remote, modifier = Modifier.padding(top = 8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChooseButton(
                text = "保留本地",
                emphasized = true,
                onClick = onKeepLocal,
                modifier = Modifier.weight(1f),
            )
            ChooseButton(
                text = "保留云端",
                emphasized = false,
                onClick = onKeepRemote,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 两侧选项的视觉权重不同：主选项用青柠填充，次选项用中性底，避免误点。 */
@Composable
private fun ChooseButton(
    text: String,
    emphasized: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(
                if (emphasized) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .pressable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (emphasized) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/** The exhaustive `when` keeps this honest if a third [ConflictValue] shape is ever added. */
@Composable
private fun ConflictSide(label: String, value: ConflictValue, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        when (value) {
            is ConflictValue.VehicleValue -> VehicleFields(value.vehicle)
            is ConflictValue.RecordValue -> RecordFields(value.record)
        }
    }
}

@Composable
private fun VehicleFields(vehicle: Vehicle) {
    ConflictField("名称", vehicle.name)
    ConflictField("车牌", vehicle.plate.orDash())
    ConflictField("燃油类型", vehicle.fuelType)
    ConflictField("初始里程", formatOdometer(vehicle.initialOdometer), numeric = true)
}

@Composable
private fun RecordFields(record: FuelRecord) {
    ConflictField("日期", formatDate(record.date), numeric = true)
    ConflictField("里程", formatOdometer(record.odometer), numeric = true)
    ConflictField("加油量", formatLiters(record.liters), numeric = true)
    ConflictField("金额", formatMoney(record.amount), numeric = true)
    ConflictField("满箱", if (record.isFull) "是" else "否")
}

@Composable
private fun ConflictField(label: String, value: String, numeric: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = if (numeric) {
                MaterialTheme.typography.bodyMedium.numeric()
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1.6f),
        )
    }
}

private fun entityLabel(type: SyncEntityType): String = when (type) {
    SyncEntityType.VEHICLE -> "车辆"
    SyncEntityType.RECORD -> "记录"
}
