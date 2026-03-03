package app.slipnet.presentation.scanner

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.slipnet.domain.model.DnsTransport
import app.slipnet.domain.model.DnsTunnelTestResult
import app.slipnet.domain.model.E2eScannerState
import app.slipnet.domain.model.E2eTestResult
import app.slipnet.domain.model.ResolverScanResult
import app.slipnet.domain.model.ResolverStatus
import app.slipnet.domain.model.ScannerState
import app.slipnet.domain.model.ServerProfile
import app.slipnet.domain.model.TunnelType
import app.slipnet.data.local.datastore.PreferencesDataStore
import app.slipnet.domain.repository.ProfileRepository
import app.slipnet.domain.repository.ResolverScannerRepository
import app.slipnet.domain.repository.VpnRepository
import app.slipnet.tunnel.DnsttBridge
import app.slipnet.tunnel.DomainRouter
import app.slipnet.tunnel.GeoBypassCountry
import app.slipnet.tunnel.SlipstreamBridge
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DnsScannerUiState(
    val profileId: Long? = null,
    val testDomain: String = "google.com",
    val timeoutMs: String = "3000",
    val concurrency: String = "50",
    val resolverList: List<String> = emptyList(),
    val scannerState: ScannerState = ScannerState(),
    val selectedResolvers: Set<String> = emptySet(),
    val recentDnsResolvers: List<String> = emptyList(),
    val isLoadingList: Boolean = false,
    val error: String? = null,
    val listSource: ListSource = ListSource.DEFAULT,
    val selectedCountry: GeoBypassCountry = GeoBypassCountry.IR,
    val sampleCount: Int = 2000,
    val customRangeInput: String = "",
    val showResumeDialog: Boolean = false,
    val transparentProxyDetected: Boolean = false,
    // E2E tunnel test state
    val e2eScannerState: E2eScannerState = E2eScannerState(),
    val testUrl: String = "http://www.google.com/generate_204",
    val e2eTimeoutMs: String = "7000",
    val isVpnActive: Boolean = false,
    val profile: ServerProfile? = null
) {
    companion object {
        const val MAX_SELECTED_RESOLVERS = 8
        internal val E2E_SUPPORTED_TUNNEL_TYPES = setOf(
            TunnelType.SLIPSTREAM, TunnelType.SLIPSTREAM_SSH,
            TunnelType.DNSTT, TunnelType.DNSTT_SSH
        )
    }

    val isSelectionLimitReached: Boolean
        get() = selectedResolvers.size >= MAX_SELECTED_RESOLVERS

    val selectionLimitMessage: String
        get() = "Maximum $MAX_SELECTED_RESOLVERS resolvers can be selected"

    val canRunE2e: Boolean
        get() {
            if (profileId == null || profile == null) return false
            if (profile.tunnelType !in E2E_SUPPORTED_TUNNEL_TYPES) return false
            // DNSTT with DoH transport uses URL, not per-resolver IP
            if ((profile.tunnelType == TunnelType.DNSTT || profile.tunnelType == TunnelType.DNSTT_SSH)
                && profile.dnsTransport == DnsTransport.DOH) return false
            if (isVpnActive) return false
            if (scannerState.isScanning) return false
            if (e2eScannerState.isRunning) return false
            // Check for untested compatible (4/4) resolvers
            val untestedCompatible = scannerState.results.count {
                it.status == ResolverStatus.WORKING && it.tunnelTestResult?.isCompatible == true
            }
            return untestedCompatible > 0
        }

    /** True when some resolvers have been tunnel-tested but others haven't (paused mid-test) */
    val canResumeE2e: Boolean
        get() {
            if (!canRunE2e) return false
            val hasUntested = scannerState.results.any {
                it.status == ResolverStatus.WORKING && it.tunnelTestResult?.isCompatible == true
            }
            val hasTested = scannerState.results.any {
                it.status == ResolverStatus.TUNNEL_VERIFIED || it.status == ResolverStatus.TUNNEL_FAILED
            }
            return hasUntested && hasTested
        }

    /** True when all compatible resolvers have been tunnel-tested */
    val e2eComplete: Boolean
        get() {
            val compatible = scannerState.results.filter {
                (it.status == ResolverStatus.WORKING && it.tunnelTestResult?.isCompatible == true) ||
                    it.status == ResolverStatus.TUNNEL_VERIFIED ||
                    it.status == ResolverStatus.TUNNEL_FAILED
            }
            return compatible.isNotEmpty() && compatible.none { it.status == ResolverStatus.WORKING }
        }
}

enum class ListSource {
    DEFAULT,
    IMPORTED,
    COUNTRY_RANGE,
    CUSTOM_RANGE
}

private data class ScannerSettings(
    val timeoutMs: String,
    val concurrency: String,
    val e2eTimeoutMs: String,
    val testUrl: String
)

// Lightweight models for JSON serialization of scan sessions.
private data class SavedScanSession(
    val resolverList: List<String>,
    val testDomain: String,
    val timeoutMs: String,
    val concurrency: String,
    val listSource: String,
    val scannedCount: Int,
    val workingCount: Int,
    val results: List<SavedResult>,
    val customRangeInput: String? = null
)

private data class SavedResult(
    val host: String,
    val status: String,
    val responseTimeMs: Long?,
    val errorMessage: String?,
    val nsSupport: Boolean?,
    val txtSupport: Boolean?,
    val randomSub1: Boolean?,
    val randomSub2: Boolean?,
    // E2E tunnel test result persistence
    val e2eSuccess: Boolean? = null,
    val e2eTotalMs: Long? = null,
    val e2eTunnelSetupMs: Long? = null,
    val e2eHttpLatencyMs: Long? = null,
    val e2eHttpStatusCode: Int? = null,
    val e2eErrorMessage: String? = null
)

@HiltViewModel
class DnsScannerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val scannerRepository: ResolverScannerRepository,
    private val profileRepository: ProfileRepository,
    private val preferencesDataStore: PreferencesDataStore,
    private val vpnRepository: VpnRepository,
    @ApplicationContext private val appContext: android.content.Context
) : ViewModel() {

    private val profileId: Long? = savedStateHandle.get<Long>("profileId")?.takeIf { it != -1L }

    private val _uiState = MutableStateFlow(DnsScannerUiState(profileId = profileId))
    val uiState: StateFlow<DnsScannerUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null
    private var e2eJob: Job? = null
    private val gson = Gson()

    init {
        loadSavedSession()
        loadRecentDns()
        loadProfile()
        observeVpnState()
    }

    /** One-shot read of persisted scanner settings. Must be called before session restore. */
    private suspend fun loadScannerSettings() {
        try {
            combine(
                preferencesDataStore.scannerTimeoutMs,
                preferencesDataStore.scannerConcurrency,
                preferencesDataStore.scannerE2eTimeoutMs,
                preferencesDataStore.scannerTestUrl
            ) { timeout, concurrency, e2eTimeout, testUrl ->
                ScannerSettings(timeout, concurrency, e2eTimeout, testUrl)
            }.first().let { s ->
                _uiState.value = _uiState.value.copy(
                    timeoutMs = s.timeoutMs,
                    concurrency = s.concurrency,
                    e2eTimeoutMs = s.e2eTimeoutMs,
                    testUrl = s.testUrl
                )
            }
        } catch (e: Exception) {
            Log.w("DnsScanner", "Failed to load scanner settings", e)
        }
    }

    private fun saveScannerSettings() {
        val state = _uiState.value
        viewModelScope.launch {
            try {
                preferencesDataStore.saveScannerSettings(
                    timeoutMs = state.timeoutMs,
                    concurrency = state.concurrency,
                    e2eTimeoutMs = state.e2eTimeoutMs,
                    testUrl = state.testUrl
                )
            } catch (e: Exception) {
                Log.w("DnsScanner", "Failed to save scanner settings", e)
            }
        }
    }

    private fun loadRecentDns() {
        viewModelScope.launch {
            preferencesDataStore.recentDnsResolvers.collect { resolvers ->
                _uiState.value = _uiState.value.copy(recentDnsResolvers = resolvers)
            }
        }
    }

    private fun loadProfile() {
        val id = profileId ?: return
        viewModelScope.launch {
            try {
                val profile = profileRepository.getProfileById(id)
                if (profile != null) {
                    _uiState.value = _uiState.value.copy(
                        profile = profile
                    )
                }
            } catch (e: Exception) {
                Log.w("DnsScanner", "Failed to load profile", e)
            }
        }
    }

    private fun observeVpnState() {
        viewModelScope.launch {
            vpnRepository.connectionState.collect {
                _uiState.value = _uiState.value.copy(isVpnActive = vpnRepository.isConnected())
            }
        }
    }

    private suspend fun loadProfileDomain() {
        val id = profileId ?: return
        try {
            val profile = profileRepository.getProfileById(id) ?: return
            if (!profile.isLocked && profile.domain.isNotBlank()) {
                _uiState.value = _uiState.value.copy(testDomain = profile.domain)
            }
        } catch (e: Exception) {
            Log.w("DnsScanner", "Failed to load profile domain", e)
        }
    }

    fun saveRecentDns() {
        val selected = _uiState.value.selectedResolvers.toList()
        viewModelScope.launch {
            withContext(NonCancellable) {
                preferencesDataStore.addRecentDnsResolvers(selected)
            }
        }
    }

    // --- Saved session persistence ---

    private fun loadSavedSession() {
        viewModelScope.launch {
            // Load persisted scanner settings first (timeout, concurrency, etc.)
            // so they serve as defaults. A restored session will override them.
            loadScannerSettings()
            try {
                val json = preferencesDataStore.getSavedScanSession()
                if (json != null) {
                    val session = gson.fromJson(json, SavedScanSession::class.java)
                    if (session != null && session.resolverList.isNotEmpty() &&
                        session.scannedCount > 0 && session.scannedCount < session.resolverList.size
                    ) {
                        // Restore the previous scan state.
                        val results = session.resolverList.map { host ->
                            val saved = session.results.find { it.host == host }
                            saved?.toScanResult() ?: ResolverScanResult(host = host)
                        }
                        _uiState.value = _uiState.value.copy(
                            resolverList = session.resolverList,
                            testDomain = session.testDomain,
                            timeoutMs = session.timeoutMs,
                            concurrency = session.concurrency,
                            listSource = try { ListSource.valueOf(session.listSource) } catch (_: Exception) { ListSource.DEFAULT },
                            scannerState = ScannerState(
                                isScanning = false,
                                totalCount = session.resolverList.size,
                                scannedCount = session.scannedCount,
                                workingCount = session.workingCount,
                                results = results
                            ),
                            selectedResolvers = emptySet(),
                            customRangeInput = session.customRangeInput ?: ""
                        )
                        // Override testDomain with profile domain (profile takes priority)
                        loadProfileDomain()
                        return@launch
                    }
                }
            } catch (e: Exception) {
                Log.w("DnsScanner", "Failed to load saved session", e)
            }
            // No valid saved session — load default list and pre-fill domain from profile.
            loadDefaultList()
            loadProfileDomain()
        }
    }

    private fun saveScanSessionToStore() {
        val state = _uiState.value
        val scanState = state.scannerState
        if (scanState.scannedCount <= 0 || scanState.scannedCount >= scanState.totalCount) return

        val savedResults = scanState.results
            .filter { it.status != ResolverStatus.PENDING && it.status != ResolverStatus.SCANNING }
            .map { it.toSavedResult() }

        val session = SavedScanSession(
            resolverList = state.resolverList,
            testDomain = state.testDomain,
            timeoutMs = state.timeoutMs,
            concurrency = state.concurrency,
            listSource = state.listSource.name,
            scannedCount = scanState.scannedCount,
            workingCount = scanState.workingCount,
            results = savedResults,
            customRangeInput = state.customRangeInput.ifEmpty { null }
        )

        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    preferencesDataStore.saveScanSession(gson.toJson(session))
                } catch (e: Exception) {
                    Log.w("DnsScanner", "Failed to save scan session", e)
                }
            }
        }
    }

    private fun clearSavedSession() {
        viewModelScope.launch {
            try {
                preferencesDataStore.clearScanSession()
            } catch (_: Exception) {}
        }
    }

    // --- List loading ---

    fun loadDefaultList() {
        clearSavedSession()
        _uiState.value = _uiState.value.copy(
            resolverList = scannerRepository.getDefaultResolvers(),
            listSource = ListSource.DEFAULT,
            scannerState = ScannerState(),
            selectedResolvers = emptySet()
        )
    }

    fun importList(content: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingList = true)
            try {
                val resolvers = scannerRepository.parseResolverList(content)
                if (resolvers.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoadingList = false,
                        error = "No valid IP addresses found in file"
                    )
                } else {
                    clearSavedSession()
                    _uiState.value = _uiState.value.copy(
                        resolverList = resolvers,
                        listSource = ListSource.IMPORTED,
                        scannerState = ScannerState(),
                        selectedResolvers = emptySet(),
                        isLoadingList = false
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingList = false,
                    error = "Failed to parse file: ${e.message}"
                )
            }
        }
    }

    fun updateTestDomain(domain: String) {
        _uiState.value = _uiState.value.copy(testDomain = domain)
    }

    fun updateTimeout(timeout: String) {
        _uiState.value = _uiState.value.copy(timeoutMs = timeout)
        saveScannerSettings()
    }

    fun updateConcurrency(concurrency: String) {
        _uiState.value = _uiState.value.copy(concurrency = concurrency)
        saveScannerSettings()
    }

    fun updateSelectedCountry(country: GeoBypassCountry) {
        _uiState.value = _uiState.value.copy(selectedCountry = country)
    }

    fun updateSampleCount(count: Int) {
        _uiState.value = _uiState.value.copy(sampleCount = count)
    }

    fun loadCountryRangeList() {
        val state = _uiState.value
        _uiState.value = state.copy(isLoadingList = true)

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val ips = scannerRepository.generateCountryRangeIps(
                    context = appContext,
                    countryCode = state.selectedCountry.code,
                    count = state.sampleCount
                )
                if (ips.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoadingList = false,
                        error = "No CIDR ranges found for ${state.selectedCountry.displayName}"
                    )
                } else {
                    clearSavedSession()
                    _uiState.value = _uiState.value.copy(
                        resolverList = ips,
                        listSource = ListSource.COUNTRY_RANGE,
                        scannerState = ScannerState(),
                        selectedResolvers = emptySet(),
                        isLoadingList = false,
                        timeoutMs = "1500"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingList = false,
                    error = "Failed to generate IPs: ${e.message}"
                )
            }
        }
    }

    fun updateCustomRangeInput(text: String) {
        _uiState.value = _uiState.value.copy(customRangeInput = text)
    }

    fun loadCustomRangeList() {
        val input = _uiState.value.customRangeInput
        val ranges = mutableListOf<Pair<Long, Long>>()

        for (line in input.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            val range = parseIpRange(trimmed)
            if (range != null) {
                ranges.add(range)
            }
        }

        if (ranges.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                error = "No valid IP ranges found. Use CIDR (8.8.8.0/24), range (8.8.8.1-8.8.8.254), or single IP."
            )
            return
        }

        // Safety cap check
        var totalCount = 0L
        for ((start, end) in ranges) {
            totalCount += (end - start + 1)
            if (totalCount > 100_000) {
                _uiState.value = _uiState.value.copy(
                    error = "Too many IPs (over 100,000). Use smaller ranges."
                )
                return
            }
        }

        _uiState.value = _uiState.value.copy(isLoadingList = true)

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val ips = scannerRepository.expandIpRanges(ranges)
                clearSavedSession()
                _uiState.value = _uiState.value.copy(
                    resolverList = ips,
                    listSource = ListSource.CUSTOM_RANGE,
                    scannerState = ScannerState(),
                    selectedResolvers = emptySet(),
                    isLoadingList = false,
                    timeoutMs = "1500"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingList = false,
                    error = "Failed to expand IP ranges: ${e.message}"
                )
            }
        }
    }

    private fun parseIpRange(line: String): Pair<Long, Long>? {
        // Try CIDR first (e.g. 8.8.8.0/24)
        DomainRouter.parseCidr(line)?.let { return it }

        // Try range format (e.g. 8.8.8.1-8.8.8.254)
        if ('-' in line) {
            val parts = line.split('-', limit = 2)
            if (parts.size == 2) {
                val start = DomainRouter.ipToLong(parts[0].trim())
                val end = DomainRouter.ipToLong(parts[1].trim())
                if (start != null && end != null && start <= end) {
                    return Pair(start, end)
                }
            }
        }

        // Try single IP (e.g. 8.8.8.8)
        DomainRouter.ipToLong(line)?.let { ip ->
            return Pair(ip, ip)
        }

        return null
    }

    fun toggleResolverSelection(host: String) {
        val current = _uiState.value.selectedResolvers
        if (current.contains(host)) {
            _uiState.value = _uiState.value.copy(selectedResolvers = current - host)
        } else if (current.size < DnsScannerUiState.MAX_SELECTED_RESOLVERS) {
            _uiState.value = _uiState.value.copy(selectedResolvers = current + host)
        }
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedResolvers = emptySet())
    }

    // --- Scan control ---

    fun startScan() {
        val state = _uiState.value
        if (state.resolverList.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "No resolvers to scan")
            return
        }

        if (state.testDomain.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Test domain is required")
            return
        }

        // Check for resumable partial scan.
        val ss = state.scannerState
        if (!ss.isScanning && ss.scannedCount > 0 && ss.scannedCount < ss.totalCount) {
            _uiState.value = _uiState.value.copy(showResumeDialog = true)
            return
        }

        startFreshScan()
    }

    fun dismissResumeDialog() {
        _uiState.value = _uiState.value.copy(showResumeDialog = false)
    }

    fun startFreshScan() {
        _uiState.value = _uiState.value.copy(showResumeDialog = false)
        clearSavedSession()

        val state = _uiState.value
        val timeout = state.timeoutMs.toLongOrNull() ?: 3000L
        val concurrency = state.concurrency.toIntOrNull() ?: 50

        // Initialize scanner state
        val initialResults = state.resolverList.map { host ->
            ResolverScanResult(host = host, status = ResolverStatus.PENDING)
        }

        _uiState.value = _uiState.value.copy(
            scannerState = ScannerState(
                isScanning = true,
                totalCount = state.resolverList.size,
                scannedCount = 0,
                workingCount = 0,
                results = initialResults
            ),
            selectedResolvers = emptySet(),
            error = null,
            transparentProxyDetected = false
        )

        launchScan(
            hosts = state.resolverList,
            allHosts = state.resolverList,
            testDomain = state.testDomain,
            timeout = timeout,
            concurrency = concurrency,
            existingResults = emptyMap(),
            startScannedCount = 0,
            startWorkingCount = 0
        )
    }

    fun resumeScan() {
        _uiState.value = _uiState.value.copy(showResumeDialog = false)
        clearSavedSession()

        val state = _uiState.value
        val timeout = state.timeoutMs.toLongOrNull() ?: 3000L
        val concurrency = state.concurrency.toIntOrNull() ?: 50

        // Determine which hosts were already scanned.
        val existingResults = mutableMapOf<String, ResolverScanResult>()
        var startWorkingCount = 0
        for (result in state.scannerState.results) {
            if (result.status != ResolverStatus.PENDING && result.status != ResolverStatus.SCANNING) {
                existingResults[result.host] = result
                if (result.status == ResolverStatus.WORKING ||
                    result.status == ResolverStatus.TUNNEL_VERIFIED ||
                    result.status == ResolverStatus.TUNNEL_FAILED) startWorkingCount++
            }
        }
        val scannedHosts = existingResults.keys
        val remainingHosts = state.resolverList.filter { it !in scannedHosts }

        if (remainingHosts.isEmpty()) {
            // Nothing left to scan — just mark complete.
            _uiState.value = _uiState.value.copy(
                scannerState = state.scannerState.copy(isScanning = false)
            )
            return
        }

        // Update state: mark remaining as SCANNING, keep scanned results.
        val resumeResults = state.resolverList.map { host ->
            existingResults[host] ?: ResolverScanResult(host = host, status = ResolverStatus.PENDING)
        }
        _uiState.value = _uiState.value.copy(
            scannerState = ScannerState(
                isScanning = true,
                totalCount = state.resolverList.size,
                scannedCount = existingResults.size,
                workingCount = startWorkingCount,
                results = resumeResults
            ),
            selectedResolvers = emptySet(),
            error = null
        )

        launchScan(
            hosts = remainingHosts,
            allHosts = state.resolverList,
            testDomain = state.testDomain,
            timeout = timeout,
            concurrency = concurrency,
            existingResults = existingResults,
            startScannedCount = existingResults.size,
            startWorkingCount = startWorkingCount
        )
    }

    private fun launchScan(
        hosts: List<String>,
        allHosts: List<String>,
        testDomain: String,
        timeout: Long,
        concurrency: Int,
        existingResults: Map<String, ResolverScanResult>,
        startScannedCount: Int,
        startWorkingCount: Int
    ) {
        // Run transparent proxy detection concurrently
        viewModelScope.launch {
            try {
                val detected = scannerRepository.detectTransparentProxy(testDomain)
                if (detected) {
                    _uiState.value = _uiState.value.copy(transparentProxyDetected = true)
                }
            } catch (_: Exception) { }
        }

        // Determine if we should interleave E2E tunnel testing with the DNS scan
        val profile = _uiState.value.profile
        val shouldInterleaveE2e = profile != null &&
            profile.tunnelType in DnsScannerUiState.E2E_SUPPORTED_TUNNEL_TYPES &&
            !((profile.tunnelType == TunnelType.DNSTT || profile.tunnelType == TunnelType.DNSTT_SSH)
                && profile.dnsTransport == DnsTransport.DOH) &&
            !vpnRepository.isConnected()

        val e2eChannel = if (shouldInterleaveE2e) Channel<ResolverScanResult>(Channel.UNLIMITED) else null

        scanJob = viewModelScope.launch {
            val resultsMap = mutableMapOf<String, ResolverScanResult>()
            resultsMap.putAll(existingResults)
            var scannedCount = startScannedCount
            var workingCount = startWorkingCount

            // Mark hosts to scan as SCANNING.
            hosts.forEach { host ->
                resultsMap[host] = ResolverScanResult(host = host, status = ResolverStatus.SCANNING)
            }

            // Initialize E2E state and launch worker if interleaving
            if (shouldInterleaveE2e) {
                _uiState.value = _uiState.value.copy(
                    e2eScannerState = E2eScannerState(
                        isRunning = true,
                        totalCount = 0,
                        testedCount = 0,
                        passedCount = 0
                    )
                )
            }

            var e2eCandidateCount = 0

            // Queue any existing WORKING+compatible resolvers from a resumed scan
            if (e2eChannel != null) {
                for ((_, result) in existingResults) {
                    if (result.status == ResolverStatus.WORKING &&
                        result.tunnelTestResult?.isCompatible == true
                    ) {
                        e2eCandidateCount++
                        e2eChannel.trySend(result)
                    }
                }
                if (e2eCandidateCount > 0) {
                    _uiState.value = _uiState.value.copy(
                        e2eScannerState = _uiState.value.e2eScannerState.copy(
                            totalCount = e2eCandidateCount
                        )
                    )
                }
            }

            // Launch E2E worker coroutine — runs concurrently with DNS scan,
            // processing 4/4 resolvers as they are discovered.
            // E2E tests are sequential (singleton bridges) but overlap with DNS scanning.
            val e2eWorkerJob = if (shouldInterleaveE2e && e2eChannel != null && profile != null) {
                launch {
                    var testedCount = 0
                    var passedCount = 0

                    for (resolver in e2eChannel) {
                        // Early termination: stop after finding enough verified resolvers
                        if (passedCount >= DnsScannerUiState.MAX_SELECTED_RESOLVERS) break

                        scannerRepository.testResolversE2e(
                            resolvers = listOf(resolver.host to resolver.port),
                            profile = profile,
                            testUrl = _uiState.value.testUrl,
                            timeoutMs = _uiState.value.e2eTimeoutMs.toLongOrNull() ?: 7000L,
                            onPhaseUpdate = { r, phase ->
                                _uiState.value = _uiState.value.copy(
                                    e2eScannerState = _uiState.value.e2eScannerState.copy(
                                        currentResolver = r,
                                        currentPhase = phase
                                    )
                                )
                            }
                        ).collect { (host, e2eResult) ->
                            testedCount++
                            if (e2eResult.success) passedCount++

                            val newStatus = if (e2eResult.success)
                                ResolverStatus.TUNNEL_VERIFIED
                            else
                                ResolverStatus.TUNNEL_FAILED

                            // Update shared resultsMap so DNS scan state rebuilds include E2E changes
                            resultsMap[host]?.let { existing ->
                                resultsMap[host] = existing.copy(status = newStatus, e2eTestResult = e2eResult)
                            }

                            _uiState.value = _uiState.value.copy(
                                scannerState = _uiState.value.scannerState.copy(
                                    results = allHosts.map { h ->
                                        resultsMap[h] ?: ResolverScanResult(host = h, status = ResolverStatus.PENDING)
                                    }
                                ),
                                e2eScannerState = _uiState.value.e2eScannerState.copy(
                                    testedCount = testedCount,
                                    passedCount = passedCount,
                                    currentResolver = null,
                                    currentPhase = ""
                                )
                            )
                        }
                    }

                    // E2E worker completed
                    _uiState.value = _uiState.value.copy(
                        e2eScannerState = _uiState.value.e2eScannerState.copy(
                            isRunning = false,
                            currentResolver = null,
                            currentPhase = ""
                        )
                    )
                }
            } else null

            // Store E2E job reference for independent cancellation
            e2eJob = e2eWorkerJob

            scannerRepository.scanResolvers(
                hosts = hosts,
                testDomain = testDomain,
                timeoutMs = timeout,
                concurrency = concurrency
            ).collect { result ->
                resultsMap[result.host] = result
                scannedCount++

                if (result.status == ResolverStatus.WORKING) {
                    workingCount++

                    // Queue compatible (4/4) resolvers for interleaved E2E testing
                    if (e2eChannel != null &&
                        result.tunnelTestResult?.isCompatible == true
                    ) {
                        e2eCandidateCount++
                        _uiState.value = _uiState.value.copy(
                            e2eScannerState = _uiState.value.e2eScannerState.copy(
                                totalCount = e2eCandidateCount
                            )
                        )
                        e2eChannel.trySend(result)
                    }
                }

                _uiState.value = _uiState.value.copy(
                    scannerState = ScannerState(
                        isScanning = scannedCount < allHosts.size,
                        totalCount = allHosts.size,
                        scannedCount = scannedCount,
                        workingCount = workingCount,
                        results = allHosts.map { host ->
                            resultsMap[host] ?: ResolverScanResult(host = host, status = ResolverStatus.PENDING)
                        }
                    )
                )
            }

            // DNS scan completed — close channel so E2E worker finishes remaining items
            e2eChannel?.close()

            // Mark DNS scan as complete
            _uiState.value = _uiState.value.copy(
                scannerState = _uiState.value.scannerState.copy(isScanning = false)
            )
            clearSavedSession()

            // Wait for E2E worker to finish processing remaining candidates
            e2eWorkerJob?.join()
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _uiState.value = _uiState.value.copy(
            scannerState = _uiState.value.scannerState.copy(isScanning = false),
            e2eScannerState = _uiState.value.e2eScannerState.copy(
                isRunning = false,
                currentResolver = null,
                currentPhase = ""
            )
        )
        saveScanSessionToStore()

        // Clean up any running bridge from interleaved E2E
        val profile = _uiState.value.profile ?: return
        if (profile.tunnelType in DnsScannerUiState.E2E_SUPPORTED_TUNNEL_TYPES) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    when (profile.tunnelType) {
                        TunnelType.SLIPSTREAM, TunnelType.SLIPSTREAM_SSH -> {
                            SlipstreamBridge.stopClient()
                            SlipstreamBridge.proxyOnlyMode = false
                        }
                        TunnelType.DNSTT, TunnelType.DNSTT_SSH -> {
                            DnsttBridge.stopClient()
                        }
                        else -> {}
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // --- E2E tunnel testing ---

    fun updateTestUrl(url: String) {
        _uiState.value = _uiState.value.copy(testUrl = url)
        saveScannerSettings()
    }

    fun updateE2eTimeout(value: String) {
        _uiState.value = _uiState.value.copy(e2eTimeoutMs = value)
        saveScannerSettings()
    }

    fun startE2eTest(fresh: Boolean = false) {
        val state = _uiState.value
        val profile = state.profile ?: run {
            _uiState.value = state.copy(error = "No profile loaded")
            return
        }

        if (vpnRepository.isConnected()) {
            _uiState.value = state.copy(error = "Disconnect VPN before running E2E test")
            return
        }

        // Include only compatible (4/4) WORKING and previously tunnel-tested resolvers
        val eligible = state.scannerState.results
            .filter {
                (it.status == ResolverStatus.WORKING && it.tunnelTestResult?.isCompatible == true) ||
                    it.status == ResolverStatus.TUNNEL_VERIFIED ||
                    it.status == ResolverStatus.TUNNEL_FAILED
            }

        if (eligible.isEmpty()) {
            _uiState.value = state.copy(error = "No compatible (4/4) resolvers to test")
            return
        }

        // If fresh, reset tunnel-tested resolvers back to WORKING and clear E2E results
        if (fresh) {
            val clearedResults = state.scannerState.results.map { r ->
                if ((r.status == ResolverStatus.WORKING && r.tunnelTestResult?.isCompatible == true) ||
                    r.status == ResolverStatus.TUNNEL_VERIFIED ||
                    r.status == ResolverStatus.TUNNEL_FAILED
                ) {
                    r.copy(status = ResolverStatus.WORKING, e2eTestResult = null)
                } else r
            }
            _uiState.value = _uiState.value.copy(
                scannerState = state.scannerState.copy(results = clearedResults)
            )
        }

        // Determine which resolvers still need testing (resume support)
        val currentResults = _uiState.value.scannerState.results
        val allEligible = currentResults.filter {
            (it.status == ResolverStatus.WORKING && it.tunnelTestResult?.isCompatible == true) ||
                it.status == ResolverStatus.TUNNEL_VERIFIED ||
                it.status == ResolverStatus.TUNNEL_FAILED
        }
        val alreadyTested = if (fresh) emptySet()
        else allEligible.filter {
            it.status == ResolverStatus.TUNNEL_VERIFIED || it.status == ResolverStatus.TUNNEL_FAILED
        }.map { it.host }.toSet()

        val remaining = allEligible
            .filter { it.host !in alreadyTested }
            .map { it.host to it.port }

        if (remaining.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "All working resolvers already tested")
            return
        }

        val startTestedCount = alreadyTested.size
        val startPassedCount = if (fresh) 0
        else allEligible.count { it.status == ResolverStatus.TUNNEL_VERIFIED }

        _uiState.value = _uiState.value.copy(
            e2eScannerState = E2eScannerState(
                isRunning = true,
                totalCount = allEligible.size,
                testedCount = startTestedCount,
                passedCount = startPassedCount
            )
        )

        e2eJob = viewModelScope.launch {
            var testedCount = startTestedCount
            var passedCount = startPassedCount

            scannerRepository.testResolversE2e(
                resolvers = remaining,
                profile = profile,
                testUrl = _uiState.value.testUrl,
                timeoutMs = _uiState.value.e2eTimeoutMs.toLongOrNull() ?: 5000L,
                onPhaseUpdate = { resolver, phase ->
                    _uiState.value = _uiState.value.copy(
                        e2eScannerState = _uiState.value.e2eScannerState.copy(
                            currentResolver = resolver,
                            currentPhase = phase
                        )
                    )
                }
            ).collect { (host, e2eResult) ->
                testedCount++
                if (e2eResult.success) passedCount++

                val newStatus = if (e2eResult.success)
                    ResolverStatus.TUNNEL_VERIFIED
                else
                    ResolverStatus.TUNNEL_FAILED

                // Update the scan result with the E2E result and status
                val updatedResults = _uiState.value.scannerState.results.map { r ->
                    if (r.host == host) r.copy(status = newStatus, e2eTestResult = e2eResult) else r
                }

                _uiState.value = _uiState.value.copy(
                    scannerState = _uiState.value.scannerState.copy(results = updatedResults),
                    e2eScannerState = _uiState.value.e2eScannerState.copy(
                        testedCount = testedCount,
                        passedCount = passedCount,
                        currentResolver = null,
                        currentPhase = ""
                    )
                )
            }

            // Done
            _uiState.value = _uiState.value.copy(
                e2eScannerState = _uiState.value.e2eScannerState.copy(isRunning = false)
            )
        }
    }

    fun stopE2eTest() {
        e2eJob?.cancel()
        _uiState.value = _uiState.value.copy(
            e2eScannerState = _uiState.value.e2eScannerState.copy(
                isRunning = false,
                currentResolver = null,
                currentPhase = ""
            )
        )

        // Clean up any running bridge
        val profile = _uiState.value.profile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (profile.tunnelType) {
                    TunnelType.SLIPSTREAM, TunnelType.SLIPSTREAM_SSH -> {
                        SlipstreamBridge.stopClient()
                        SlipstreamBridge.proxyOnlyMode = false
                    }
                    TunnelType.DNSTT, TunnelType.DNSTT_SSH -> {
                        DnsttBridge.stopClient()
                    }
                    else -> {}
                }
            } catch (_: Exception) {}
        }
    }

    fun getSelectedResolversString(): String {
        return _uiState.value.selectedResolvers.joinToString(",") { "$it:53" }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
        e2eJob?.cancel()
        // Save partial results so the user can resume after navigating away.
        val ss = _uiState.value.scannerState
        if (ss.scannedCount > 0 && ss.scannedCount < ss.totalCount) {
            // Use a blocking approach since viewModelScope is cancelled.
            val state = _uiState.value
            val savedResults = ss.results
                .filter { it.status != ResolverStatus.PENDING && it.status != ResolverStatus.SCANNING }
                .map { it.toSavedResult() }
            val session = SavedScanSession(
                resolverList = state.resolverList,
                testDomain = state.testDomain,
                timeoutMs = state.timeoutMs,
                concurrency = state.concurrency,
                listSource = state.listSource.name,
                scannedCount = ss.scannedCount,
                workingCount = ss.workingCount,
                results = savedResults,
                customRangeInput = state.customRangeInput.ifEmpty { null }
            )
            try {
                val file = java.io.File(appContext.cacheDir, "scan_session.json")
                file.writeText(gson.toJson(session))
            } catch (_: Exception) {}
        }
    }
}

// --- Conversion helpers ---

private fun ResolverScanResult.toSavedResult() = SavedResult(
    host = host,
    status = status.name,
    responseTimeMs = responseTimeMs,
    errorMessage = errorMessage,
    nsSupport = tunnelTestResult?.nsSupport,
    txtSupport = tunnelTestResult?.txtSupport,
    randomSub1 = tunnelTestResult?.randomSubdomain1,
    randomSub2 = tunnelTestResult?.randomSubdomain2,
    e2eSuccess = e2eTestResult?.success,
    e2eTotalMs = e2eTestResult?.totalMs,
    e2eTunnelSetupMs = e2eTestResult?.tunnelSetupMs,
    e2eHttpLatencyMs = e2eTestResult?.httpLatencyMs,
    e2eHttpStatusCode = e2eTestResult?.httpStatusCode,
    e2eErrorMessage = e2eTestResult?.errorMessage
)

private fun SavedResult.toScanResult() = ResolverScanResult(
    host = host,
    status = try { ResolverStatus.valueOf(status) } catch (_: Exception) { ResolverStatus.PENDING },
    responseTimeMs = responseTimeMs,
    errorMessage = errorMessage,
    tunnelTestResult = if (nsSupport != null) {
        DnsTunnelTestResult(
            nsSupport = nsSupport,
            txtSupport = txtSupport ?: false,
            randomSubdomain1 = randomSub1 ?: false,
            randomSubdomain2 = randomSub2 ?: false
        )
    } else null,
    e2eTestResult = if (e2eSuccess != null) {
        E2eTestResult(
            success = e2eSuccess,
            totalMs = e2eTotalMs ?: 0,
            tunnelSetupMs = e2eTunnelSetupMs ?: 0,
            httpLatencyMs = e2eHttpLatencyMs ?: 0,
            httpStatusCode = e2eHttpStatusCode ?: 0,
            errorMessage = e2eErrorMessage
        )
    } else null
)
