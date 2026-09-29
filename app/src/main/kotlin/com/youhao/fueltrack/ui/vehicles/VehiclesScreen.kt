package com.youhao.fueltrack.ui.vehicles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.youhao.fueltrack.R
import com.youhao.fueltrack.appContainer
import com.youhao.fueltrack.domain.model.Vehicle
import com.youhao.fueltrack.ui.components.EmptyHint
import com.youhao.fueltrack.ui.components.MessageCard
import com.youhao.fueltrack.ui.components.PageHeader
import com.youhao.fueltrack.ui.components.PrimaryButton
import com.youhao.fueltrack.ui.components.StaggeredAppear
import com.youhao.fueltrack.ui.components.pressable
import com.youhao.fueltrack.ui.components.softCard
import com.youhao.fueltrack.ui.components.youHaoFieldColors
import com.youhao.fueltrack.ui.formatMoney
import com.youhao.fueltrack.ui.formatOdometer
import com.youhao.fueltrack.ui.orDash
import com.youhao.fueltrack.ui.theme.NightDanger
import com.youhao.fueltrack.ui.theme.numeric
import com.youhao.fueltrack.ui.toDoubleOrNullField
import com.youhao.fueltrack.ui.toFieldText

/** Matches the legacy web app's default, which the seeded first vehicle also uses. */
private const val DEFAULT_FUEL_TYPE = "92#"

/**
 * 车辆 section.
 *
 * Deliberately no `Scaffold`/`TopAppBar`: the nav shell in `ui/YouHaoApp.kt` already owns the app bar
 * and the bottom navigation bar, and nesting a second one would double the insets.
 */
@Composable
fun VehiclesScreen(modifier: Modifier = Modifier) {
    val container = LocalContext.current.appContainer
    val viewModel: VehiclesViewModel = viewModel(
        factory = viewModelFactory { initializer { VehiclesViewModel(container.store) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    // `null` + a flag rather than a nullable "is editing" state: adding and editing share one dialog,
    // and the target has to survive until the user actually presses 保存.
    var editorTarget by remember { mutableStateOf<Vehicle?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Vehicle?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.error?.let { error ->
            MessageCard(
                text = error,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        }

        // Vue 把「添加车辆」放在标题行右侧，而不是页面底部整行的大按钮。
        Row(verticalAlignment = Alignment.CenterVertically) {
            PageHeader(
                eyebrow = "每一程，都心中有数",
                title = "我的车辆",
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = "添加车辆",
                onClick = {
                    editorTarget = null
                    editorOpen = true
                },
                compact = true,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        when {
            state.loading -> EmptyHint("正在载入…")

            state.vehicles.isEmpty() -> EmptyHint("还没有车辆，点击右上角「添加车辆」开始记录")

            else -> state.vehicles.forEachIndexed { index, vehicle ->
                val summary = state.summaries[vehicle.id]
                StaggeredAppear(index = index) {
                    VehicleCard(
                        vehicle = vehicle,
                        recordCountText = summary?.let { "${it.recordCount} 次" } ?: "—",
                        currentOdometerText = summary?.currentOdometer?.let { formatOdometer(it) } ?: "—",
                        summaryText = summary?.let { "累计 ${formatMoney(it.totalPaid)}" },
                        deleteEnabled = state.vehicles.size > 1,
                        onEdit = {
                            editorTarget = vehicle
                            editorOpen = true
                        },
                        onDelete = { pendingDelete = vehicle },
                    )
                }
            }
        }
    }

    if (editorOpen) {
        VehicleEditorDialog(
            existing = editorTarget,
            onDismiss = { editorOpen = false },
            onSave = { name, plate, fuelType, odometer ->
                viewModel.saveVehicle(editorTarget, name, plate, fuelType, odometer)
                editorOpen = false
            },
        )
    }

    val target = pendingDelete
    if (target != null) {
        DeleteVehicleDialog(
            onDismiss = { pendingDelete = null },
            onConfirm = {
                viewModel.deleteVehicle(target.id)
                pendingDelete = null
            },
        )
    }
}

/**
 * `.vehicle-card`：顶部 138dp 的深绿「车图」区 + 下方信息区。
 *
 * 车图区的青柠辉光是画上去的而不是贴图：`drawBehind` 里按 72% / 20% 的位置打一个径向渐变圆，
 * 复刻 CSS 的 `radial-gradient(circle at 72% 20%, rgba(216,255,114,.18), transparent 8rem)`。
 */
@Composable
private fun VehicleCard(
    vehicle: Vehicle,
    recordCountText: String,
    currentOdometerText: String,
    summaryText: String?,
    deleteEnabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(
                text = vehicle.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${vehicle.plate.ifBlank { "未设置车牌" }} · ${vehicle.fuelType.orDash()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                VehicleFact(label = "加油次数", value = recordCountText)
                VehicleFact(label = "当前里程", value = currentOdometerText)
                VehicleFact(
                    label = "初始里程",
                    value = formatOdometer(vehicle.initialOdometer),
                )
            }

            summaryText?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.numeric(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionChip(text = "编辑", enabled = true, onClick = onEdit, modifier = Modifier.weight(1f))
                ActionChip(
                    text = "删除",
                    enabled = deleteEnabled,
                    danger = true,
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun VehicleFact(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.numeric(),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** `.card-actions` 里的小按钮；危险动作在浅色下用品牌红，深色下换成浅红。 */
@Composable
private fun ActionChip(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val isLight = !isSystemInDarkTheme()
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        danger -> if (isLight) MaterialTheme.colorScheme.error else NightDanger
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (enabled) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/**
 * Shared add/edit form.
 *
 * Validation runs on 保存 rather than as the user types so the errors only appear once the user has
 * committed to the value; each field clears its own error on the next keystroke.
 */
@Composable
private fun VehicleEditorDialog(
    existing: Vehicle?,
    onDismiss: () -> Unit,
    onSave: (name: String, plate: String, fuelType: String, initialOdometer: Double) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var plate by remember { mutableStateOf(existing?.plate.orEmpty()) }
    var fuelType by remember { mutableStateOf(existing?.fuelType ?: DEFAULT_FUEL_TYPE) }
    var odometer by remember { mutableStateOf(existing?.initialOdometer.toFieldText()) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var odometerError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "添加车辆" else "编辑车辆") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = null
                    },
                    label = { Text("名称") },
                    isError = nameError != null,
                    singleLine = true,
                    colors = youHaoFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                nameError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                OutlinedTextField(
                    value = plate,
                    onValueChange = { plate = it },
                    label = { Text("车牌") },
                    singleLine = true,
                    colors = youHaoFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = fuelType,
                    onValueChange = { fuelType = it },
                    label = { Text("燃油类型") },
                    singleLine = true,
                    colors = youHaoFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = odometer,
                    onValueChange = {
                        odometer = it
                        odometerError = null
                    },
                    label = { Text("初始里程") },
                    isError = odometerError != null,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.numeric(),
                    colors = youHaoFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                odometerError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = odometer.toDoubleOrNullField()
                    val blankName = name.isBlank()
                    val missingOdometer = parsed == null
                    nameError = if (blankName) "请输入车辆名称" else null
                    odometerError = if (missingOdometer) "请输入初始里程" else null
                    if (!blankName && parsed != null) {
                        onSave(
                            name.trim(),
                            plate.trim(),
                            fuelType.trim().ifBlank { DEFAULT_FUEL_TYPE },
                            parsed,
                        )
                    }
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** Deleting tombstones the vehicle *and* hides its records, so the consequence is spelled out. */
@Composable
private fun DeleteVehicleDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除车辆") },
        text = { Text("删除后该车辆及其全部加油记录都将不再显示，确定继续吗？") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("删除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
