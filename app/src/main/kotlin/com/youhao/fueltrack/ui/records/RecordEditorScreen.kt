package com.youhao.fueltrack.ui.records

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import com.youhao.fueltrack.domain.calc.fuelPriceSummary
import com.youhao.fueltrack.domain.time.LocalDateKeys
import com.youhao.fueltrack.ui.components.EmptyHint
import com.youhao.fueltrack.ui.components.MessageCard
import com.youhao.fueltrack.ui.components.PrimaryButton
import com.youhao.fueltrack.ui.components.SectionTitle
import com.youhao.fueltrack.ui.components.StaggeredAppear
import com.youhao.fueltrack.ui.components.SwitchRow
import com.youhao.fueltrack.ui.components.pressable
import com.youhao.fueltrack.ui.components.youHaoFieldColors
import com.youhao.fueltrack.ui.formatMoney
import com.youhao.fueltrack.ui.formatPrice
import com.youhao.fueltrack.ui.theme.numeric

@Composable
fun RecordEditorScreen(
    recordId: String?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalContext.current.appContainer
    val viewModel: RecordEditorViewModel = viewModel(
        factory = viewModelFactory {
            initializer { RecordEditorViewModel(container.store, recordId) }
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
        onLitersChange = viewModel::onLitersChange,
        onAmountChange = viewModel::onAmountChange,
        onPumpAmountChange = viewModel::onPumpAmountChange,
        onPumpPriceChange = viewModel::onPumpPriceChange,
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
    onLitersChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onPumpAmountChange: (String) -> Unit,
    onPumpPriceChange: (String) -> Unit,
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
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.error?.let { message ->
            StaggeredAppear(0) {
                MessageCard(
                    text = message,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    trailing = { TextButton(onClick = onClearError) { Text("知道了") } },
                )
            }
        }

        // 对应 Vue 表单里的 `.form-intro`：先给一句「这一步不难」的预期。
        Text(
            text = "几项信息，就能记好一笔。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.date,
                onValueChange = onDateChange,
                label = { Text("日期") },
                placeholder = { Text("YYYY-MM-DD") },
                singleLine = true,
                textStyle = numericFieldStyle,
                colors = youHaoFieldColors(),
                modifier = Modifier.weight(1f),
            )
            TodayChip(onClick = { onDateChange(LocalDateKeys.localDateKey()) })
        }

        OutlinedTextField(
            value = state.odometerText,
            onValueChange = onOdometerChange,
            label = { Text("里程 (km)") },
            singleLine = true,
            textStyle = numericFieldStyle,
            colors = youHaoFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle("油量与金额")
        OutlinedTextField(
            value = state.litersText,
            onValueChange = onLitersChange,
            label = { Text("加油量 (L)") },
            singleLine = true,
            textStyle = numericFieldStyle,
            colors = youHaoFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.amountText,
            onValueChange = onAmountChange,
            label = { Text("实付金额 (¥)") },
            singleLine = true,
            textStyle = numericFieldStyle,
            colors = youHaoFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.pumpAmountText,
            onValueChange = onPumpAmountChange,
            label = { Text("表显金额 (¥)") },
            supportingText = { Text("留空则与实付金额相同") },
            singleLine = true,
            textStyle = numericFieldStyle,
            colors = youHaoFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.pumpPriceText,
            onValueChange = onPumpPriceChange,
            label = { Text("表显单价 (¥/L)") },
            singleLine = true,
            textStyle = numericFieldStyle,
            colors = youHaoFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        PriceSummary(state)

        SwitchRow(
            text = "这次加满了",
            checked = state.isFull,
            onCheckedChange = onFullChange,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle("其他")
        OutlinedTextField(
            value = state.station,
            onValueChange = onStationChange,
            label = { Text("加油站") },
            singleLine = true,
            colors = youHaoFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.note,
            onValueChange = onNoteChange,
            label = { Text("备注") },
            minLines = 2,
            colors = youHaoFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )

        state.warnings.forEach { warning ->
            MessageCard(
                text = warning,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }

        PrimaryButton(
            text = if (state.saving) "保存中…" else "保存",
            onClick = onSave,
            enabled = !state.saving,
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
            text = { Text("删除后该记录会从列表和统计中消失，并在同步时作为删除标记保留。") },
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

/** Shows what the two prices work out to once litres and both amounts are known. */
@Composable
private fun PriceSummary(state: RecordEditorUiState) {
    val liters = state.entry.liters ?: return
    if (liters <= 0.0) return
    val amount = state.entry.amount ?: 0.0
    val summary = fuelPriceSummary(liters, amount, state.pumpAmountOrNull ?: 0.0)
    Text(
        text = "实付单价 ${formatPrice(summary.discountedPricePerLiter)} · " +
            "表显单价 ${formatPrice(summary.pumpPricePerLiter)} · " +
            "优惠 ${formatMoney(summary.discountAmount)}",
        style = MaterialTheme.typography.bodySmall.numeric(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
