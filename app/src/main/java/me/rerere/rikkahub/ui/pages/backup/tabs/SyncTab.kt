package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SyncServerConfig
import me.rerere.rikkahub.data.db.entity.SyncConflictEntity
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.ui.toast.ToastType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// 线程安全单例时间格式化器，避免每次 Compose 重组重复构造 SimpleDateFormat（兼顾手表小屏性能与 GC 开销）
private val SYNC_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun formatEpochMillis(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(SYNC_TIME_FORMATTER)
}

private fun entityTypeLabel(entityType: String): String = when (entityType) {
    "conversation" -> "对话"
    "folder" -> "文件夹"
    "memory_file" -> "助手记忆"
    "assistant" -> "助手"
    "provider" -> "服务商"
    "favorite" -> "收藏"
    "media_session" -> "生图会话"
    "media_node" -> "生图节点"
    "media_record" -> "生图记录"
    "extra_setting" -> "扩展设置"
    "workspace" -> "工作区配置"
    "skill_bundle" -> "技能"
    "file_asset" -> "附件"
    "ui_preference" -> "界面偏好"
    "translation_history" -> "翻译历史"
    "usage_record" -> "用量记录"
    "gen_media" -> "旧版生图记录"
    "managed_file" -> "文件信息"
    "memory_progress" -> "记忆整理进度"
    else -> entityType
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyncTab(
    vm: BackupVM,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val syncConfig = settings.syncServerConfig
    val isSyncing by vm.isSyncing.collectAsStateWithLifecycle()
    val syncStatusMessage by vm.syncStatusMessage.collectAsStateWithLifecycle()
    val unresolvedConflicts by vm.unresolvedSyncConflicts.collectAsStateWithLifecycle()
    val pendingFileCount by vm.pendingSyncFileCount.collectAsStateWithLifecycle()
    val outboxCount by vm.outboxSyncCount.collectAsStateWithLifecycle()
    val currentRevision by vm.currentSyncRevision.collectAsStateWithLifecycle()

    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isTesting by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var showConflictDialog by remember { mutableStateOf(false) }

    fun updateConfig(block: (SyncServerConfig) -> SyncServerConfig) {
        vm.updateSettings {
            it.copy(syncServerConfig = block(it.syncServerConfig))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 说明卡片
        CardGroup {
            formItem {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = "⚡ 自建增量同步 (Sync V2)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.backup_page_sync_server_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 未解决并发冲突保护横幅（MD3 Expressive 醒目提示容器）
        if (unresolvedConflicts.isNotEmpty()) {
            CardGroup {
                formItem {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Alert01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "检测到 ${unresolvedConflicts.size} 项并发同步冲突",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Text(
                                text = "本地与云端曾同时修改同一记录，两端副本均已完整保留，未丢弃任何内容。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            FilledTonalButton(
                                onClick = { showConflictDialog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("打开冲突中心处理 (${unresolvedConflicts.size})")
                            }
                        }
                    }
                }
            }
        }

        // 同步操作与修订状态卡片
        CardGroup {
            formItem {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = "同步状态与操作",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val neverStr = stringResource(R.string.backup_page_sync_server_never)
                    val lastSyncStr = remember(syncConfig.lastSyncTime, neverStr) {
                        if (syncConfig.lastSyncTime > 0L) formatEpochMillis(syncConfig.lastSyncTime) else neverStr
                    }

                    Text(
                        text = stringResource(R.string.backup_page_sync_server_last_time, lastSyncStr),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "当前修订游标: #$currentRevision · 待推变更: $outboxCount · 附件队列: $pendingFileCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 附件开关关闭或存在排队附件时的明确说明
                    if (!syncConfig.syncFiles || pendingFileCount > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (!syncConfig.syncFiles) {
                                "提示：当前已关闭附件文件传输，对话文字正常同步；缺失的图片/文档已记入队列 ($pendingFileCount)，开启后自动补拉。"
                            } else {
                                "提示：当前有 $pendingFileCount 个附件待下次同步自动重试传输。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }

                    if (syncConfig.authToken.isBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "尚未填写 Sync Token：请在下方填入服务端配置的 RIKKA_SYNC_TOKEN。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    AnimatedVisibility(visible = isSyncing) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(
                                    text = syncStatusMessage.ifEmpty {
                                        stringResource(
                                            R.string.backup_page_sync_server_syncing
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    try {
                                        val summary = vm.startIncrementalSync()
                                        val conflictSuffix = if (summary.conflictsDetected > 0) {
                                            "，保护冲突 ${summary.conflictsDetected} 项"
                                        } else {
                                            ""
                                        }
                                        toaster.show(
                                            "同步完成 (Rev #${summary.currentRevision})：拉取 ${summary.pulledConversations} 条、推送 ${summary.pushedConversations} 条$conflictSuffix",
                                            type = ToastType.Success
                                        )
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        toaster.show(e.message ?: "同步失败", type = ToastType.Error)
                                    }
                                }
                            },
                            enabled = !isSyncing && !isTesting,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(HugeIcons.Refresh01, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(stringResource(R.string.backup_page_sync_server_sync_now))
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isTesting = true
                                    try {
                                        val info = vm.testSyncServer()
                                        toaster.show(
                                            "连接成功 (Sync V${info.protocol_version}, 云端修订 #${info.current_revision})",
                                            type = ToastType.Success
                                        )
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        toaster.show(
                                            context.getString(R.string.backup_page_connection_failed, e.message ?: ""),
                                            type = ToastType.Error
                                        )
                                    } finally {
                                        isTesting = false
                                    }
                                }
                            },
                            enabled = !isSyncing && !isTesting
                        ) {
                            if (isTesting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.backup_page_sync_server_test))
                            }
                        }
                    }
                }
            }
        }

        // 服务器配置卡片
        CardGroup {
            formItem {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "服务器连接参数",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    OutlinedTextField(
                        value = syncConfig.serverUrl,
                        onValueChange = { updateConfig { cfg -> cfg.copy(serverUrl = it) } },
                        label = { Text(stringResource(R.string.backup_page_sync_server_url)) },
                        placeholder = { Text("http://45.207.200.189:8765") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = syncConfig.authToken,
                        onValueChange = { updateConfig { cfg -> cfg.copy(authToken = it) } },
                        label = { Text(stringResource(R.string.backup_page_sync_server_token)) },
                        placeholder = { Text("填入服务端 RIKKA_SYNC_TOKEN") },
                        singleLine = true,
                        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showToken = !showToken }) {
                                Icon(
                                    imageVector = if (showToken) HugeIcons.View else HugeIcons.ViewOff,
                                    contentDescription = null
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.backup_page_sync_server_sync_files),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "基于 SHA-256 增量校验上传与原子下载缺失附件，支持断网续传",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = syncConfig.syncFiles,
                            onCheckedChange = { updateConfig { cfg -> cfg.copy(syncFiles = it) } }
                        )
                    }
                }
            }
        }
    }

    if (showConflictDialog) {
        SyncConflictCenterDialog(
            conflicts = unresolvedConflicts,
            onDismissDialog = { showConflictDialog = false },
            onForkConversation = { item ->
                scope.launch {
                    runCatching { vm.forkConflictConversation(item) }
                        .onSuccess { forked ->
                            toaster.show("已另存为新对话：${forked.title}", type = ToastType.Success)
                        }
                        .onFailure { err ->
                            toaster.show(err.message ?: "另存失败", type = ToastType.Error)
                        }
                }
            },
            onApplyLocal = { item ->
                scope.launch {
                    runCatching { vm.applyConflictLocalCopy(item) }
                        .onSuccess {
                            toaster.show("已恢复本地副本，点击立即同步可推送至云端", type = ToastType.Success)
                        }
                        .onFailure { err ->
                            toaster.show(err.message ?: "应用本地副本失败", type = ToastType.Error)
                        }
                }
            },
            onKeepRemote = { item ->
                scope.launch {
                    runCatching { vm.dismissConflict(item) }
                        .onSuccess {
                            toaster.show("已保留云端版本并归档该冲突", type = ToastType.Success)
                        }
                        .onFailure { err ->
                            toaster.show(err.message ?: "操作失败", type = ToastType.Error)
                        }
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SyncConflictCenterDialog(
    conflicts: List<SyncConflictEntity>,
    onDismissDialog: () -> Unit,
    onForkConversation: (SyncConflictEntity) -> Unit,
    onApplyLocal: (SyncConflictEntity) -> Unit,
    onKeepRemote: (SyncConflictEntity) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissDialog,
        title = {
            Text(
                text = "同步冲突中心 (${conflicts.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (conflicts.isEmpty()) {
                Text("当前没有待处理的同步冲突。")
            } else {
                // 使用带稳定主键与 contentType 的 LazyColumn，防止多条冲突时造成帧抖动或内存暴涨
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = conflicts,
                        key = { it.id },
                        contentType = { "sync_conflict" }
                    ) { conflict ->
                        ConflictItemCard(
                            conflict = conflict,
                            onForkConversation = { onForkConversation(conflict) },
                            onApplyLocal = { onApplyLocal(conflict) },
                            onKeepRemote = { onKeepRemote(conflict) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissDialog) {
                Text("关闭")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConflictItemCard(
    conflict: SyncConflictEntity,
    onForkConversation: () -> Unit,
    onApplyLocal: () -> Unit,
    onKeepRemote: () -> Unit,
) {
    val createdTimeStr = remember(conflict.createdAt) {
        formatEpochMillis(conflict.createdAt)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "[${entityTypeLabel(conflict.entityType)}] ${conflict.title}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "基线 #${conflict.baseRevision} vs 云端 #${conflict.serverRevision} · $createdTimeStr",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (conflict.entityType == "conversation" && conflict.localPayload.isNotBlank()) {
                    FilledTonalButton(onClick = onForkConversation) {
                        Text("另存为新会话", style = MaterialTheme.typography.labelMedium)
                    }
                }
                OutlinedButton(onClick = onApplyLocal) {
                    Text("采用本地覆盖", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onKeepRemote) {
                    Text("保留云端", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
