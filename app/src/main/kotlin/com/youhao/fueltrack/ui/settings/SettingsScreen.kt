package com.youhao.fueltrack.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.youhao.fueltrack.appContainer
import com.youhao.fueltrack.data.prefs.DEFAULT_SYNC_FILE_NAME
import com.youhao.fueltrack.ui.components.HeroCard
import com.youhao.fueltrack.ui.theme.PineHeroInk
import com.youhao.fueltrack.ui.components.HeroStat
import com.youhao.fueltrack.ui.components.MessageCard
import com.youhao.fueltrack.ui.components.PageHeader
import com.youhao.fueltrack.ui.components.PrimaryButton
import com.youhao.fueltrack.ui.components.SecondaryButton
import com.youhao.fueltrack.ui.components.SectionTitle
import com.youhao.fueltrack.ui.components.StaggeredAppear
import com.youhao.fueltrack.ui.components.SwitchRow
import com.youhao.fueltrack.ui.components.youHaoFieldColors
import com.youhao.fueltrack.ui.formatDate
import com.youhao.fueltrack.ui.theme.numeric

/**
 * 设置页：WebDAV 同步配置、本地数据信息和冲突入口。
 *
 * The screen owns no [androidx.compose.material3.Scaffold] and no top bar — [com.youhao.fueltrack.ui.YouHaoApp]
 * already provides both, plus the 返回 affordance, so this composable only fills the content slot.
 */
@Composable
fun SettingsScreen(onOpenConflicts: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalContext.current.appContainer
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(container.settings, container.syncEngine, container.store, container.backupFiles)
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The document pickers are owned here, not in the ViewModel: a launcher is a UI-scoped handle
    // and the ViewModel only ever sees the resulting Uri. All four are declared unconditionally so
    // the composable's call order stays stable.
    val exportCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> uri?.let(viewModel::exportCsv) }
    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportBackup) }
    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importBackup) }
    val exportDiagnosticsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportDiagnostics) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.lastError?.let { lastError ->
            MessageCard(
                text = "最近一次同步失败（${formatDate(lastError.occurredAt)}）：${lastError.code.guidance}",
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                trailing = {
                    androidx.compose.material3.TextButton(onClick = viewModel::clearLastError) { Text("清除") }
                },
            )
        }

        state.statusMessage?.let { message ->
            MessageCard(
                text = message,
                containerColor = if (state.statusIsError) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (state.statusIsError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            )
        }

        PageHeader(
            eyebrow = "记录随行，安心留存",
            title = "数据与同步",
            subtitle = "连接你的 WebDAV 空间，让不同设备的记录随行。",
        )

        // `.sync-summary`：深绿底色的同步概览，把「同步去哪儿了」讲清楚再让用户填地址。
        SyncSummary(
            configured = state.config.url.isNotBlank(),
            busy = state.busy,
            encryptionEnabled = state.config.encryptionEnabled == true,
            conflictCount = state.conflictCount,
            onOpenConflicts = onOpenConflicts,
        )

        SectionTitle("同步")
        OutlinedTextField(
            value = state.config.url,
            onValueChange = viewModel::onUrlChange,
            label = { Text("WebDAV 地址") },
            singleLine = true,
            placeholder = { Text("https://example.com/dav/") },
            colors = youHaoFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.config.username,
            onValueChange = viewModel::onUsernameChange,
            label = { Text("用户名") },
            singleLine = true,
            colors = youHaoFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.editingPassword,
            onValueChange = viewModel::onPasswordChange,
            label = { Text("应用密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            colors = youHaoFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        SwitchRow(
            text = "记住密码以便后台同步",
            checked = state.config.rememberPassword == true,
            onCheckedChange = viewModel::onRememberPasswordChange,
            modifier = Modifier.fillMaxWidth(),
        )
        Hint(
            "开启后密码会加密保存在 Android Keystore 中，每 6 小时的后台同步才有凭据可用。" +
                "关闭时密码只保留在本次运行期间，进程结束后后台同步会直接跳过。",
        )
        OutlinedTextField(
            value = state.config.fileName,
            onValueChange = viewModel::onFileNameChange,
            label = { Text("同步文件名") },
            singleLine = true,
            placeholder = { Text(DEFAULT_SYNC_FILE_NAME) },
            colors = youHaoFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )

        SwitchRow(
            text = "启用端到端加密",
            checked = state.config.encryptionEnabled == true,
            onCheckedChange = viewModel::onEncryptionEnabledChange,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.config.encryptionEnabled == true) {
            StaggeredAppear {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.config.encryptionPassphrase.orEmpty(),
                        onValueChange = viewModel::onPassphraseChange,
                        label = { Text("同步口令") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = youHaoFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SwitchRow(
                        text = "记住口令",
                        checked = state.config.rememberEncryptionPassphrase == true,
                        onCheckedChange = viewModel::onRememberPassphraseChange,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Hint("开启后口令会加密保存在 Android Keystore 中，只有本机应用可以读取。")
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton(
                text = "保存设置",
                onClick = viewModel::saveSettings,
                enabled = !state.busy,
                compact = true,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                text = "测试连接",
                onClick = viewModel::testConnection,
                enabled = !state.busy,
                compact = true,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                text = "立即同步",
                onClick = viewModel::syncNow,
                enabled = !state.busy,
                compact = true,
                modifier = Modifier.weight(1f),
            )
        }

        if (state.busy) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 4.dp))
        }

        SectionTitle("数据")
        HeroCard(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeroStat(
                    label = "本地数据库",
                    value = state.schemaInfo?.let { "v${it.version}" } ?: "—",
                    emphasize = true,
                    modifier = Modifier.weight(1f),
                )
                HeroStat(
                    label = "存储引擎",
                    value = state.schemaInfo?.backend ?: "—",
                    modifier = Modifier.weight(1.3f),
                )
            }
            Text(
                text = "设备标识：${state.deviceId.ifBlank { "—" }}",
                style = MaterialTheme.typography.labelSmall.numeric(),
                color = PineHeroInk,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        SectionTitle("备份与导出")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SecondaryButton(
                text = "导出 CSV",
                onClick = { exportCsvLauncher.launch("fuel-track-records.csv") },
                enabled = !state.busy,
                compact = true,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                text = "导出备份",
                onClick = { exportBackupLauncher.launch("fuel-track-backup.json") },
                enabled = !state.busy,
                compact = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SecondaryButton(
                text = "导入备份",
                onClick = { importBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                enabled = !state.busy,
                compact = true,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                text = "导出诊断",
                onClick = { exportDiagnosticsLauncher.launch("fuel-track-diagnostics.json") },
                enabled = !state.busy,
                compact = true,
                modifier = Modifier.weight(1f),
            )
        }
        Hint("导入会与本地数据合并：较新的记录优先，删除标记同样保留，因此不会把已删除的记录带回来。")

        SectionTitle("同步冲突")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (state.conflictCount > 0) {
                    "有 ${state.conflictCount} 条冲突等着你决定"
                } else {
                    "本地和云端目前没有冲突"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (state.conflictCount > 0) {
                SecondaryButton(
                    text = "去处理",
                    onClick = onOpenConflicts,
                    compact = true,
                )
            }
        }
    }
}

/** `.sync-summary`：先回答「同步配置好了吗」，再让用户往下填。 */
@Composable
private fun SyncSummary(
    configured: Boolean,
    busy: Boolean,
    encryptionEnabled: Boolean,
    conflictCount: Int,
    onOpenConflicts: () -> Unit,
) {
    HeroCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = when {
                busy -> "正在同步…"
                !configured -> "还没有配置同步"
                else -> "同步已就绪"
            },
            style = MaterialTheme.typography.titleMedium,
            color = PineHeroInk,
        )
        Text(
            text = when {
                busy -> "同步进行中，请稍候，不要离开这一页。"
                !configured -> "填好下面的 WebDAV 地址与账号，就能把记录同步到你自己的网盘。"
                encryptionEnabled -> "端到端加密已开启：云端只保存密文，口令不出本机。"
                else -> "数据会在每次改动后上传，后台每 6 小时核对一次。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = PineHeroInk,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (conflictCount > 0) {
            Text(
                text = "另有 $conflictCount 条冲突待处理",
                style = MaterialTheme.typography.labelMedium,
                color = PineHeroInk,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
