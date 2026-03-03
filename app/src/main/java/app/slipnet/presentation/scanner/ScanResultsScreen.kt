package app.slipnet.presentation.scanner

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.slipnet.domain.model.E2eTestResult
import app.slipnet.domain.model.ResolverScanResult
import app.slipnet.domain.model.ResolverStatus

private val WorkingGreen = Color(0xFF4CAF50)
private val CensoredOrange = Color(0xFFFF9800)
private val TimeoutGray = Color(0xFF9E9E9E)
private val ErrorRed = Color(0xFFE53935)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanResultsScreen(
    profileId: Long? = null,
    fromProfile: Boolean = false,
    parentBackStackEntry: NavBackStackEntry,
    onNavigateBack: () -> Unit,
    onResolversSelected: (String) -> Unit,
    viewModel: DnsScannerViewModel = hiltViewModel(parentBackStackEntry)
) {
    val uiState by viewModel.uiState.collectAsState()
    val canApply = fromProfile
    val snackbarHostState = remember { SnackbarHostState() }
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    var sortOption by remember { mutableStateOf(SortOption.E2E_SPEED) }
    var scoreFilter by remember { mutableStateOf(ScoreFilter.ALL) }
    var proxyWarningDismissed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("scanner_ui", Context.MODE_PRIVATE) }
    var showSortFilter by remember { mutableStateOf(prefs.getBoolean("show_sort_filter", true)) }
    val scope = rememberCoroutineScope()

    val verifiedCount = uiState.scannerState.results.count {
        it.status == ResolverStatus.TUNNEL_VERIFIED
    }
    val isActive = uiState.scannerState.isScanning || uiState.e2eScannerState.isRunning

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Scan Results",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.stopScan()
                            onNavigateBack()
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (canApply && uiState.selectedResolvers.isNotEmpty()) {
                        Button(
                            onClick = {
                                viewModel.saveRecentDns()
                                onResolversSelected(viewModel.getSelectedResolversString())
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WorkingGreen
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Apply (${uiState.selectedResolvers.size})",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = {
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.padding(bottom = 120.dp)
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Unified progress section
            UnifiedProgressSection(
                verifiedCount = verifiedCount,
                selectedCount = uiState.selectedResolvers.size,
                maxSelected = DnsScannerUiState.MAX_SELECTED_RESOLVERS,
                isScanning = uiState.scannerState.isScanning,
                isE2eRunning = uiState.e2eScannerState.isRunning,
                scanProgress = uiState.scannerState.progress,
                scannedCount = uiState.scannerState.scannedCount,
                totalCount = uiState.scannerState.totalCount,
                e2eTestedCount = uiState.e2eScannerState.testedCount,
                e2eTotalCount = uiState.e2eScannerState.totalCount,
                e2eCurrentResolver = uiState.e2eScannerState.currentResolver,
                e2eCurrentPhase = uiState.e2eScannerState.currentPhase,
                canApply = canApply,
                canRetest = !isActive && uiState.e2eComplete,
                onStop = {
                    viewModel.stopScan()
                    viewModel.stopE2eTest()
                },
                onRetest = { viewModel.startE2eTest(fresh = true) }
            )

            // VPN active warning for E2E
            AnimatedVisibility(
                visible = uiState.isVpnActive && uiState.profileId != null &&
                        !uiState.scannerState.isScanning &&
                        uiState.scannerState.results.any { it.status == ResolverStatus.TUNNEL_VERIFIED },
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = CensoredOrange.copy(alpha = 0.12f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = CensoredOrange,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Disconnect VPN to run tunnel test",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Transparent proxy warning
            AnimatedVisibility(
                visible = uiState.transparentProxyDetected && !proxyWarningDismissed,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Transparent DNS interception detected \u2014 your ISP may be redirecting all DNS traffic. Results may be unreliable.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { proxyWarningDismissed = true },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Results
            val filteredResults = uiState.scannerState.results.filter {
                (it.status == ResolverStatus.TUNNEL_VERIFIED ||
                    it.status == ResolverStatus.TUNNEL_FAILED) &&
                    (it.tunnelTestResult?.score ?: 0) >= scoreFilter.minScore
            }

            val displayResults = when (sortOption) {
                SortOption.SPEED -> filteredResults.sortedBy { it.responseTimeMs ?: Long.MAX_VALUE }
                SortOption.IP -> filteredResults.sortedWith(compareBy {
                    it.host.split(".").map { part -> part.toIntOrNull() ?: 0 }
                        .fold(0L) { acc, i -> acc * 256 + i }
                })
                SortOption.E2E_SPEED -> filteredResults.sortedWith(
                    compareBy<ResolverScanResult> {
                        if (it.status == ResolverStatus.TUNNEL_VERIFIED) 0 else 1
                    }.thenBy { it.e2eTestResult?.totalMs ?: Long.MAX_VALUE }
                )
                SortOption.NONE -> filteredResults
            }

            if (displayResults.isEmpty() && !isActive) {
                ResultsEmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 8.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(displayResults, key = { it.host }) { result ->
                        val isSelected = uiState.selectedResolvers.contains(result.host)
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("DNS", result.host))
                                    if (android.os.Build.VERSION.SDK_INT < 33) {
                                        scope.launch {
                                            snackbarHostState.currentSnackbarData?.dismiss()
                                            launch { snackbarHostState.showSnackbar("Copied ${result.host}") }
                                            delay(1200)
                                            snackbarHostState.currentSnackbarData?.dismiss()
                                        }
                                    }
                                    false // don't dismiss, snap back
                                } else false
                            }
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = false,
                            backgroundContent = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Row(
                                        modifier = Modifier.padding(end = 20.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "Copy",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = "Copy IP",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        ) {
                            ResultsResolverItem(
                                result = result,
                                isSelected = isSelected,
                                isLimitReached = uiState.isSelectionLimitReached,
                                showSelection = canApply,
                                onToggleSelection = if (canApply) {
                                    { viewModel.toggleResolverSelection(result.host) }
                                } else null
                            )
                        }
                    }
                }

                if (displayResults.isNotEmpty() || scoreFilter != ScoreFilter.ALL) {
                    Column(modifier = Modifier.padding(bottom = navBarPadding.calculateBottomPadding())) {
                        // Toggle handle
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showSortFilter = !showSortFilter
                                    prefs.edit().putBoolean("show_sort_filter", showSortFilter).apply()
                                }
                        ) {
                            Icon(
                                imageVector = if (showSortFilter) Icons.Default.KeyboardArrowDown
                                              else Icons.Default.KeyboardArrowUp,
                                contentDescription = if (showSortFilter) "Hide sort & filter" else "Show sort & filter",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        AnimatedVisibility(visible = showSortFilter) {
                            SortControlBar(
                                sortOption = sortOption,
                                onSortOptionChange = { sortOption = it },
                                scoreFilter = scoreFilter,
                                onScoreFilterChange = { scoreFilter = it }
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class ScoreFilter(val label: String, val minScore: Int) {
    ALL("All", 0),
    SCORE_4("4/4", 4)
}

private enum class SortOption {
    NONE, SPEED, IP, E2E_SPEED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortControlBar(
    sortOption: SortOption,
    onSortOptionChange: (SortOption) -> Unit,
    scoreFilter: ScoreFilter,
    onScoreFilterChange: (ScoreFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Sort row
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sort:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FilterChip(
                    selected = sortOption == SortOption.E2E_SPEED,
                    onClick = {
                        onSortOptionChange(if (sortOption == SortOption.E2E_SPEED) SortOption.NONE else SortOption.E2E_SPEED)
                    },
                    label = { Text("Tunnel Speed") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Speed,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.tertiary,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.tertiary
                    )
                )

                FilterChip(
                    selected = sortOption == SortOption.SPEED,
                    onClick = {
                        onSortOptionChange(if (sortOption == SortOption.SPEED) SortOption.NONE else SortOption.SPEED)
                    },
                    label = { Text("DNS Speed") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Speed,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.primary
                    )
                )

                FilterChip(
                    selected = sortOption == SortOption.IP,
                    onClick = {
                        onSortOptionChange(if (sortOption == SortOption.IP) SortOption.NONE else SortOption.IP)
                    },
                    label = { Text("IP") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Dns,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            // Filter row
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Filter:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                ScoreFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = scoreFilter == filter,
                        onClick = { onScoreFilterChange(filter) },
                        label = { Text(filter.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.secondary
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun UnifiedProgressSection(
    verifiedCount: Int,
    selectedCount: Int,
    maxSelected: Int,
    isScanning: Boolean,
    isE2eRunning: Boolean,
    scanProgress: Float,
    scannedCount: Int,
    totalCount: Int,
    e2eTestedCount: Int,
    e2eTotalCount: Int,
    e2eCurrentResolver: String?,
    e2eCurrentPhase: String,
    canApply: Boolean,
    canRetest: Boolean,
    onStop: () -> Unit,
    onRetest: () -> Unit
) {
    val isActive = isScanning || isE2eRunning
    val animatedProgress by animateFloatAsState(
        targetValue = scanProgress,
        label = "progress"
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Hero row: verified count + stop button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (verifiedCount > 0) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = WorkingGreen,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "$verifiedCount server${if (verifiedCount != 1) "s" else ""} found",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = WorkingGreen
                        )
                    } else if (isActive) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Searching for servers...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else {
                        Text(
                            text = "No servers found",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (isActive) {
                    OutlinedButton(
                        onClick = onStop,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Stop", style = MaterialTheme.typography.labelSmall)
                    }
                } else if (canRetest) {
                    OutlinedButton(
                        onClick = onRetest,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Re-test", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Hint text
            if (verifiedCount > 0 && canApply) {
                val hintText = when {
                    selectedCount >= maxSelected && isActive ->
                        "$maxSelected best auto-selected \u2014 scanning for better ones..."
                    selectedCount >= maxSelected ->
                        "$maxSelected best auto-selected"
                    isActive ->
                        "Ready to use \u2014 tap Apply, or wait for more"
                    else ->
                        "$selectedCount auto-selected"
                }
                Text(
                    text = hintText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isActive) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Progress details (secondary)
            if (isActive) {
                val progressParts = mutableListOf<String>()
                if (isScanning) {
                    progressParts.add("Scanned $scannedCount/$totalCount")
                }
                if (isE2eRunning) {
                    progressParts.add("Tunnels $e2eTestedCount/$e2eTotalCount")
                }
                Text(
                    text = progressParts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Current E2E resolver being tested
                if (isE2eRunning && e2eCurrentResolver != null) {
                    Text(
                        text = "$e2eCurrentResolver \u2014 $e2eCurrentPhase",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Subtle progress bar
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    strokeCap = StrokeCap.Round,
                    color = if (verifiedCount > 0) WorkingGreen
                           else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ResultsEmptyState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.SearchOff,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "No working resolvers found",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Try running a new scan or importing a different list",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ResultsResolverItem(
    result: ResolverScanResult,
    isSelected: Boolean,
    isLimitReached: Boolean = false,
    showSelection: Boolean = true,
    onToggleSelection: (() -> Unit)? = null
) {
    val isDisabled = isLimitReached && !isSelected
    val canInteract = showSelection &&
        result.status == ResolverStatus.TUNNEL_VERIFIED &&
        onToggleSelection != null && !isDisabled

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isSelected && showSelection -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = "backgroundColor"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (canInteract && onToggleSelection != null) {
                    Modifier.clickable(onClick = onToggleSelection)
                } else {
                    Modifier
                }
            ),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected && showSelection) 1.dp else 0.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ResultsStatusIcon(status = result.status)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.host,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
                    ),
                    fontWeight = FontWeight.Medium
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ResultsStatusLabel(status = result.status)

                    result.responseTimeMs?.let { ms ->
                        Text(
                            text = "${ms}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    result.tunnelTestResult?.let { tunnelResult ->
                        Text(
                            text = "${tunnelResult.score}/${tunnelResult.maxScore}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                tunnelResult.score == tunnelResult.maxScore -> WorkingGreen
                                tunnelResult.score >= 3 -> CensoredOrange
                                else -> ErrorRed
                            }
                        )
                    }
                }

                result.tunnelTestResult?.let { tunnelResult ->
                    Text(
                        text = tunnelResult.details,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                result.errorMessage?.takeIf { result.tunnelTestResult == null }?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelSmall,
                        color = ErrorRed,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // E2E tunnel test result
                result.e2eTestResult?.let { e2e ->
                    E2eResultRow(e2e)
                }
            }

            if (showSelection &&
                result.status == ResolverStatus.TUNNEL_VERIFIED &&
                onToggleSelection != null) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { if (!isDisabled) onToggleSelection() },
                    enabled = !isDisabled
                )
            }
        }
    }
}

@Composable
private fun E2eResultRow(e2e: E2eTestResult) {
    if (e2e.success) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "E2E ${e2e.totalMs}ms",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = WorkingGreen
            )
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "E2E",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = ErrorRed
            )
            Text(
                text = e2e.errorMessage ?: "Failed",
                style = MaterialTheme.typography.labelSmall,
                color = ErrorRed,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ResultsStatusIcon(status: ResolverStatus) {
    val (icon, color, bgColor) = when (status) {
        ResolverStatus.PENDING -> Triple(
            Icons.Outlined.Schedule,
            MaterialTheme.colorScheme.outline,
            MaterialTheme.colorScheme.surfaceVariant
        )
        ResolverStatus.SCANNING -> Triple(
            Icons.Default.Search,
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primaryContainer
        )
        ResolverStatus.WORKING -> Triple(
            Icons.Default.CheckCircle,
            WorkingGreen,
            WorkingGreen.copy(alpha = 0.1f)
        )
        ResolverStatus.TUNNEL_VERIFIED -> Triple(
            Icons.Default.CheckCircle,
            WorkingGreen,
            WorkingGreen.copy(alpha = 0.1f)
        )
        ResolverStatus.TUNNEL_FAILED -> Triple(
            Icons.Outlined.Error,
            CensoredOrange,
            CensoredOrange.copy(alpha = 0.1f)
        )
        ResolverStatus.CENSORED -> Triple(
            Icons.Default.Warning,
            CensoredOrange,
            CensoredOrange.copy(alpha = 0.1f)
        )
        ResolverStatus.TIMEOUT -> Triple(
            Icons.Outlined.CloudOff,
            TimeoutGray,
            TimeoutGray.copy(alpha = 0.1f)
        )
        ResolverStatus.ERROR -> Triple(
            Icons.Outlined.Error,
            ErrorRed,
            ErrorRed.copy(alpha = 0.1f)
        )
    }

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        if (status == ResolverStatus.SCANNING) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = color
            )
        } else {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ResultsStatusLabel(status: ResolverStatus) {
    val (text, color) = when (status) {
        ResolverStatus.PENDING -> "Pending" to MaterialTheme.colorScheme.outline
        ResolverStatus.SCANNING -> "Scanning..." to MaterialTheme.colorScheme.primary
        ResolverStatus.WORKING -> "Working" to WorkingGreen
        ResolverStatus.TUNNEL_VERIFIED -> "Working" to WorkingGreen
        ResolverStatus.TUNNEL_FAILED -> "Tunnel Failed" to CensoredOrange
        ResolverStatus.CENSORED -> "Censored" to CensoredOrange
        ResolverStatus.TIMEOUT -> "Timeout" to TimeoutGray
        ResolverStatus.ERROR -> "Error" to ErrorRed
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = color
    )
}
