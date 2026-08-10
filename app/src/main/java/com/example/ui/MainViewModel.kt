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

    val filteredConfigs: StateFlow<List<ConfigItem>> = combine(
        _allConfigs,
        selectedProtocol,
        selectedSourceType,
        searchQuery
    ) { configs, protocol, sourceType, query ->
        configs.filter { item ->
            val matchesProtocol = (protocol == null || item.protocol == protocol)
            val matchesSourceType = (sourceType == null || item.sourceType == sourceType)
            val matchesQuery = query.isBlank() ||
                    item.nameTag.contains(query, ignoreCase = true) ||
                    item.countryFlag.contains(query) ||
                    item.rawConfig.contains(query, ignoreCase = true)

            matchesProtocol && matchesSourceType && matchesQuery
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
            val result = fetcherRepository.fetchAllSources(currentSources)

            _allConfigs.value = result.configs
            val now = System.currentTimeMillis()
            _lastUpdated.value = now

            sourceRepository.saveCachedConfigs(result.configs, now)

            isFetching.value = false
            val msg = "Updated ${result.configs.size} unique configs from ${result.successCount} sources (${result.failedCount} failed)"
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

    fun pingSingleConfig(item: ConfigItem) {
        viewModelScope.launch {
            configLatencies.value = configLatencies.value + (item.id to -2L)
            val target = com.example.util.PingUtil.extractServerTarget(item.rawConfig, item.protocol)
            val result = if (target != null) {
                com.example.util.PingUtil.pingServer(target)
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

            kotlinx.coroutines.coroutineScope {
                val dispatcher = kotlinx.coroutines.Dispatchers.IO
                val semaphore = kotlinx.coroutines.sync.Semaphore(12)

                currentList.map { item ->
                    launch(dispatcher) {
                        semaphore.acquire()
                        try {
                            val target = com.example.util.PingUtil.extractServerTarget(item.rawConfig, item.protocol)
                            val latency = if (target != null) {
                                com.example.util.PingUtil.pingServer(target)
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
