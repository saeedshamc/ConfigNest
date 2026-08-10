package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

    var showMenu by remember { mutableStateOf(false) }

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

            Spacer(modifier = Modifier.height(8.dp))

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
}
