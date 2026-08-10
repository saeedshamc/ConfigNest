package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ConfigFetcherRepository
import com.example.data.SourceRepository
import com.example.model.ConfigItem
import com.example.model.ConfigSource
import com.example.model.ProtocolType
import com.example.model.SourceType
import com.example.model.ThemeMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(
    private val applicationContext: Context,
    private val sourceRepository: SourceRepository,
    private val fetcherRepository: ConfigFetcherRepository
) : ViewModel() {

    val sources: StateFlow<List<ConfigSource>> = sourceRepository.sourcesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val themeMode: StateFlow<ThemeMode> = sourceRepository.themeModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    val proxySettings: StateFlow<com.example.model.ProxySettings> = sourceRepository.proxySettingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.example.model.ProxySettings())

    val autoRemoveDeadConfigs: StateFlow<Boolean> = sourceRepository.autoRemoveDeadFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val bgSyncEnabled: StateFlow<Boolean> = sourceRepository.bgSyncEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val batchLimit: StateFlow<Int?> = sourceRepository.batchLimitFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 100)

    private val _cachedConfigsPair = sourceRepository.cachedConfigsFlow
    private val _allConfigs = MutableStateFlow<List<ConfigItem>>(emptyList())
    val allConfigs: StateFlow<List<ConfigItem>> = _allConfigs

    private val _lastUpdated = MutableStateFlow(0L)
    val lastUpdated: StateFlow<Long> = _lastUpdated

    val isFetching = MutableStateFlow(false)
    val fetchStatusMessage = MutableStateFlow<String?>(null)

    val configLatencies = MutableStateFlow<Map<String, Long>>(emptyMap())
    val isPinging = MutableStateFlow(false)

    // Snackbar event channel
    private val _snackbarEvent = MutableSharedFlow<String>()
    val snackbarEvent: SharedFlow<String> = _snackbarEvent

    val selectedProtocol = MutableStateFlow<ProtocolType?>(null)
    val selectedSourceType = MutableStateFlow<SourceType?>(null)
    val searchQuery = MutableStateFlow("")

    private fun getLatencyRank(lat: Long?): Int {
        return when {
            lat != null && lat >= 0L -> 1  // Valid ping -> Top group
            lat != null && lat == -2L -> 2 // Currently testing...
            lat == null -> 3              // Not tested yet
            else -> 4                     // Failed / Timeout (-1)
        }
    }

    val rawSortedFilteredConfigs: StateFlow<List<ConfigItem>> = combine(
        _allConfigs,
        selectedProtocol,
        selectedSourceType,
        searchQuery,
        configLatencies
    ) { configs, protocol, sourceType, query, latencies ->
        configs.filter { item ->
            val matchesProtocol = (protocol == null || item.protocol == protocol)
            val matchesSourceType = (sourceType == null || item.sourceType == sourceType)
            val matchesQuery = query.isBlank() ||
                    item.nameTag.contains(query, ignoreCase = true) ||
                    item.countryFlag.contains(query) ||
                    item.rawConfig.contains(query, ignoreCase = true)

            matchesProtocol && matchesSourceType && matchesQuery
        }.sortedWith { a, b ->
            val latA = latencies[a.id]
            val latB = latencies[b.id]

            val rankA = getLatencyRank(latA)
            val rankB = getLatencyRank(latB)

            if (rankA != rankB) {
                rankA.compareTo(rankB)
            } else if (rankA == 1) { // Both have valid positive latency
                (latA!!).compareTo(latB!!)
            } else {
                0
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredConfigs: StateFlow<List<ConfigItem>> = combine(
        rawSortedFilteredConfigs,
        batchLimit
    ) { configs, limit ->
        if (limit != null && limit > 0) {
            configs.take(limit)
        } else {
            configs
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            _cachedConfigsPair.collect { (cachedList, time) ->
                if (_allConfigs.value.isEmpty()) {
                    _allConfigs.value = cachedList
                    _lastUpdated.value = time
                    if (cachedList.isEmpty()) {
                        refreshConfigs()
                    }
                }
            }
        }
    }

    fun refreshConfigs() {
        if (isFetching.value) return
        viewModelScope.launch {
            if (!com.example.util.NetworkUtils.isNetworkAvailable(applicationContext)) {
                val offlineMsg = "No internet connection. Please check your network and try again."
                fetchStatusMessage.value = offlineMsg
                _snackbarEvent.emit(offlineMsg)
                return@launch
            }

            isFetching.value = true
            fetchStatusMessage.value = "Fetching live configs in parallel..."

            val currentSources = sources.value.ifEmpty { com.example.data.DefaultSources.LIST }
            val currentProxy = proxySettings.value
            val result = fetcherRepository.fetchAllSources(currentSources, currentProxy)

            val existingList = _allConfigs.value
            val existingSet = existingList.map { it.rawConfig.trim() }.toSet()

            val newlyFetchedUnique = result.configs.filterNot { existingSet.contains(it.rawConfig.trim()) }
            val mergedList = existingList + newlyFetchedUnique

            _allConfigs.value = mergedList
            val now = System.currentTimeMillis()
            _lastUpdated.value = now

            sourceRepository.saveCachedConfigs(mergedList, now)

            isFetching.value = false
            val msg = "Added ${newlyFetchedUnique.size} new configs! Total saved: ${mergedList.size} (${result.failedCount} sources failed)"
            fetchStatusMessage.value = msg
            _snackbarEvent.emit(msg)
        }
    }

    fun setSelectedProtocol(protocol: ProtocolType?) {
        selectedProtocol.value = protocol
    }

    fun setSelectedSourceType(type: SourceType?) {
        selectedSourceType.value = type
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun toggleSource(id: String) {
        viewModelScope.launch {
            val updated = sources.value.map {
                if (it.id == id) it.copy(enabled = !it.enabled) else it
            }
            sourceRepository.saveSources(updated)
        }
    }

    fun addSource(url: String, customName: String?, explicitType: SourceType?) {
        viewModelScope.launch {
            sourceRepository.addSource(url, customName, explicitType)
        }
    }

    fun editSource(id: String, name: String, url: String, type: SourceType, enabled: Boolean) {
        viewModelScope.launch {
            val (normalizedUrl, normalizedType) = sourceRepository.normalizeSourceUrlAndType(url, type)
            val updated = sources.value.map {
                if (it.id == id) ConfigSource(id, name, normalizedUrl, normalizedType, enabled) else it
            }
            sourceRepository.saveSources(updated)
        }
    }

    fun deleteSource(id: String) {
        viewModelScope.launch {
            val updated = sources.value.filterNot { it.id == id }
            sourceRepository.saveSources(updated)
        }
    }

    fun resetSourcesToDefault() {
        viewModelScope.launch {
            sourceRepository.resetToDefaultSources()
        }
    }

    fun updateTheme(mode: ThemeMode) {
        viewModelScope.launch {
            sourceRepository.saveThemeMode(mode)
        }
    }

    fun updateProxySettings(proxy: com.example.model.ProxySettings) {
        viewModelScope.launch {
            sourceRepository.saveProxySettings(proxy)
            _snackbarEvent.emit(if (proxy.enabled) "Proxy enabled (${proxy.type.name} ${proxy.host}:${proxy.port})" else "Proxy disabled")
        }
    }

    fun updateAutoRemoveDead(enabled: Boolean) {
        viewModelScope.launch {
            sourceRepository.saveAutoRemoveDead(enabled)
            _snackbarEvent.emit(if (enabled) "Auto-remove dead configs enabled" else "Auto-remove dead configs disabled")
        }
    }

    fun updateBgSyncEnabled(enabled: Boolean, context: android.content.Context) {
        viewModelScope.launch {
            sourceRepository.saveBgSyncEnabled(enabled)
            if (enabled) {
                com.example.worker.ConfigRefreshWorker.schedule(context)
                _snackbarEvent.emit("Background periodic refresh scheduled (every 6 hrs).")
            } else {
                com.example.worker.ConfigRefreshWorker.cancel(context)
                _snackbarEvent.emit("Background periodic refresh disabled.")
            }
        }
    }

    fun importFromClipboard(clipboardText: String) {
        if (clipboardText.isBlank()) {
            viewModelScope.launch {
                _snackbarEvent.emit("Clipboard is empty.")
            }
            return
        }

        viewModelScope.launch {
            val importedItems = fetcherRepository.parseConfigsFromClipboardText(clipboardText)
            if (importedItems.isEmpty()) {
                _snackbarEvent.emit("No valid V2Ray/Xray links found in clipboard.")
                return@launch
            }

            val currentList = _allConfigs.value
            val currentSet = currentList.map { it.rawConfig.trim() }.toSet()

            val newlyAdded = importedItems.filterNot { currentSet.contains(it.rawConfig.trim()) }
            if (newlyAdded.isEmpty()) {
                _snackbarEvent.emit("All ${importedItems.size} config(s) from clipboard are already saved.")
                return@launch
            }

            val merged = currentList + newlyAdded
            _allConfigs.value = merged
            val now = System.currentTimeMillis()
            _lastUpdated.value = now
            sourceRepository.saveCachedConfigs(merged, now)

            val msg = "Successfully imported ${newlyAdded.size} new config(s) from clipboard!"
            _snackbarEvent.emit(msg)
        }
    }

    fun updateBatchLimit(limit: Int?) {
        viewModelScope.launch {
            sourceRepository.saveBatchLimit(limit)
        }
    }

    fun loadMoreConfigs() {
        viewModelScope.launch {
            val current = batchLimit.value ?: 100
            val next = current + 100
            sourceRepository.saveBatchLimit(next)
            _snackbarEvent.emit("Showing top $next configs")
        }
    }

    fun clearAllConfigs() {
        viewModelScope.launch {
            _allConfigs.value = emptyList()
            configLatencies.value = emptyMap()
            val now = System.currentTimeMillis()
            _lastUpdated.value = now
            sourceRepository.saveCachedConfigs(emptyList(), now)
            _snackbarEvent.emit("Cleared all saved configurations.")
        }
    }

    fun deleteFailedConfigs() {
        viewModelScope.launch {
            val latencies = configLatencies.value
            val beforeCount = _allConfigs.value.size
            val remaining = _allConfigs.value.filter { item ->
                val lat = latencies[item.id]
                lat == null || lat == -2L || lat >= 0L
            }
            val removedCount = beforeCount - remaining.size
            if (removedCount > 0) {
                _allConfigs.value = remaining
                val now = System.currentTimeMillis()
                _lastUpdated.value = now
                sourceRepository.saveCachedConfigs(remaining, now)
                val msg = "Deleted $removedCount timed-out / dead configs! Remaining: ${remaining.size}"
                _snackbarEvent.emit(msg)
            } else {
                _snackbarEvent.emit("No timed-out configs found to delete.")
            }
        }
    }

    fun pingSingleConfig(item: ConfigItem) {
        viewModelScope.launch {
            configLatencies.value = configLatencies.value + (item.id to -2L)
            val target = com.example.util.PingUtil.extractServerTarget(item.rawConfig, item.protocol)
            val currentProxy = proxySettings.value
            val result = if (target != null) {
                com.example.util.PingUtil.pingServer(target, timeoutMs = 3000, proxySettings = currentProxy)
            } else {
                -1L
            }
            configLatencies.value = configLatencies.value + (item.id to result)
        }
    }

    fun pingFilteredConfigs() {
        if (isPinging.value) return
        val currentList = filteredConfigs.value
        if (currentList.isEmpty()) return

        viewModelScope.launch {
            isPinging.value = true
            val updatedMap = configLatencies.value.toMutableMap()
            currentList.forEach { updatedMap[it.id] = -2L }
            configLatencies.value = updatedMap

            val currentProxy = proxySettings.value

            kotlinx.coroutines.coroutineScope {
                val dispatcher = kotlinx.coroutines.Dispatchers.IO
                val semaphore = kotlinx.coroutines.sync.Semaphore(12)

                currentList.map { item ->
                    launch(dispatcher) {
                        semaphore.acquire()
                        try {
                            val target = com.example.util.PingUtil.extractServerTarget(item.rawConfig, item.protocol)
                            val latency = if (target != null) {
                                com.example.util.PingUtil.pingServer(target, timeoutMs = 3000, proxySettings = currentProxy)
                            } else {
                                -1L
                            }
                            configLatencies.value = configLatencies.value + (item.id to latency)
                        } finally {
                            semaphore.release()
                        }
                    }
                }
            }

            isPinging.value = false
            _snackbarEvent.emit("Ping test completed for ${currentList.size} servers!")

            if (autoRemoveDeadConfigs.value) {
                deleteFailedConfigs()
            }
        }
    }

    fun copySingleConfig(context: Context, item: ConfigItem) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("V2Ray Config", item.rawConfig)
        clipboard.setPrimaryClip(clip)
        val message = "Copied ${item.protocol.displayName} config to clipboard!"
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModelScope.launch {
            _snackbarEvent.emit(message)
        }
    }

    fun copyFilteredConfigs(context: Context) {
        val currentList = filteredConfigs.value
        if (currentList.isEmpty()) {
            Toast.makeText(context, "No configs to copy", Toast.LENGTH_SHORT).show()
            return
        }
        val combined = currentList.joinToString("\n") { it.rawConfig }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("V2Ray Config List", combined)
        clipboard.setPrimaryClip(clip)
        val successMessage = "Successfully copied ${currentList.size} configs from current tab!"
        Toast.makeText(context, successMessage, Toast.LENGTH_LONG).show()
        viewModelScope.launch {
            _snackbarEvent.emit(successMessage)
        }
    }

    fun copyAllConfigs(context: Context) {
        val currentList = allConfigs.value
        if (currentList.isEmpty()) {
            Toast.makeText(context, "No configs to copy", Toast.LENGTH_SHORT).show()
            return
        }
        val combined = currentList.joinToString("\n") { it.rawConfig }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("All V2Ray Configs", combined)
        clipboard.setPrimaryClip(clip)
        val successMessage = "Successfully copied ALL ${currentList.size} configs!"
        Toast.makeText(context, successMessage, Toast.LENGTH_LONG).show()
        viewModelScope.launch {
            _snackbarEvent.emit(successMessage)
        }
    }

    fun shareFilteredConfigs(context: Context) {
        val currentList = filteredConfigs.value
        if (currentList.isEmpty()) {
            Toast.makeText(context, "No configs to share", Toast.LENGTH_SHORT).show()
            return
        }
        val text = currentList.joinToString("\n") { it.rawConfig }
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Share ${currentList.size} Configs")
        context.startActivity(shareIntent)
    }

    fun exportConfigsToJson(context: Context, uri: android.net.Uri) {
        val currentList = allConfigs.value
        if (currentList.isEmpty()) {
            Toast.makeText(context, "No configurations available to export", Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch {
            val success = com.example.util.JsonIOUtil.exportConfigsToJson(context, uri, currentList)
            val msg = if (success) {
                "Successfully exported ${currentList.size} configs to JSON!"
            } else {
                "Failed to export configs to JSON."
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            _snackbarEvent.emit(msg)
        }
    }

    fun importConfigsFromJson(context: Context, uri: android.net.Uri) {
        viewModelScope.launch {
            val imported = com.example.util.JsonIOUtil.importConfigsFromJson(context, uri)
            if (imported.isEmpty()) {
                val msg = "No valid configurations found in JSON file."
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                _snackbarEvent.emit(msg)
                return@launch
            }

            val existingRawConfigs = _allConfigs.value.map { it.rawConfig.trim() }.toSet()
            val newConfigs = imported.filterNot { existingRawConfigs.contains(it.rawConfig.trim()) }

            val merged = _allConfigs.value + newConfigs
            _allConfigs.value = merged
            val now = System.currentTimeMillis()
            _lastUpdated.value = now
            sourceRepository.saveCachedConfigs(merged, now)

            val msg = if (newConfigs.isNotEmpty()) {
                "Imported ${newConfigs.size} new configurations (${imported.size - newConfigs.size} duplicates skipped)."
            } else {
                "All ${imported.size} configurations in file are already saved."
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            _snackbarEvent.emit(msg)
        }
    }

    fun formatLastUpdatedTime(timestamp: Long): String {
        if (timestamp <= 0) return "Never"
        val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val appContext = context.applicationContext
            val sourceRepo = SourceRepository(appContext)
            val fetcherRepo = ConfigFetcherRepository()
            return MainViewModel(appContext, sourceRepo, fetcherRepo) as T
        }
    }
}
