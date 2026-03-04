package app.slipnet.presentation.profiles

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.slipnet.domain.model.CongestionControl
import app.slipnet.domain.model.DnsTransport
import app.slipnet.domain.model.SshAuthType
import app.slipnet.tunnel.DOH_SERVERS
import app.slipnet.tunnel.DohServer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    profileId: Long?,
    onNavigateBack: () -> Unit,
    onNavigateToScanner: ((Long?) -> Unit)? = null,
    selectedResolvers: String? = null,
    viewModel: EditProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()
    var showUnlockDialog by remember { mutableStateOf(false) }
    var unlockPassword by remember { mutableStateOf("") }
    var unlockError by remember { mutableStateOf(false) }

    // Apply selected resolvers from scanner
    LaunchedEffect(selectedResolvers) {
        selectedResolvers?.let { resolvers ->
            if (resolvers.isNotBlank()) {
                viewModel.updateResolvers(resolvers)
            }
        }
    }

    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            if (uiState.showRestartVpnMessage) {
                android.widget.Toast.makeText(
                    context,
                    "Profile saved. Turn VPN off and on to apply changes.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            onNavigateBack()
        }
    }

    // Navigate to scanner after profile is saved
    LaunchedEffect(uiState.savedProfileIdForScanner) {
        uiState.savedProfileIdForScanner?.let { savedId ->
            viewModel.clearScannerNavigation()
            onNavigateToScanner?.invoke(savedId)
        }
    }

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
                    Text(
                        if (uiState.isLocked) "Locked Profile"
                        else if (profileId != null) "Edit Profile"
                        else "Add Profile"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val lockedCanEditDns = uiState.isLocked &&
                            (uiState.isDnsttBased || uiState.isSlipstreamBased)
                    if (!uiState.isLocked || lockedCanEditDns) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(onClick = { viewModel.save() }) {
                                Icon(Icons.Default.Check, contentDescription = "Save")
                            }
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (uiState.isLocked) {
            // Locked profile view — minimal info + unlock button
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .align(Alignment.CenterHorizontally),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = uiState.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = uiState.tunnelType.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (uiState.isDnsttBased || uiState.isSlipstreamBased)
                        "This profile is locked. You can change DNS resolver settings below. Enter the admin password to unlock all settings."
                    else
                        "This profile is locked. Server details are hidden to prevent unauthorized access. Enter the admin password to unlock.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // DNS settings for DNSTT/Slipstream locked profiles
                if (uiState.isDnsttBased || uiState.isSlipstreamBased) {
                    // DNS Transport selector (DNSTT-based profiles only)
                    if (uiState.isDnsttBased) {
                        Text(
                            text = "DNS Transport",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DnsTransport.entries.forEach { transport ->
                                if (uiState.dnsTransport == transport) {
                                    Button(
                                        onClick = { },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(transport.displayName)
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { viewModel.updateDnsTransport(transport) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(transport.displayName)
                                    }
                                }
                            }
                        }
                    }

                    // DoH URL for DNSTT with DoH transport
                    if (uiState.isDnsttBased && uiState.dnsTransport == DnsTransport.DOH) {
                        DohServerSelector(
                            dohUrl = uiState.dohUrl,
                            dohUrlError = uiState.dohUrlError,
                            onUrlChange = { viewModel.updateDohUrl(it) },
                            onPresetSelected = { viewModel.selectDohPreset(it) },
                            customDohUrls = uiState.customDohUrls,
                            onCustomDohUrlsChange = { viewModel.updateCustomDohUrls(it) }
                        )
                    }

                    // Resolver field (not shown when DNSTT with DoH transport)
                    if (!(uiState.isDnsttBased && uiState.dnsTransport == DnsTransport.DOH)) {
                        val isDoT = uiState.isDnsttBased && uiState.dnsTransport == DnsTransport.DOT
                        OutlinedTextField(
                            value = uiState.resolvers,
                            onValueChange = { viewModel.updateResolvers(it) },
                            label = { Text("DNS Resolver") },
                            placeholder = { Text(if (isDoT) "e.g. 8.8.8.8:853" else "e.g. 8.8.8.8:53") },
                            isError = uiState.resolversError != null,
                            supportingText = {
                                Text(uiState.resolversError ?: if (isDoT) "DNS-over-TLS server (IP:853)" else "DNS server address (IP:port)")
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = { viewModel.autoDetectResolver() },
                                    enabled = !uiState.isAutoDetecting
                                ) {
                                    if (uiState.isAutoDetecting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Text(
                                            text = "Local",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (onNavigateToScanner != null) {
                            OutlinedButton(
                                onClick = { viewModel.saveForScanner() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Scan for Working Resolvers")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        unlockPassword = ""
                        unlockError = false
                        showUnlockDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Unlock Profile")
                }
            }

            // Unlock dialog
            if (showUnlockDialog) {
                AlertDialog(
                    onDismissRequest = { showUnlockDialog = false },
                    title = { Text("Unlock Profile") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Enter the admin password to permanently unlock this profile.")
                            OutlinedTextField(
                                value = unlockPassword,
                                onValueChange = {
                                    unlockPassword = it
                                    unlockError = false
                                },
                                label = { Text("Password") },
                                visualTransformation = PasswordVisualTransformation(),
                                isError = unlockError,
                                supportingText = if (unlockError) {
                                    { Text("Incorrect password") }
                                } else null,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.unlockProfile(unlockPassword) { success ->
                                    if (success) {
                                        showUnlockDialog = false
                                    } else {
                                        unlockError = true
                                    }
                                }
                            },
                            enabled = unlockPassword.isNotBlank()
                        ) { Text("Unlock") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showUnlockDialog = false }) { Text("Cancel") }
                    }
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .imePadding()
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Name
                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = { viewModel.updateName(it) },
                    label = { Text("Profile Name") },
                    placeholder = { Text("My VPN Server") },
                    isError = uiState.nameError != null,
                    supportingText = uiState.nameError?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Domain
                OutlinedTextField(
                    value = uiState.domain,
                    onValueChange = { viewModel.updateDomain(it) },
                    label = { Text("Domain") },
                    placeholder = {
                        Text(
                            when {
                                uiState.isDnsttBased -> "t.example.com"
                                else -> "vpn.example.com"
                            }
                        )
                    },
                    isError = uiState.domainError != null,
                    supportingText = {
                        Text(
                            uiState.domainError ?: when {
                                uiState.isDnsttBased -> "DNSTT tunnel domain"
                                uiState.isSlipstreamBased -> "Slipstream tunnel domain"
                                else -> "Tunnel domain"
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // DNSTT Public Key
                if (uiState.isDnsttBased) {
                    OutlinedTextField(
                        value = uiState.dnsttPublicKey,
                        onValueChange = { viewModel.updateDnsttPublicKey(it) },
                        label = { Text("Public Key") },
                        placeholder = { Text("Server's Noise public key (hex)") },
                        isError = uiState.dnsttPublicKeyError != null,
                        supportingText = {
                            Text(uiState.dnsttPublicKeyError ?: "Server's Noise protocol public key in hex format")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // DNS Transport selector (DNSTT-based profiles only)
                if (uiState.isDnsttBased) {
                    Text(
                        text = "DNS Transport",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DnsTransport.entries.forEach { transport ->
                            if (uiState.dnsTransport == transport) {
                                Button(
                                    onClick = { },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(transport.displayName)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { viewModel.updateDnsTransport(transport) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(transport.displayName)
                                }
                            }
                        }
                    }
                }

                // Authoritative Mode toggle (DNSTT-based profiles only)
                if (uiState.isDnsttBased) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Authoritative Mode",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Aggressive query rate for faster speeds",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.dnsttAuthoritative,
                            onCheckedChange = { viewModel.updateDnsttAuthoritative(it) }
                        )
                    }
                    if (uiState.dnsttAuthoritative) {
                        Text(
                            text = "Only use when the DNS resolver is your own server. Public resolvers (Google, Cloudflare) will rate-limit and block your connection.",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }

                // DoH URL for DNSTT with DoH transport
                if (uiState.isDnsttBased && uiState.dnsTransport == DnsTransport.DOH) {
                    DohServerSelector(
                        dohUrl = uiState.dohUrl,
                        dohUrlError = uiState.dohUrlError,
                        onUrlChange = { viewModel.updateDohUrl(it) },
                        onPresetSelected = { viewModel.selectDohPreset(it) },
                        customDohUrls = uiState.customDohUrls,
                        onCustomDohUrlsChange = { viewModel.updateCustomDohUrls(it) }
                    )
                }

                // Resolvers (not shown when DNSTT with DoH transport)
                val showResolvers = !(uiState.isDnsttBased && uiState.dnsTransport == DnsTransport.DOH)
                if (showResolvers) {
                    val isDoT = uiState.isDnsttBased && uiState.dnsTransport == DnsTransport.DOT
                    OutlinedTextField(
                        value = uiState.resolvers,
                        onValueChange = { viewModel.updateResolvers(it) },
                        label = { Text("DNS Resolver") },
                        placeholder = { Text(if (isDoT) "e.g. 8.8.8.8:853" else "e.g. 8.8.8.8:53") },
                        isError = uiState.resolversError != null,
                        supportingText = {
                            Text(uiState.resolversError ?: if (isDoT) "DNS-over-TLS server (IP:853)" else "DNS server address (IP:port)")
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { viewModel.autoDetectResolver() },
                                enabled = !uiState.isAutoDetecting
                            ) {
                                if (uiState.isAutoDetecting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text(
                                        text = "Local",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Scan Resolvers button
                    if (onNavigateToScanner != null) {
                        OutlinedButton(
                            onClick = { viewModel.saveForScanner() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Scan for Working Resolvers")
                        }
                    }
                }

                // Slipstream-specific settings
                if (uiState.isSlipstreamBased) {
                    // Keep-Alive Interval (hidden in authoritative mode — polling subsumes keep-alive)
                    if (!uiState.authoritativeMode) {
                        OutlinedTextField(
                            value = uiState.keepAliveInterval,
                            onValueChange = { viewModel.updateKeepAliveInterval(it) },
                            label = { Text("Keep-Alive Interval (ms)") },
                            placeholder = { Text("200") },
                            supportingText = { Text("QUIC keep-alive interval in milliseconds") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Congestion Control
                    CongestionControlDropdown(
                        selected = uiState.congestionControl,
                        onSelect = { viewModel.updateCongestionControl(it) }
                    )

                    // Authoritative Mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Authoritative Mode",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Use authoritative DNS resolution (--authoritative)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.authoritativeMode,
                            onCheckedChange = { viewModel.updateAuthoritativeMode(it) }
                        )
                    }
                    if (uiState.authoritativeMode) {
                        Text(
                            text = "Only use when the DNS resolver is your own server. Public resolvers (Google, Cloudflare) will rate-limit and block your connection.",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    // GSO Mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "GSO (Generic Segmentation Offload)",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Enable GSO for better performance (--gso)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.gsoEnabled,
                            onCheckedChange = { viewModel.updateGsoEnabled(it) }
                        )
                    }
                }

                // Connection Method section
                Text(
                    text = "Connection Method",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (uiState.useSsh) {
                        OutlinedButton(
                            onClick = { viewModel.setUseSsh(false) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("SOCKS")
                        }
                        Button(
                            onClick = { },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("SSH")
                        }
                    } else {
                        Button(
                            onClick = { },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("SOCKS")
                        }
                        OutlinedButton(
                            onClick = { viewModel.setUseSsh(true) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("SSH")
                        }
                    }
                }

                // SOCKS5 Credentials (optional, when SOCKS selected)
                if (!uiState.useSsh) {
                    Text(
                        text = "SOCKS5 Credentials (Optional)",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    OutlinedTextField(
                        value = uiState.socksUsername,
                        onValueChange = { viewModel.updateSocksUsername(it) },
                        label = { Text("Username") },
                        placeholder = { Text("Enter SOCKS username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    var passwordVisible by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = uiState.socksPassword,
                        onValueChange = { viewModel.updateSocksPassword(it) },
                        label = { Text("Password") },
                        placeholder = { Text("Enter SOCKS password") },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Text(
                                    text = if (passwordVisible) "Hide" else "Show",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // SSH Credentials (when SSH selected for DNSTT/Slipstream)
                if (uiState.useSsh) {
                    Text(
                        text = "SSH Credentials",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    OutlinedTextField(
                        value = uiState.sshPort,
                        onValueChange = { viewModel.updateSshPort(it) },
                        label = { Text("SSH Port") },
                        placeholder = { Text("22") },
                        isError = uiState.sshPortError != null,
                        supportingText = uiState.sshPortError?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = uiState.sshUsername,
                        onValueChange = { viewModel.updateSshUsername(it) },
                        label = { Text("SSH Username") },
                        placeholder = { Text("Enter SSH username") },
                        isError = uiState.sshUsernameError != null,
                        supportingText = uiState.sshUsernameError?.let { { Text(it) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // SSH Auth Type Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (uiState.sshAuthType == SshAuthType.PASSWORD) {
                            Button(
                                onClick = { },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Password")
                            }
                            OutlinedButton(
                                onClick = { viewModel.updateSshAuthType(SshAuthType.KEY) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Key")
                            }
                        } else {
                            OutlinedButton(
                                onClick = { viewModel.updateSshAuthType(SshAuthType.PASSWORD) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Password")
                            }
                            Button(
                                onClick = { },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Key")
                            }
                        }
                    }

                    if (uiState.sshAuthType == SshAuthType.PASSWORD) {
                        // Password auth
                        var sshPasswordVisible by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = uiState.sshPassword,
                            onValueChange = { viewModel.updateSshPassword(it) },
                            label = { Text("SSH Password") },
                            placeholder = { Text("Enter SSH password") },
                            isError = uiState.sshPasswordError != null,
                            supportingText = uiState.sshPasswordError?.let { { Text(it) } },
                            singleLine = true,
                            visualTransformation = if (sshPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { sshPasswordVisible = !sshPasswordVisible }) {
                                    Text(
                                        text = if (sshPasswordVisible) "Hide" else "Show",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        // Key auth
                        val context = LocalContext.current
                        val keyFileLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.OpenDocument()
                        ) { uri: Uri? ->
                            uri?.let {
                                try {
                                    val content = context.contentResolver.openInputStream(it)
                                        ?.bufferedReader()?.readText() ?: ""
                                    viewModel.updateSshPrivateKey(content)
                                } catch (_: Exception) {}
                            }
                        }

                        OutlinedTextField(
                            value = uiState.sshPrivateKey,
                            onValueChange = { viewModel.updateSshPrivateKey(it) },
                            label = { Text("SSH Private Key") },
                            placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                            isError = uiState.sshPrivateKeyError != null,
                            supportingText = uiState.sshPrivateKeyError?.let { { Text(it) } },
                            minLines = 3,
                            maxLines = 8,
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedButton(
                            onClick = { keyFileLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Import Key File")
                        }

                        var passphraseVisible by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = uiState.sshKeyPassphrase,
                            onValueChange = { viewModel.updateSshKeyPassphrase(it) },
                            label = { Text("Key Passphrase (optional)") },
                            placeholder = { Text("Enter passphrase if key is encrypted") },
                            singleLine = true,
                            visualTransformation = if (passphraseVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { passphraseVisible = !passphraseVisible }) {
                                    Text(
                                        text = if (passphraseVisible) "Hide" else "Show",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CongestionControlDropdown(
    selected: CongestionControl,
    onSelect: (CongestionControl) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selected.value.uppercase(),
            onValueChange = { },
            readOnly = true,
            label = { Text("Congestion Control") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            CongestionControl.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.value.uppercase()) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DohServerSelector(
    dohUrl: String,
    dohUrlError: String?,
    onUrlChange: (String) -> Unit,
    onPresetSelected: (DohServer) -> Unit,
    customDohUrls: String = "",
    onCustomDohUrlsChange: (String) -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    val matchingPreset = DOH_SERVERS.find { it.url == dohUrl }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = matchingPreset?.name ?: if (dohUrl.isNotBlank()) "Custom" else "",
            onValueChange = { },
            readOnly = true,
            label = { Text("DoH Server") },
            placeholder = { Text("Select a server") },
            isError = dohUrlError != null,
            supportingText = {
                Text(dohUrlError ?: (matchingPreset?.url ?: "Select a preset or enter custom URL"))
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DOH_SERVERS.forEach { preset ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(preset.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                preset.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        onPresetSelected(preset)
                        expanded = false
                    }
                )
            }
            DropdownMenuItem(
                text = {
                    Text("Custom URL...", style = MaterialTheme.typography.bodyLarge)
                },
                onClick = {
                    onUrlChange("")
                    expanded = false
                }
            )
        }
    }

    // Show custom URL field when no preset matches (and not empty)
    if (matchingPreset == null) {
        OutlinedTextField(
            value = dohUrl,
            onValueChange = onUrlChange,
            label = { Text("Custom DoH URL") },
            placeholder = { Text("https://example.com/dns-query") },
            isError = dohUrlError != null,
            supportingText = if (dohUrl.isNotBlank()) {
                { Text(dohUrlError ?: "Custom DNS-over-HTTPS endpoint") }
            } else null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}


