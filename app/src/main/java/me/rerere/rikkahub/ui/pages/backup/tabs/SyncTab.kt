package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SyncServerConfig
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.ui.toast.ToastType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SyncTab(
    vm: BackupVM,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val syncConfig = settings.syncServerConfig
    val isSyncing by vm.isSyncing.collectAsStateWithLifecycle()
    val syncStatusMessage by vm.syncStatusMessage.collectAsStateWithLifecycle()

    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isTesting by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }

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
                        text = "⚡ 自建轻量增量同步 (Sync Server)",
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

        // 同步操作卡片
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

                    val lastSyncStr = if (syncConfig.lastSyncTime > 0) {
                        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        sdf.format(Date(syncConfig.lastSyncTime))
                    } else {
                        stringResource(R.string.backup_page_sync_server_never)
                    }

                    Text(
                        text = stringResource(R.string.backup_page_sync_server_last_time, lastSyncStr),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    AnimatedVisibility(visible = isSyncing) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(
                                    text = syncStatusMessage.ifEmpty { stringResource(R.string.backup_page_sync_server_syncing) },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    try {
                                        val summary = vm.startIncrementalSync()
                                        toaster.show(
                                            "增量同步成功！拉取 ${summary.pulledConversations} 条会话、${summary.syncedAssistants} 个助手，推送 ${summary.pushedConversations} 条",
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
                                        vm.testSyncServer()
                                        toaster.show(
                                            context.getString(R.string.backup_page_connection_success),
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
                                text = "仅上传与下载本地/远端缺失的附件，避免重复耗流",
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
}
