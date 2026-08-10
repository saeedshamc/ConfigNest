package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import com.example.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ProtocolType
import com.example.model.SourceType
import com.example.ui.MainViewModel
import com.example.ui.components.ConfigCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val filteredConfigs by viewModel.filteredConfigs.collectAsState()
    val allConfigs by viewModel.allConfigs.collectAsState()
    val isFetching by viewModel.isFetching.collectAsState()
    val fetchStatusMessage by viewModel.fetchStatusMessage.collectAsState()
    val lastUpdated by viewModel.lastUpdated.collectAsState()

    val selectedProtocol by viewModel.selectedProtocol.collectAsState()
    val selectedSourceType by viewModel.selectedSourceType.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    val configLatencies by viewModel.configLatencies.collectAsState()
    val isPinging by viewModel.isPinging.collectAsState()

    val rawSortedFilteredConfigs by viewModel.rawSortedFilteredConfigs.collectAsState()
    val proxySettings by viewModel.proxySettings.collectAsState()
    val autoRemoveDeadConfigs by viewModel.autoRemoveDeadConfigs.collectAsState()
    val batchLimit by viewModel.batchLimit.collectAsState()

    var showMenu by remember { mutableStateOf(false) }
    var showProxyDialog by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportConfigsToJson(context, it) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importConfigsFromJson(context, it) }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.v2ray_hub_logo),
                            contentDescription = "V2Ray Hub Logo",
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "V2Ray Hub",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Last sync: ${viewModel.formatLastUpdatedTime(lastUpdated)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.pingFilteredConfigs() },
                        enabled = !isPinging && filteredConfigs.isNotEmpty(),
                        modifier = Modifier.testTag("ping_all_button")
                    ) {
                        if (isPinging) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = "Test Ping Latency"
                            )
                        }
                    }

                    IconButton(
                        onClick = { viewModel.refreshConfigs() },
                        enabled = !isFetching,
                        modifier = Modifier.testTag("refresh_button")
                    ) {
                        if (isFetching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Fetch live configs"
                            )
                        }
                    }

                    IconButton(
                        onClick = { viewModel.copyFilteredConfigs(context) },
                        modifier = Modifier.testTag("copy_tab_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Current Tab Configs"
                        )
                    }

                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.testTag("more_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More Options"
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Copy Current Tab (${filteredConfigs.size})") },
                                onClick = {
                                    viewModel.copyFilteredConfigs(context)
                                    showMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) }
                            )

                            DropdownMenuItem(
                                text = { Text("Copy ALL Configs (${allConfigs.size})") },
                                onClick = {
                                    viewModel.copyAllConfigs(context)
                                    showMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.CopyAll, contentDescription = null) }
                            )

                            DropdownMenuItem(
                                text = { Text("Ping Test Current Tab (${filteredConfigs.size})") },
                                onClick = {
                                    viewModel.pingFilteredConfigs()
                                    showMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.Speed, contentDescription = null) }
                            )

                            DropdownMenuItem(
                                text = { Text("Share Current Tab") },
                                onClick = {
                                    viewModel.shareFilteredConfigs(context)
                                    showMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }
                            )

                            DropdownMenuItem(
                                text = { Text("Delete Timed-Out Configs") },
                                onClick = {
                                    viewModel.deleteFailedConfigs()
                                    showMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                            )

                            DropdownMenuItem(
                                text = { Text("Proxy & Ping Settings") },
                                onClick = {
                                    showProxyDialog = true
                                    showMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null) }
                            )

                            DropdownMenuItem(
                                text = { Text("Export Configs to JSON") },
                                onClick = {
                                    showMenu = false
                                    val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                                    val fileName = "v2ray_configs_${sdf.format(java.util.Date())}.json"
                                    exportLauncher.launch(fileName)
                                },
                                leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) }
                            )

                            DropdownMenuItem(
                                text = { Text("Import Configs from JSON") },
                                onClick = {
                                    showMenu = false
                                    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                                },
                                leadingIcon = { Icon(Icons.Default.FileUpload, contentDescription = null) }
                            )

                            DropdownMenuItem(
                                text = { Text("Clear All Saved Configs") },
                                onClick = {
                                    showClearConfirmDialog = true
                                    showMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Status Banner if fetching
            AnimatedVisibility(visible = isFetching || fetchStatusMessage != null) {
                Surface(
                    color = if (isFetching) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isFetching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = fetchStatusMessage ?: "Ready",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Search by name, country, or host...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("search_input")
            )

            // Protocol & Source Filter Chips
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedProtocol == null && selectedSourceType == null,
                        onClick = {
                            viewModel.setSelectedProtocol(null)
                            viewModel.setSelectedSourceType(null)
                        },
                        label = { Text("All (${allConfigs.size})") },
                        modifier = Modifier.testTag("filter_all")
                    )
                }

                items(ProtocolType.entries) { protocol ->
                    val count = allConfigs.count { it.protocol == protocol }
                    if (count > 0) {
                        FilterChip(
                            selected = selectedProtocol == protocol && selectedSourceType == null,
                            onClick = {
                                if (selectedProtocol == protocol) {
                                    viewModel.setSelectedProtocol(null)
                                } else {
                                    viewModel.setSelectedProtocol(protocol)
                                    viewModel.setSelectedSourceType(null)
                                }
                            },
                            label = { Text("${protocol.displayName} ($count)") },
                            modifier = Modifier.testTag("filter_${protocol.name}")
                        )
                    }
                }

                item {
                    val tgCount = allConfigs.count { it.sourceType == SourceType.TELEGRAM }
                    if (tgCount > 0) {
                        FilterChip(
                            selected = selectedSourceType == SourceType.TELEGRAM,
                            onClick = {
                                if (selectedSourceType == SourceType.TELEGRAM) {
                                    viewModel.setSelectedSourceType(null)
                                } else {
                                    viewModel.setSelectedSourceType(SourceType.TELEGRAM)
                                    viewModel.setSelectedProtocol(null)
                                }
                            },
                            label = { Text("Telegram ($tgCount)") },
                            modifier = Modifier.testTag("filter_telegram")
                        )
                    }
                }

                item {
                    val ghCount = allConfigs.count { it.sourceType == SourceType.GITHUB }
                    if (ghCount > 0) {
                        FilterChip(
                            selected = selectedSourceType == SourceType.GITHUB,
                            onClick = {
                                if (selectedSourceType == SourceType.GITHUB) {
                                    viewModel.setSelectedSourceType(null)
                                } else {
                                    viewModel.setSelectedSourceType(SourceType.GITHUB)
                                    viewModel.setSelectedProtocol(null)
                                }
                            },
                            label = { Text("GitHub ($ghCount)") },
                            modifier = Modifier.testTag("filter_github")
                        )
                    }
                }
            }

            // Batch Limit and Quick Action Controls Row
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Batch:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val limitOptions = listOf(50, 100, 200, 500, null)
                        limitOptions.forEach { limit ->
                            val isSelected = batchLimit == limit
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.updateBatchLimit(limit) },
                                label = {
                                    Text(
                                        text = if (limit == null) "All" else "$limit",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                modifier = Modifier
                                    .height(28.dp)
                                    .testTag("batch_limit_${limit ?: "all"}")
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(
                            onClick = { viewModel.deleteFailedConfigs() },
                            modifier = Modifier.size(32.dp).testTag("delete_dead_configs_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Delete Timed-out Configs",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = { showProxyDialog = true },
                            modifier = Modifier.size(32.dp).testTag("proxy_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Dns,
                                contentDescription = "Proxy & Ping Settings",
                                tint = if (proxySettings.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Display counter summary
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Showing ${filteredConfigs.size} of ${rawSortedFilteredConfigs.size} matching (${allConfigs.size} total)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (proxySettings.enabled) {
                    Text(
                        text = "Proxy: ${proxySettings.type.name} ${proxySettings.host}:${proxySettings.port}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Main Config List with Pull-to-Refresh
            val pullToRefreshState = rememberPullToRefreshState()

            PullToRefreshBox(
                isRefreshing = isFetching,
                onRefresh = { viewModel.refreshConfigs() },
                state = pullToRefreshState,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                if (filteredConfigs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "🌐",
                                fontSize = 48.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (allConfigs.isEmpty()) "No configs loaded yet" else "No matching configs found",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (allConfigs.isEmpty()) "Tap 'Fetch All' or pull down to load live configs in parallel from GitHub and Telegram sources."
                                else "Try clearing search or filter chips to view all extracted configs.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { viewModel.refreshConfigs() },
                                modifier = Modifier.testTag("empty_fetch_button")
                            ) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Fetch Live Configs")
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 80.dp, start = 16.dp, end = 16.dp, top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(
                            items = filteredConfigs,
                            key = { it.id }
                        ) { item ->
                            ConfigCard(
                                item = item,
                                onCopy = { viewModel.copySingleConfig(context, item) },
                                latency = configLatencies[item.id],
                                onPing = { viewModel.pingSingleConfig(item) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Proxy Settings Dialog
    if (showProxyDialog) {
        var proxyEnabled by remember { mutableStateOf(proxySettings.enabled) }
        var proxyType by remember { mutableStateOf(proxySettings.type) }
        var proxyHost by remember { mutableStateOf(proxySettings.host) }
        var proxyPortStr by remember { mutableStateOf(proxySettings.port.toString()) }
        var autoRemoveDead by remember { mutableStateOf(autoRemoveDeadConfigs) }

        AlertDialog(
            onDismissRequest = { showProxyDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Proxy & Ping Settings")
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-remove Dead Configs", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("Automatically delete configs that fail ping test (-1 ms)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = autoRemoveDead,
                            onCheckedChange = { autoRemoveDead = it }
                        )
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Use Proxy for Fetch & Ping", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Switch(
                                    checked = proxyEnabled,
                                    onCheckedChange = { proxyEnabled = it }
                                )
                            }

                            if (proxyEnabled) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = proxyType == com.example.model.ProxyType.SOCKS,
                                            onClick = { proxyType = com.example.model.ProxyType.SOCKS }
                                        )
                                        Text("SOCKS5", style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = proxyType == com.example.model.ProxyType.HTTP,
                                            onClick = { proxyType = com.example.model.ProxyType.HTTP }
                                        )
                                        Text("HTTP", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }

                                OutlinedTextField(
                                    value = proxyHost,
                                    onValueChange = { proxyHost = it },
                                    label = { Text("Proxy Host Address") },
                                    placeholder = { Text("127.0.0.1") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = proxyPortStr,
                                    onValueChange = { proxyPortStr = it.filter { c -> c.isDigit() } },
                                    label = { Text("Proxy Port") },
                                    placeholder = { Text("1080") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val port = proxyPortStr.toIntOrNull() ?: 1080
                    viewModel.updateProxySettings(
                        com.example.model.ProxySettings(
                            enabled = proxyEnabled,
                            type = proxyType,
                            host = proxyHost.trim().ifEmpty { "127.0.0.1" },
                            port = port
                        )
                    )
                    viewModel.updateAutoRemoveDead(autoRemoveDead)
                    showProxyDialog = false
                }) {
                    Text("Save Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showProxyDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Clear All Confirm Dialog
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = {
                Text("Clear All Saved Configs")
            },
            text = {
                Text("Are you sure you want to delete all saved configurations from local database?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllConfigs()
                        showClearConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
