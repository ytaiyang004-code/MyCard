package com.mycard.wallet.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mycard.wallet.ui.theme.WalletDesign
import com.mycard.wallet.*
import com.mycard.wallet.data.*

@Composable
fun SettingsScreen(vm: WalletViewModel, settings: WalletSettings, onBack: () -> Unit,
                   onLockChange: (Boolean) -> Unit) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    var clear by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var changelog by remember { mutableStateOf(false) }
    val releaseNotes by produceState("", resources) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            resources.openRawResource(com.mycard.wallet.R.raw.changelog).bufferedReader().use { it.readText() }
        }
    }
    val operation by vm.backupOperation.collectAsStateWithLifecycle()
    val working = operation.step == BackupStep.RUNNING
    // Only non-secret request IDs survive saved-state restoration. Password text never does.
    var createRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var openRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var password by remember(operation.id) { mutableStateOf("") }
    var repeatPassword by remember(operation.id) { mutableStateOf("") }
    val visible = LocalWalletVisible.current
    LaunchedEffect(visible) { if (!visible) { password = ""; repeatPassword = "" } }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        createRequest?.let { vm.backupDocumentSelected(it, uri) }
        createRequest = null
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        openRequest?.let { vm.backupDocumentSelected(it, uri) }
        openRequest = null
    }
    Scaffold(topBar = { PageTopBar(Copy.settings, onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = WalletDesign.pageInset),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsGroup(Copy.appearance) {
                Text(Copy.theme, style = MaterialTheme.typography.titleSmall)
                AppPalette.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { palette ->
                            OutlinedCard(onClick = { vm.palette(palette) }, modifier = Modifier.weight(1f).semantics { selected = settings.palette == palette },
                                border = androidx.compose.foundation.BorderStroke(if(settings.palette == palette) 2.dp else 1.dp,
                                    if(settings.palette == palette) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                                Row(Modifier.padding(14.dp).heightIn(min = 32.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
                                        drawCircle(com.mycard.wallet.ui.theme.walletColors(palette, palette == AppPalette.BLACK).primary)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text(palette.label, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
                ToggleRow(Copy.followSystem, settings.usesSystem, Copy.themeHint) { vm.boolean("followSystem", it) }
                SettingChoice(Copy.layout, CardLayout.entries, settings.layout, { it.label }) { vm.layout(it) }
                SettingChoice(Copy.tailMode, listOf(false, true), settings.tailBadge, { if(it) "卡面标签" else "仅详情页" }) { vm.boolean("tailBadge", it) }
                ToggleRow(Copy.animations, settings.animations, "关闭后减少页面和卡片动效，滑动与点击保持可用。") { vm.boolean("animations", it) }
                SettingChoice(Copy.sorting, SortOrder.entries, settings.sort, { it.label }) { vm.text("sort", it.name) }
            }
            SettingsGroup(Copy.privacy) {
                ToggleRow(Copy.privacyLock, settings.privacyLock, Copy.privacyLockHint, onLockChange)
                Text("卡片及处理后的图片仅保存在本机加密空间。允许截图和录屏。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingsGroup(Copy.storage) {
                Text("备份包含全部卡片及处理后的卡面。密码不会保存，忘记密码无法恢复。设备隐私锁和界面偏好不会随备份迁移。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton({
                    val id = vm.requestBackupDocument(BackupAction.EXPORT) ?: return@OutlinedButton
                    createRequest = id
                    try { create.launch("MyCard-${java.time.LocalDate.now()}.mycard") }
                    catch (_: Exception) { createRequest = null; vm.backupDocumentFailed(id) }
                }, Modifier.fillMaxWidth().heightIn(min = WalletDesign.touch), enabled = operation.step == BackupStep.IDLE) { Text(Copy.backup) }
                OutlinedButton({
                    val id = vm.requestBackupDocument(BackupAction.RESTORE) ?: return@OutlinedButton
                    openRequest = id
                    try { open.launch(arrayOf("application/octet-stream", "*/*")) }
                    catch (_: Exception) { openRequest = null; vm.backupDocumentFailed(id) }
                }, Modifier.fillMaxWidth().heightIn(min = WalletDesign.touch), enabled = operation.step == BackupStep.IDLE) { Text(Copy.restore) }
                if (working) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (operation.action == BackupAction.RESTORE) "正在恢复，可离开此页面，返回后查看结果。"
                        else "正在加密备份，可离开此页面，返回后查看结果。", style = MaterialTheme.typography.bodySmall)
                }
                TextButton({ clear = true }, Modifier.fillMaxWidth().heightIn(min = WalletDesign.touch), enabled = !working,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(Copy.clear) }
            }
            SettingsGroup(Copy.about) {
                Text("MyCard ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                Text("versionCode ${BuildConfig.VERSION_CODE}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({ changelog = true }, Modifier.fillMaxWidth().heightIn(min = WalletDesign.touch)) { Text(Copy.changeLog) }
                TextButton({ about = true }, Modifier.fillMaxWidth().heightIn(min = WalletDesign.touch)) { Text("应用说明") }
            }
            Text(Copy.offline, Modifier.fillMaxWidth().padding(bottom = 24.dp), style = MaterialTheme.typography.labelSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (changelog && LocalWalletVisible.current) AlertDialog(onDismissRequest = { changelog = false },
        title = { Text(Copy.changeLog) }, text = {
            Text(releaseNotes, Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
        }, confirmButton = { TextButton({ changelog = false }) { Text("知道了") } })
    if (clear) ConfirmDialog(Copy.clear, Copy.clearBody, Copy.clear, { clear = false }) {
        clear = false; vm.clearWallet()
    }
    if (about && LocalWalletVisible.current) AlertDialog(onDismissRequest = { about = false }, title = { Text(Copy.about) },
        text = { Text(Copy.aboutBody) }, confirmButton = { TextButton({ about = false }) { Text("知道了") } })
    if (operation.step == BackupStep.PASSWORD && visible) {
        val restoring = operation.action == BackupAction.RESTORE
        fun dismiss() { password = ""; repeatPassword = ""; operation.id?.let(vm::dismissBackup) }
        AlertDialog(onDismissRequest = { dismiss() },
            title = { Text(if (restoring) Copy.restore else Copy.backup) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (restoring) "请输入备份密码。恢复会合并卡片；相同 ID 的现有卡片会被备份覆盖。操作失败不会改变现有卡片。"
                        else "设置至少 8 位的备份密码，建议使用较长且独立的口令。请将密码保存在安全的地方。")
                    OutlinedTextField(password, { password = it.take(128) }, label = { Text("备份密码") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !working)
                    if (!restoring) OutlinedTextField(repeatPassword, { repeatPassword = it.take(128) }, label = { Text("再次输入密码") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !working)
                }
            },
            confirmButton = {
                TextButton({
                    val id = operation.id ?: return@TextButton
                    val chars = password.toCharArray()
                    password = ""; repeatPassword = ""
                    vm.startBackup(id, chars)
                }, enabled = !working && password.length >= 8 && (restoring || password == repeatPassword)) {
                    Text(if (restoring) "确认恢复" else "加密并保存")
                }
            },
            dismissButton = { TextButton({ dismiss() }, enabled = !working) { Text(Copy.cancel) } })
    }
    operation.message?.takeIf { visible }?.let { message ->
        AlertDialog(onDismissRequest = { operation.id?.let(vm::dismissBackup) }, text = { Text(message) },
            confirmButton = { TextButton({ operation.id?.let(vm::dismissBackup) }) { Text("知道了") } })
    }
}


@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(title, Modifier.padding(start = 6.dp, bottom = 12.dp), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
private fun <T> SettingChoice(title: String, options: List<T>, selected: T, label: (T) -> String, onChoose: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Box {
            OutlinedButton({ expanded = true }, Modifier.fillMaxWidth().heightIn(min = WalletDesign.touch)) {
                Text(label(selected), Modifier.weight(1f))
                Icon(androidx.compose.material.icons.Icons.Rounded.ArrowDropDown, null)
            }
            if (LocalWalletVisible.current) DropdownMenu(expanded, { expanded = false }) {
                options.forEach { option -> DropdownMenuItem(text = { Text(label(option)) },
                    trailingIcon = { if (selected == option) Icon(androidx.compose.material.icons.Icons.Rounded.Check, Copy.currentLayout) },
                    onClick = { expanded = false; onChoose(option) }) }
            }
        }
    }
}
