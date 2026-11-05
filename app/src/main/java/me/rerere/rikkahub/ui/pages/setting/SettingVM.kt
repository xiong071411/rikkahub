package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.engine.IncrementalSyncEngine
import me.rerere.rikkahub.data.sync.engine.SyncSummary

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager,
    private val incrementalSyncEngine: IncrementalSyncEngine,
) :
    ViewModel() {
    // 设置在应用启动时就读进了内存，初始值直接取当前值；
    // 用占位值的话，每次进设置页第一帧都是默认设置，随后才跳成真实数据
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = settingsStore.settingsFlow.value.takeUnless { it.init }
                ?: Settings(init = true, providers = emptyList()),
        )

    val launchCount: StateFlow<Int> = settingsStore.launchCountFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val isSyncing: StateFlow<Boolean> = incrementalSyncEngine.isSyncing
    val syncStatusMessage: StateFlow<String> = incrementalSyncEngine.syncStatusMessage
    val syncErrorMessage: StateFlow<String?> = incrementalSyncEngine.syncErrorMessage

    // 按需订阅（WhileSubscribed）：离开设置页 5 秒后自动解绑 Room 查询，兼顾手表与手机待机功耗
    val unresolvedSyncConflictCount: StateFlow<Int> = incrementalSyncEngine.observeUnresolvedConflictCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val pendingSyncFileCount: StateFlow<Int> = incrementalSyncEngine.observePendingFileCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val outboxSyncCount: StateFlow<Int> = incrementalSyncEngine.observeOutboxCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val currentSyncRevision: StateFlow<Long> = incrementalSyncEngine.observeCurrentRevision()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun triggerIncrementalSync(
        onComplete: ((Result<SyncSummary>) -> Unit)? = null,
    ) {
        incrementalSyncEngine.startBackgroundSync(
            config = settings.value.syncServerConfig,
            onComplete = onComplete,
        )
    }

    fun updateSettings(fn: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(fn)
        }
    }
}
