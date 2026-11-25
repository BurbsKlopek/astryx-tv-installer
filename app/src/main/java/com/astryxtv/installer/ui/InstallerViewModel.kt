package com.astryxtv.installer.ui

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.astryxtv.installer.adb.AdbClient
import com.astryxtv.installer.adb.AdbKeyManager
import com.astryxtv.installer.adb.DiscoveredTv
import com.astryxtv.installer.adb.LanTvScanner
import com.astryxtv.installer.core.InstallOptions
import com.astryxtv.installer.core.InstallPhase
import com.astryxtv.installer.core.LauncherInstaller
import com.astryxtv.installer.core.ReleaseAsset
import com.astryxtv.installer.core.ReleaseFetcher
import com.astryxtv.installer.core.TvInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ApkSourceMode {
    BUNDLED,
    GITHUB_LATEST,
    LOCAL_FILE
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    AWAITING_TV_APPROVAL,
    CONNECTED,
    ERROR
}

data class InstallerUiState(
    val themeId: InstallerThemeId = InstallerThemeId.TOKYO_CYBER,
    val accentOverride: AccentOverrideId = AccentOverrideId.THEME_DEFAULT,
    val ambientGlowEnabled: Boolean = true,
    val tvHostInput: String = "",
    val tvPortInput: String = "5555",
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val tvInfo: TvInfo? = null,
    val connectionError: String? = null,
    val isScanning: Boolean = false,
    val discoveredTvs: List<DiscoveredTv> = emptyList(),
    val localSubnetHint: String? = null,
    val hasBundledApk: Boolean = false,
    val bundledVersion: String = "v1.2.0",
    val bundledSizeMb: String = "",
    val apkSource: ApkSourceMode = ApkSourceMode.BUNDLED,
    val githubRelease: ReleaseAsset? = null,
    val isCheckingGithub: Boolean = false,
    val githubError: String? = null,
    val pickedLocalApkName: String? = null,
    val pickedLocalApkFile: File? = null,
    val disablePreviousLauncher: Boolean = true,
    val overridePreviousLauncher: String = "",
    val grantOverlayPermission: Boolean = true,
    val grantTclAutoStart: Boolean = true,
    val grantInstallPackages: Boolean = false,
    val isBusy: Boolean = false,
    val activePhase: InstallPhase? = null,
    val completedPhases: Set<InstallPhase> = emptySet(),
    val failedPhase: InstallPhase? = null,
    val transferProgress: Float? = null,
    val transferLabel: String = "",
    val statusBanner: StatusBanner? = null,
    val savedPreviousLauncher: String? = null,
    val restoreCustomPackage: String = "",
    val restoreAlsoUninstall: Boolean = false,
    val logs: List<String> = emptyList()
)

data class StatusBanner(
    val title: String,
    val detail: String,
    val isError: Boolean
)

class InstallerViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("astryx_tv_installer_prefs", Context.MODE_PRIVATE)
    private val keyManager by lazy {
        AdbKeyManager(keyDir = File(application.filesDir, "adbkey"))
    }
    private var activeClient: AdbClient? = null

    private val _ui = MutableStateFlow(InstallerUiState())
    val ui: StateFlow<InstallerUiState> = _ui.asStateFlow()

    init {
        val uiModeMgr = application.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val isTv = uiModeMgr?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val savedHost = prefs.getString("last_tv_host", "")?.ifBlank { if (isTv) "127.0.0.1" else "" }
            ?: if (isTv) "127.0.0.1" else ""
        val savedPort = prefs.getString("last_tv_port", "5555") ?: "5555"
        val savedTheme = InstallerThemeId.fromId(prefs.getString("theme_id", null))
        val savedAccent = AccentOverrideId.fromId(prefs.getString("accent_override_id", null))
        val ambientGlow = prefs.getBoolean("ambient_glow", true)
        val (hasBundled, bundledVer, bundledMb) = inspectBundledAsset()
        val subnet = LanTvScanner.detectLocalIpv4()?.substringBeforeLast('.')?.plus(".x")

        _ui.update {
            it.copy(
                themeId = savedTheme,
                accentOverride = savedAccent,
                ambientGlowEnabled = ambientGlow,
                tvHostInput = savedHost,
                tvPortInput = savedPort,
                localSubnetHint = subnet,
                hasBundledApk = hasBundled,
                bundledVersion = bundledVer,
                bundledSizeMb = bundledMb,
                apkSource = if (hasBundled) ApkSourceMode.BUNDLED else ApkSourceMode.GITHUB_LATEST,
                savedPreviousLauncher = savedHost.takeIf { h -> h.isNotBlank() }?.let { h -> loadSavedPrevious(h) }
            )
        }
    }

    private fun inspectBundledAsset(): Triple<Boolean, String, String> {
        val assets = getApplication<Application>().assets
        val hasApk = runCatching { assets.openFd(BUNDLED_ASSET_NAME).use { it.length } }.getOrNull()
            ?: runCatching { assets.open(BUNDLED_ASSET_NAME).use { it.available().toLong() } }.getOrNull()
        if (hasApk == null || hasApk <= 0L) {
            return Triple(false, "", "")
        }
        val ver = runCatching {
            assets.open(BUNDLED_VERSION_NAME).bufferedReader().use { it.readText().trim() }
        }.getOrDefault("v1.2.0").ifBlank { "v1.2.0" }
        val mb = String.format(Locale.US, "%.2f MB", hasApk / 1_048_576.0)
        return Triple(true, ver, mb)
    }

    fun selectTheme(themeId: InstallerThemeId) {
        prefs.edit()
            .putString("theme_id", themeId.id)
            .putString("accent_override_id", AccentOverrideId.THEME_DEFAULT.id)
            .apply()
        _ui.update { it.copy(themeId = themeId, accentOverride = AccentOverrideId.THEME_DEFAULT) }
    }

    fun selectAccentOverride(accent: AccentOverrideId) {
        prefs.edit().putString("accent_override_id", accent.id).apply()
        _ui.update { it.copy(accentOverride = accent) }
    }

    fun setAmbientGlow(enabled: Boolean) {
        prefs.edit().putBoolean("ambient_glow", enabled).apply()
        _ui.update { it.copy(ambientGlowEnabled = enabled) }
    }

    fun updateTvHost(host: String) {
        val cleaned = host.trim()
        _ui.update {
            it.copy(
                tvHostInput = cleaned,
                savedPreviousLauncher = cleaned.takeIf { h -> h.isNotBlank() }?.let { h -> loadSavedPrevious(h) }
            )
        }
    }

    fun updateTvPort(port: String) {
        val digits = port.filter { it.isDigit() }.take(5)
        _ui.update { it.copy(tvPortInput = digits) }
    }

    fun selectApkSource(mode: ApkSourceMode) {
        _ui.update { it.copy(apkSource = mode, githubError = null) }
        if (mode == ApkSourceMode.GITHUB_LATEST && _ui.value.githubRelease == null) {
            refreshGithubRelease()
        }
    }

    fun setDisablePreviousLauncher(enabled: Boolean) {
        _ui.update { it.copy(disablePreviousLauncher = enabled) }
    }

    fun setOverridePreviousLauncher(pkg: String) {
        _ui.update { it.copy(overridePreviousLauncher = pkg.trim()) }
    }

    fun setGrantOverlayPermission(enabled: Boolean) {
        _ui.update { it.copy(grantOverlayPermission = enabled) }
    }

    fun setGrantTclAutoStart(enabled: Boolean) {
        _ui.update { it.copy(grantTclAutoStart = enabled) }
    }

    fun setGrantInstallPackages(enabled: Boolean) {
        _ui.update { it.copy(grantInstallPackages = enabled) }
    }

    fun setRestoreCustomPackage(pkg: String) {
        _ui.update { it.copy(restoreCustomPackage = pkg.trim()) }
    }

    fun setRestoreAlsoUninstall(enabled: Boolean) {
        _ui.update { it.copy(restoreAlsoUninstall = enabled) }
    }

    fun dismissBanner() {
        _ui.update { it.copy(statusBanner = null) }
    }

    fun clearLogs() {
        _ui.update { it.copy(logs = emptyList()) }
    }

    fun scanLan() {
        if (_ui.value.isScanning) return
        val port = _ui.value.tvPortInput.toIntOrNull()?.takeIf { it in 1..65535 } ?: 5555
        _ui.update {
            it.copy(
                isScanning = true,
                discoveredTvs = emptyList(),
                localSubnetHint = LanTvScanner.detectLocalIpv4()?.substringBeforeLast('.')?.plus(".x")
            )
        }
        appendLog("Scanning local Wi-Fi subnet on port $port...")
        viewModelScope.launch {
            val found = LanTvScanner.scanSubnet(port = port) { tv ->
                _ui.update { cur ->
                    if (cur.discoveredTvs.any { it.ip == tv.ip }) cur
                    else cur.copy(discoveredTvs = (cur.discoveredTvs + tv).sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 })
                }
            }
            _ui.update { it.copy(isScanning = false, discoveredTvs = found) }
            if (found.isEmpty()) {
                appendLog("No TVs with port $port open responded. Enable USB / Network Debugging on the TV or enter its IP manually.")
            } else {
                appendLog("Found ${found.size} ADB device(s): ${found.joinToString { it.ip }}")
            }
        }
    }

    fun pickDiscoveredTv(tv: DiscoveredTv) {
        updateTvHost(tv.ip)
        updateTvPort(tv.port.toString())
        connectAndProbe()
    }

    fun connectAndProbe() {
        if (_ui.value.isBusy) return
        val parsed = parseHostPort(_ui.value.tvHostInput, _ui.value.tvPortInput)
        if (parsed == null) {
            _ui.update {
                it.copy(
                    connectionState = ConnectionState.ERROR,
                    connectionError = "Enter your TV's IP address (for example 192.168.1.200)."
                )
            }
            return
        }
        val (host, port) = parsed
        prefs.edit()
            .putString("last_tv_host", host)
            .putString("last_tv_port", port.toString())
            .apply()

        _ui.update {
            it.copy(
                tvHostInput = host,
                tvPortInput = port.toString(),
                connectionState = ConnectionState.CONNECTING,
                connectionError = null,
                statusBanner = null,
                savedPreviousLauncher = loadSavedPrevious(host)
            )
        }
        appendLog("Connecting to $host:$port over Wi-Fi ADB...")

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    activeClient?.close()
                    val client = AdbClient(host = host, port = port, keyManager = keyManager)
                    client.connect(
                        onAwaitingTvApproval = {
                            appendLog("Waiting for 'Allow USB debugging?' approval on the TV screen...")
                            _ui.update { it.copy(connectionState = ConnectionState.AWAITING_TV_APPROVAL) }
                        }
                    )
                    activeClient = client
                    LauncherInstaller.probeTv(client) { line -> appendLog(line) }
                }
            }

            result.onSuccess { info ->
                _ui.update {
                    it.copy(
                        connectionState = ConnectionState.CONNECTED,
                        tvInfo = info,
                        connectionError = null
                    )
                }
            }.onFailure { err ->
                val msg = err.message ?: "Connection failed."
                appendLog("ERROR: $msg")
                _ui.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        tvInfo = null,
                        connectionError = msg
                    )
                }
            }
        }
    }

    fun refreshGithubRelease() {
        if (_ui.value.isCheckingGithub) return
        _ui.update { it.copy(isCheckingGithub = true, githubError = null) }
        appendLog("Checking GitHub Releases for latest Astryx Rust Launcher...")
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { ReleaseFetcher.fetchLatestReleaseInfo() } }
            res.onSuccess { asset ->
                val mb = if (asset.sizeBytes > 0) String.format(Locale.US, " (%.2f MB)", asset.sizeBytes / 1_048_576.0) else ""
                appendLog("Latest GitHub release: ${asset.tagName} — ${asset.fileName}$mb")
                _ui.update { it.copy(isCheckingGithub = false, githubRelease = asset, githubError = null) }
            }.onFailure { err ->
                val msg = err.message ?: "Could not reach GitHub."
                appendLog("GitHub check failed: $msg")
                _ui.update { it.copy(isCheckingGithub = false, githubError = msg) }
            }
        }
    }

    fun importLocalApk(uri: Uri) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val cr = getApplication<Application>().contentResolver
                    var displayName = "selected-launcher.apk"
                    cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            c.getString(0)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                        }
                    }
                    val cacheDir = File(getApplication<Application>().cacheDir, "picked-apk")
                    cacheDir.mkdirs()
                    val dest = File(cacheDir, "local-selected.apk")
                    cr.openInputStream(uri)?.use { inp ->
                        FileOutputStream(dest).use { out -> inp.copyTo(out) }
                    } ?: error("Could not read selected file.")
                    if (!ReleaseFetcher.hasApkMagic(dest)) {
                        dest.delete()
                        error("Selected file is not a valid Android APK.")
                    }
                    displayName to dest
                }
            }
            res.onSuccess { (name, file) ->
                val mb = String.format(Locale.US, "%.2f MB", file.length() / 1_048_576.0)
                appendLog("Selected local APK: $name ($mb)")
                _ui.update {
                    it.copy(
                        apkSource = ApkSourceMode.LOCAL_FILE,
                        pickedLocalApkName = "$name ($mb)",
                        pickedLocalApkFile = file,
                        statusBanner = null
                    )
                }
            }.onFailure { err ->
                val msg = err.message ?: "Could not read APK file."
                appendLog("ERROR: $msg")
                _ui.update {
                    it.copy(statusBanner = StatusBanner("Invalid APK file", msg, isError = true))
                }
            }
        }
    }

    fun installAstryx() {
        val state = _ui.value
        if (state.isBusy) return
        val parsed = parseHostPort(state.tvHostInput, state.tvPortInput)
        if (parsed == null) {
            _ui.update {
                it.copy(statusBanner = StatusBanner("TV IP needed", "Enter your TV's IP address first.", isError = true))
            }
            return
        }
        val (host, port) = parsed

        _ui.update {
            it.copy(
                isBusy = true,
                activePhase = InstallPhase.PREFLIGHT,
                completedPhases = emptySet(),
                failedPhase = null,
                transferProgress = null,
                transferLabel = "",
                statusBanner = null
            )
        }

        viewModelScope.launch {
            var currentPhase = InstallPhase.PREFLIGHT
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val apkFile = prepareApkFile()
                    val client = ensureConnected(host, port)
                    val options = InstallOptions(
                        disablePreviousLauncher = _ui.value.disablePreviousLauncher,
                        overridePreviousLauncher = _ui.value.overridePreviousLauncher.ifBlank { null },
                        grantOverlayPermission = _ui.value.grantOverlayPermission,
                        grantTclAutoStart = _ui.value.grantTclAutoStart,
                        grantInstallPackages = _ui.value.grantInstallPackages
                    )
                    val outcome = LauncherInstaller.installLauncher(
                        session = client,
                        apkFile = apkFile,
                        options = options,
                        onPhase = { phase ->
                            currentPhase = phase
                            _ui.update { cur ->
                                val done = InstallPhase.entries.filter { it.stepNumber < phase.stepNumber }.toSet()
                                cur.copy(
                                    activePhase = phase,
                                    completedPhases = done,
                                    transferProgress = if (phase == InstallPhase.UPLOAD) 0f else null,
                                    transferLabel = if (phase == InstallPhase.UPLOAD) "Starting transfer..." else ""
                                )
                            }
                        },
                        onUploadProgress = { sent, total, method ->
                            val frac = if (total > 0) (sent.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                            val sentMb = sent / 1_048_576.0
                            val totMb = total / 1_048_576.0
                            _ui.update {
                                it.copy(
                                    transferProgress = frac,
                                    transferLabel = String.format(Locale.US, "%s  %.1f / %.1f MB", method, sentMb, totMb)
                                )
                            }
                        },
                        log = { line -> appendLog(line) }
                    )
                    outcome.disabledLauncher?.let { savePreviousLauncher(host, it) }
                    val refreshed = runCatching { LauncherInstaller.probeTv(client) }.getOrNull()
                    outcome to refreshed
                }
            }

            res.onSuccess { (outcome, refreshed) ->
                _ui.update { cur ->
                    cur.copy(
                        isBusy = false,
                        activePhase = null,
                        completedPhases = InstallPhase.entries.toSet(),
                        failedPhase = null,
                        transferProgress = null,
                        transferLabel = "",
                        connectionState = ConnectionState.CONNECTED,
                        tvInfo = refreshed ?: cur.tvInfo,
                        savedPreviousLauncher = outcome.disabledLauncher ?: cur.savedPreviousLauncher,
                        statusBanner = StatusBanner(
                            title = "Astryx Rust Launcher is now Home",
                            detail = "Press the Home button on your TV remote. Home resolves to ${outcome.homeComponent}.",
                            isError = false
                        )
                    )
                }
            }.onFailure { err ->
                val msg = err.message ?: "Installation failed."
                appendLog("ERROR: $msg")
                _ui.update { cur ->
                    cur.copy(
                        isBusy = false,
                        activePhase = null,
                        failedPhase = currentPhase,
                        transferProgress = null,
                        transferLabel = "",
                        statusBanner = StatusBanner(
                            title = "Install stopped safely",
                            detail = msg,
                            isError = true
                        )
                    )
                }
            }
        }
    }

    fun restoreStockLauncher() {
        val state = _ui.value
        if (state.isBusy) return
        val parsed = parseHostPort(state.tvHostInput, state.tvPortInput)
        if (parsed == null) {
            _ui.update {
                it.copy(statusBanner = StatusBanner("TV IP needed", "Enter your TV's IP address first.", isError = true))
            }
            return
        }
        val (host, port) = parsed

        _ui.update { it.copy(isBusy = true, statusBanner = null) }
        appendLog("Starting stock launcher restore on $host:$port...")

        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val client = ensureConnected(host, port)
                    val preferred = _ui.value.restoreCustomPackage.ifBlank { _ui.value.savedPreviousLauncher }
                    val outcome = LauncherInstaller.restoreStockLauncher(
                        session = client,
                        preferredPackage = preferred,
                        uninstallAstryx = _ui.value.restoreAlsoUninstall,
                        log = { line -> appendLog(line) }
                    )
                    clearSavedPreviousLauncher(host)
                    val refreshed = runCatching { LauncherInstaller.probeTv(client) }.getOrNull()
                    outcome to refreshed
                }
            }

            res.onSuccess { (outcome, refreshed) ->
                val uninstallNote = if (outcome.astryxUninstalled) " Astryx Rust Launcher was uninstalled." else ""
                _ui.update { cur ->
                    cur.copy(
                        isBusy = false,
                        connectionState = ConnectionState.CONNECTED,
                        tvInfo = refreshed ?: cur.tvInfo,
                        savedPreviousLauncher = null,
                        statusBanner = StatusBanner(
                            title = "Previous launcher restored",
                            detail = "Home now resolves to ${outcome.restoredHomeComponent}.$uninstallNote",
                            isError = false
                        )
                    )
                }
            }.onFailure { err ->
                val msg = err.message ?: "Restore failed."
                appendLog("ERROR: $msg")
                _ui.update {
                    it.copy(
                        isBusy = false,
                        statusBanner = StatusBanner("Restore did not finish", msg, isError = true)
                    )
                }
            }
        }
    }

    private fun prepareApkFile(): File {
        val state = _ui.value
        return when (state.apkSource) {
            ApkSourceMode.BUNDLED -> {
                val out = File(getApplication<Application>().cacheDir, "Astryx-Rust-Launcher-${state.bundledVersion}.apk")
                if (!out.isFile || !ReleaseFetcher.hasApkMagic(out)) {
                    appendLog("Extracting bundled Astryx Rust Launcher (${state.bundledVersion})...")
                    getApplication<Application>().assets.open(BUNDLED_ASSET_NAME).use { inp ->
                        FileOutputStream(out).use { fos -> inp.copyTo(fos) }
                    }
                }
                out
            }
            ApkSourceMode.GITHUB_LATEST -> {
                val asset = state.githubRelease ?: run {
                    appendLog("Fetching latest release metadata from GitHub...")
                    ReleaseFetcher.fetchLatestReleaseInfo().also { fetched ->
                        _ui.update { it.copy(githubRelease = fetched) }
                    }
                }
                appendLog("Downloading ${asset.fileName} (${asset.tagName})...")
                val cacheDir = File(getApplication<Application>().cacheDir, "github-releases")
                ReleaseFetcher.downloadReleaseApk(asset, cacheDir) { dl, tot ->
                    val frac = if (tot > 0) (dl.toFloat() / tot.toFloat()).coerceIn(0f, 1f) else 0f
                    _ui.update {
                        it.copy(
                            transferProgress = frac,
                            transferLabel = String.format(Locale.US, "Downloading %s  %.1f / %.1f MB", asset.tagName, dl / 1_048_576.0, tot / 1_048_576.0)
                        )
                    }
                }
            }
            ApkSourceMode.LOCAL_FILE -> {
                state.pickedLocalApkFile?.takeIf { it.isFile }
                    ?: error("Pick a local .apk file first.")
            }
        }
    }

    private fun ensureConnected(host: String, port: Int): AdbClient {
        val existing = activeClient
        if (existing != null && existing.host == host && existing.port == port) {
            val alive = runCatching { existing.shell("echo ok", timeoutMs = 4_000).contains("ok") }.getOrDefault(false)
            if (alive) return existing
        }
        existing?.close()
        _ui.update { it.copy(connectionState = ConnectionState.CONNECTING) }
        val client = AdbClient(host = host, port = port, keyManager = keyManager)
        client.connect(
            onAwaitingTvApproval = {
                appendLog("Waiting for 'Allow USB debugging?' approval on the TV screen...")
                _ui.update { it.copy(connectionState = ConnectionState.AWAITING_TV_APPROVAL) }
            }
        )
        activeClient = client
        _ui.update { it.copy(connectionState = ConnectionState.CONNECTED, connectionError = null) }
        return client
    }

    private fun parseHostPort(rawHost: String, rawPort: String): Pair<String, Int>? {
        val trimmed = rawHost.trim()
        if (trimmed.isBlank() || trimmed.contains("://") || trimmed.contains('/')) return null
        return if (trimmed.count { it == ':' } == 1) {
            val h = trimmed.substringBefore(':').trim()
            val p = trimmed.substringAfter(':').trim().toIntOrNull() ?: return null
            if (h.isBlank() || p !in 1..65535) null else h to p
        } else {
            val p = rawPort.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 5555
            trimmed to p
        }
    }

    private fun savePreviousLauncher(host: String, pkg: String) {
        prefs.edit().putString("prev_home_${host.replace(Regex("[^A-Za-z0-9._-]"), "_")}", pkg).apply()
    }

    private fun loadSavedPrevious(host: String): String? =
        prefs.getString("prev_home_${host.replace(Regex("[^A-Za-z0-9._-]"), "_")}", null)

    private fun clearSavedPreviousLauncher(host: String) {
        prefs.edit().remove("prev_home_${host.replace(Regex("[^A-Za-z0-9._-]"), "_")}").apply()
    }

    private fun appendLog(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _ui.update { cur ->
            val updated = (cur.logs + "[$stamp] $line").takeLast(120)
            cur.copy(logs = updated)
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeClient?.close()
    }

    companion object {
        private const val BUNDLED_ASSET_NAME = "Astryx-Rust-Launcher-bundled.apk"
        private const val BUNDLED_VERSION_NAME = "bundled-version.txt"
    }
}
