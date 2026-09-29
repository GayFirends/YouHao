package com.youhao.fueltrack.ui.records

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import com.youhao.fueltrack.ui.components.ExpandableSection
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.youhao.fueltrack.appContainer
import com.youhao.fueltrack.domain.time.LocalDateKeys
import com.youhao.fueltrack.ui.components.EmptyHint
import com.youhao.fueltrack.ui.components.MessageCard
import com.youhao.fueltrack.ui.components.PrimaryButton
import com.youhao.fueltrack.ui.components.StaggeredAppear
import com.youhao.fueltrack.ui.components.SwitchRow
import com.youhao.fueltrack.ui.components.pressable
import com.youhao.fueltrack.ui.components.youHaoFieldColors
import com.youhao.fueltrack.ui.theme.numeric

@Composable
fun RecordEditorScreen(
    recordId: String?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    vehicleId: String? = null,
) {
    val container = LocalContext.current.appContainer
    val viewModel: RecordEditorViewModel = viewModel(
        factory = viewModelFactory {
            initializer { RecordEditorViewModel(container.store, recordId, vehicleId) }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Saving and deleting both leave the screen; which one happened only matters to this effect.
    LaunchedEffect(state.saved, state.deleted) {
        if (state.saved || state.deleted) onDone()
    }

    RecordEditorContent(
        state = state,
        onDateChange = viewModel::onDateChange,
        onOdometerChange = viewModel::onOdometerChange,
        onAmountsChange = viewModel::onAmountsChange,
        onFullChange = viewModel::onFullChange,
        onStationChange = viewModel::onStationChange,
        onNoteChange = viewModel::onNoteChange,
        onSave = viewModel::save,
        onDelete = viewModel::delete,
        onClearError = viewModel::clearError,
        modifier = modifier,
    )
}

@Composable
private fun RecordEditorContent(
    state: RecordEditorUiState,
    onDateChange: (String) -> Unit,
    onOdometerChange: (String) -> Unit,
    onAmountsChange: (FuelAmountForm) -> Unit,
    onFullChange: (Boolean) -> Unit,
    onStationChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        EmptyHint(text = "正在载入…", modifier = modifier)
        return
    }

    var confirmingDelete by remember { mutableStateOf(false) }
    // 表单里的数字用等宽数字，方便逐位核对。
    val numericFieldStyle = MaterialTheme.typography.bodyLarge.numeric()

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.error?.let { message ->
            StaggeredAppear(index = 0) {
                MessageCard(
                    text = message,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    trailing = { TextButton(onClick = onClearError) { Text("知道了") } },
                )
            }
        }

        Text(state.vehicleName.ifBlank { "请先添加车辆" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FuelAmountFields(state.amounts, onAmountsChange)
        OutlinedTextField(state.odometerText, onOdometerChange, Modifier.fillMaxWidth(), label = { Text("当前里程 · km") }, singleLine = true,
            textStyle = numericFieldStyle, colors = youHaoFieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        SwitchRow("这次加满了 · 用于计算油耗", state.isFull, onFullChange)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(state.date, onDateChange, Modifier.weight(1f), label = { Text("加油日期") }, placeholder = { Text("YYYY-MM-DD") }, singleLine = true, colors = youHaoFieldColors())
            TodayChip { onDateChange(LocalDateKeys.localDateKey()) }
        }
        OutlinedTextField(state.station, onStationChange, Modifier.fillMaxWidth(), label = { Text("加油站 · 选填") }, singleLine = true, colors = youHaoFieldColors())
        ExpandableSection("备注 · 选填", initiallyExpanded = state.note.isNotBlank()) {
            OutlinedTextField(state.note, onNoteChange, Modifier.fillMaxWidth(), label = { Text("备注") }, minLines = 2, colors = youHaoFieldColors())
        }

        state.warnings.forEach { warning ->
            MessageCard(
                text = warning,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }

        PrimaryButton(
            text = if (state.saving) "保存中…" else "保存记录",
            onClick = onSave,
            enabled = !state.saving && state.amounts.result != null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )

        // 对应 Vue `.modal-footer` 的 `.save-note`：再次说明数据落在本机。
        Text(
            text = "保存到本机 · 需要时再用 WebDAV 同步",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        if (!state.isNew) {
            DangerButton(
                text = "删除这条记录",
                enabled = !state.saving,
                onClick = { confirmingDelete = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除记录") },
            text = { Text("删除后，这条记录将从账本和统计中移除，并同步到其他设备。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("取消") }
            },
        )
    }
}

/** 「今天」是个高频快捷动作，做成药丸按钮而不是文字按钮，点按目标更大。 */
@Composable
private fun TodayChip(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pressable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = "今天",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 删除是破坏性动作：只有它用 error 色，把它和「保存」明确分开。 */
@Composable
private fun DangerButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.errorContainer)
            .then(if (enabled) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = if (enabled) 1f else 0.5f),
        )
    }
}
