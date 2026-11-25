package com.astryxtv.installer.core

import com.astryxtv.installer.adb.AdbSession
import java.io.File

data class TvInfo(
    val host: String,
    val port: Int,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val currentHomeComponent: String,
    val currentHomePackage: String,
    val astryxInstalled: Boolean,
    val disabledKnownLaunchers: List<String>,
    val enabledKnownLaunchers: List<String>
) {
    val displayName: String
        get() = listOf(manufacturer, model)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" ")
            .ifBlank { host }

    val isCompatibleSdk: Boolean
        get() = sdkInt <= 0 || sdkInt >= LauncherInstaller.MIN_SDK
}

data class InstallOptions(
    val disablePreviousLauncher: Boolean = true,
    val overridePreviousLauncher: String? = null,
    val grantOverlayPermission: Boolean = true,
    val grantTclAutoStart: Boolean = true,
    val grantInstallPackages: Boolean = false
)

enum class InstallPhase(val stepNumber: Int, val label: String) {
    PREFLIGHT(1, "Verify TV & current Home"),
    UPLOAD(2, "Transfer Astryx APK"),
    INSTALL(3, "Install package safely"),
    VERIFY_LAUNCH(4, "Start & test launcher"),
    CONFIGURE_HANDOVER(5, "Optimise & switch Home"),
    VERIFY_HOME(6, "Confirm default Home")
}

data class InstallOutcome(
    val homeComponent: String,
    val disabledLauncher: String?,
    val rolledBack: Boolean = false
)

data class RestoreOutcome(
    val restoredHomeComponent: String,
    val restoredPackage: String,
    val astryxUninstalled: Boolean
)

/**
 * Pure Kotlin port of CleanYourTV's safe launcher install, handover, rollback,
 * and restore workflow, tailored for Astryx Rust Launcher.
 */
object LauncherInstaller {

    const val ASTRYX_PKG = "com.astryxtv.rustlauncher"
    const val ASTRYX_ACTIVITY = "com.astryxtv.rustlauncher/.RustLauncherActivity"
    const val CLEANYOURTV_PKG = "com.gathin.tvhome"
    const val REMOTE_APK = "/data/local/tmp/astryx-rust-launcher.apk"
    const val MIN_SDK = 26

    val KNOWN_STOCK_LAUNCHERS = listOf(
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher",
        "com.mitv.tvhome"
    )

    val ALL_KNOWN_LAUNCHERS = KNOWN_STOCK_LAUNCHERS + CLEANYOURTV_PKG

    private const val HOME_RESOLVE_CMD =
        "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
    private const val HOME_CLAIMANTS_CMD =
        "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
    private val PKG_REGEX = Regex("""^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")

    fun probeTv(session: AdbSession, log: (String) -> Unit = {}): TvInfo {
        log("Probing TV hardware, Android SDK, and Home launcher...")
        val raw = session.shell(
            "echo MFR=$(getprop ro.product.manufacturer); " +
                "echo MDL=$(getprop ro.product.model); " +
                "echo REL=$(getprop ro.build.version.release); " +
                "echo SDK=$(getprop ro.build.version.sdk); " +
                "echo HOME=$($HOME_RESOLVE_CMD 2>/dev/null | tail -n 1); " +
                "echo HAS_ASTRYX=$(pm path $ASTRYX_PKG 2>/dev/null | head -n 1); " +
                "echo ==DISABLED==; pm list packages -d --user 0 2>/dev/null; " +
                "echo ==ENABLED==; pm list packages -e --user 0 2>/dev/null",
            timeoutMs = 20_000
        )

        var mfr = ""
        var mdl = ""
        var rel = ""
        var sdk = 0
        var homeComp = ""
        var hasAstryx = false
        val disabled = mutableListOf<String>()
        val enabled = mutableListOf<String>()
        var section = 0 // 0=kv, 1=disabled, 2=enabled

        for (rawLine in raw.lines()) {
            val line = rawLine.trim()
            if (line == "==DISABLED==") { section = 1; continue }
            if (line == "==ENABLED==") { section = 2; continue }
            when (section) {
                0 -> when {
                    line.startsWith("MFR=") -> mfr = line.removePrefix("MFR=").trim()
                    line.startsWith("MDL=") -> mdl = line.removePrefix("MDL=").trim()
                    line.startsWith("REL=") -> rel = line.removePrefix("REL=").trim()
                    line.startsWith("SDK=") -> sdk = line.removePrefix("SDK=").filter { it.isDigit() }.toIntOrNull() ?: 0
                    line.startsWith("HOME=") -> homeComp = line.removePrefix("HOME=").trim()
                    line.startsWith("HAS_ASTRYX=") -> hasAstryx = line.removePrefix("HAS_ASTRYX=").contains("package:")
                }
                1 -> if (line.startsWith("package:")) disabled.add(line.removePrefix("package:").trim())
                2 -> if (line.startsWith("package:")) enabled.add(line.removePrefix("package:").trim())
            }
        }

        val homePkg = homeComp.substringBefore('/').trim()
        val info = TvInfo(
            host = session.host,
            port = session.port,
            manufacturer = mfr,
            model = mdl,
            androidVersion = rel,
            sdkInt = sdk,
            currentHomeComponent = homeComp,
            currentHomePackage = homePkg,
            astryxInstalled = hasAstryx,
            disabledKnownLaunchers = ALL_KNOWN_LAUNCHERS.filter { it in disabled },
            enabledKnownLaunchers = ALL_KNOWN_LAUNCHERS.filter { it in enabled }
        )
        log("Connected: ${info.displayName} (Android ${rel.ifBlank { "?" }}, SDK $sdk)")
        log("Current Home: ${homeComp.ifBlank { "unknown" }}")
        return info
    }

    fun installLauncher(
        session: AdbSession,
        apkFile: File,
        options: InstallOptions,
        onPhase: (InstallPhase) -> Unit = {},
        onUploadProgress: (bytesSent: Long, totalBytes: Long, method: String) -> Unit = { _, _, _ -> },
        log: (String) -> Unit = {}
    ): InstallOutcome {
        // 1. Pre-flight SDK & previous launcher resolution
        onPhase(InstallPhase.PREFLIGHT)
        val sdkRaw = session.shell("getprop ro.build.version.sdk", timeoutMs = 15_000)
        val tvSdk = sdkRaw.filter { it.isDigit() }.toIntOrNull() ?: 0
        if (tvSdk in 1 until MIN_SDK) {
            throw IllegalStateException(
                "This TV runs Android SDK $tvSdk, but Astryx Rust Launcher requires SDK $MIN_SDK (Android 8.0+). Nothing was changed on the TV."
            )
        }

        val previousLauncher = resolvePreviousLauncher(session, options, log)
        log("Previous launcher action: ${previousLauncher?.let { "disable $it after handover" } ?: "leave untouched"}")

        // 2. Upload APK to TV with MD5 verification
        onPhase(InstallPhase.UPLOAD)
        val mb = String.format("%.2f", apkFile.length() / 1_048_576.0)
        log("Uploading ${apkFile.name} ($mb MB) to $REMOTE_APK...")
        session.pushFile(apkFile, REMOTE_APK, onUploadProgress)
        log("APK uploaded and MD5 verified.")

        // 3. Install APK standalone BEFORE touching previous launcher (CleanYourTV safety rule)
        onPhase(InstallPhase.INSTALL)
        log("Running pm install -r $REMOTE_APK...")
        val installOut = session.shell("pm install -r '$REMOTE_APK'; rm -f '$REMOTE_APK'", timeoutMs = 120_000)
        log(installOut.trim().ifBlank { "(no pm output)" })
        when {
            installOut.contains("Success") -> Unit
            installOut.contains("INSTALL_FAILED_OLDER_SDK") -> throw IllegalStateException(
                "pm install refused the APK (INSTALL_FAILED_OLDER_SDK): TV is older than Android 8.0 (minSdk $MIN_SDK). Previous launcher was left untouched."
            )
            installOut.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") -> throw IllegalStateException(
                "Existing Astryx install uses a different signing key (INSTALL_FAILED_UPDATE_INCOMPATIBLE). Make sure another launcher owns Home, uninstall the old Astryx first, then retry."
            )
            else -> throw IllegalStateException(
                "pm install failed (${installOut.trim().ifBlank { "unknown error" }}). Previous launcher was left untouched."
            )
        }

        // 4. Force-stop mid-update instance (#1101) & verify clean launch (#239) BEFORE disabling old Home
        onPhase(InstallPhase.VERIFY_LAUNCH)
        session.shell("am force-stop $ASTRYX_PKG", timeoutMs = 15_000)
        val startCmd = if (previousLauncher == null) {
            "am start -W -n $ASTRYX_ACTIVITY"
        } else {
            "am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n $ASTRYX_ACTIVITY"
        }
        log("Verifying Astryx launches cleanly...")
        val startOut = session.shell(startCmd, timeoutMs = 30_000)
        log(startOut.trim())
        val hasStatusOk = startOut.contains("Status: ok")
        val hasErrorLine = startOut.lines().any { it.trimStart().startsWith("Error", ignoreCase = true) }
        if (!hasStatusOk || hasErrorLine) {
            throw IllegalStateException(
                "Astryx Rust Launcher did not report a clean start. Previous launcher (${previousLauncher ?: "none"}) was left enabled."
            )
        }

        // 5. Optimise (AOT), grant Astryx app-ops, disable previous launcher, set default Home
        onPhase(InstallPhase.CONFIGURE_HANDOVER)
        log("Compiling AOT profile (speed) for instant cold starts...")
        runCatching { session.shell("cmd package compile -m speed -f $ASTRYX_PKG", timeoutMs = 90_000) }

        if (options.grantOverlayPermission) {
            log("Granting SYSTEM_ALERT_WINDOW (FPS overlay pill)...")
            runCatching { session.shell("cmd appops set $ASTRYX_PKG SYSTEM_ALERT_WINDOW allow 2>/dev/null", timeoutMs = 15_000) }
        }
        if (options.grantTclAutoStart) {
            log("Enabling TCL / vendor AUTO_START & persisting app-ops...")
            runCatching {
                session.shell(
                    "cmd appops set $ASTRYX_PKG AUTO_START allow 2>/dev/null; " +
                        "cmd appops set $ASTRYX_PKG APP_AUTO_START allow 2>/dev/null; " +
                        "appops write-settings 2>/dev/null",
                    timeoutMs = 15_000
                )
            }
        }
        if (options.grantInstallPackages) {
            log("Granting REQUEST_INSTALL_PACKAGES (in-app self-updates)...")
            runCatching { session.shell("cmd appops set $ASTRYX_PKG REQUEST_INSTALL_PACKAGES allow 2>/dev/null", timeoutMs = 15_000) }
        }

        if (previousLauncher != null) {
            log("Disabling previous launcher ($previousLauncher) for user 0...")
            runCatching { session.shell("pm disable-user --user 0 $previousLauncher", timeoutMs = 20_000) }
        }
        log("Setting default Home activity to $ASTRYX_ACTIVITY...")
        runCatching { session.shell("cmd package set-home-activity --user 0 $ASTRYX_ACTIVITY", timeoutMs = 20_000) }

        // 6. Verify Home landed on Astryx — otherwise AUTOMATICALLY ROLL BACK!
        onPhase(InstallPhase.VERIFY_HOME)
        val homeNow = queryHome(session)
        if (homeNow.startsWith("$ASTRYX_PKG/")) {
            log("Verified: Home now resolves to $homeNow")
            return InstallOutcome(
                homeComponent = homeNow,
                disabledLauncher = previousLauncher,
                rolledBack = false
            )
        }

        log("WARNING: Home resolved to '${homeNow.ifBlank { "nothing" }}' instead of Astryx. Starting automatic rollback...")
        val rollbackOk = rollbackHome(session, previousLauncher, log)
        val extraHint = if (homeNow.contains("setupwraith")) {
            " Google TV's setup wizard (setupwraith) hijacked Home — re-join Wi-Fi in TV Settings and disable com.google.android.tungsten.setupwraith first."
        } else {
            ""
        }
        val statusMsg = if (rollbackOk) {
            "$previousLauncher was re-enabled and owns Home again."
        } else {
            "Automatic rollback could not confirm Home; use Restore Stock Launcher once the TV answers."
        }
        throw IllegalStateException(
            "Home resolved to '${homeNow.ifBlank { "nothing" }}', not Astryx. $statusMsg$extraHint"
        )
    }

    fun restoreStockLauncher(
        session: AdbSession,
        preferredPackage: String? = null,
        uninstallAstryx: Boolean = false,
        log: (String) -> Unit = {}
    ): RestoreOutcome {
        val target = resolveRestoreTarget(session, preferredPackage, log)
        log("Re-enabling $target...")
        session.shell("pm enable --user 0 $target", timeoutMs = 20_000)

        val comp = session.shell("$HOME_RESOLVE_CMD $target", timeoutMs = 20_000)
            .lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
        if (!comp.startsWith("$target/")) {
            throw IllegalStateException(
                "Re-enabled $target, but could not resolve its HOME activity (got '$comp'). Astryx was not uninstalled."
            )
        }

        log("Setting Home back to $comp...")
        session.shell("cmd package set-home-activity --user 0 $comp", timeoutMs = 20_000)

        val now = queryHome(session)
        if (shortComponent(now) != shortComponent(comp)) {
            throw IllegalStateException(
                "Home still resolves to '${now.ifBlank { "nothing" }}' instead of '$comp'. Refusing to uninstall Astryx automatically."
            )
        }
        log("Verified: Home restored to $now")

        var uninstalled = false
        if (uninstallAstryx) {
            log("Uninstalling Astryx Rust Launcher ($ASTRYX_PKG)...")
            val out = session.shell("pm uninstall --user 0 $ASTRYX_PKG", timeoutMs = 30_000)
            log(out.trim())
            if (!out.contains("Success")) {
                throw IllegalStateException(
                    "Home is back on $now, but uninstalling Astryx returned: ${out.trim()}"
                )
            }
            uninstalled = true
        }

        return RestoreOutcome(
            restoredHomeComponent = now,
            restoredPackage = target,
            astryxUninstalled = uninstalled
        )
    }

    private fun resolvePreviousLauncher(
        session: AdbSession,
        options: InstallOptions,
        log: (String) -> Unit
    ): String? {
        if (!options.disablePreviousLauncher) return null

        val override = options.overridePreviousLauncher?.trim().orEmpty()
        if (override.isNotEmpty()) {
            if (override == "none") return null
            require(PKG_REGEX.matches(override)) { "Invalid launcher package name: '$override'" }
            require(override != ASTRYX_PKG) { "Cannot disable Astryx Rust Launcher itself." }
            return override
        }

        val homeComp = queryHome(session)
        val homePkg = homeComp.substringBefore('/').trim()
        return when {
            homePkg in KNOWN_STOCK_LAUNCHERS -> homePkg
            homePkg == ASTRYX_PKG -> {
                log("Home already belongs to Astryx Rust Launcher; nothing will be disabled.")
                null
            }
            homePkg == CLEANYOURTV_PKG -> {
                log("Detected CleanYourTV launcher ($CLEANYOURTV_PKG); will disable it after Astryx starts.")
                CLEANYOURTV_PKG
            }
            homePkg == "android" -> {
                val claimantsRaw = session.shell(HOME_CLAIMANTS_CMD, timeoutMs = 15_000)
                val claimants = Regex("""([A-Za-z0-9_.]+)/""").findAll(claimantsRaw).map { it.groupValues[1] }.toSet()
                val found = KNOWN_STOCK_LAUNCHERS.filter { it in claimants }
                if (found.size == 1) {
                    log("Home is Android's chooser; detected stock launcher ${found.first()}.")
                    found.first()
                } else {
                    throw IllegalStateException(
                        "Home is Android's launcher chooser (${found.size} stock launchers found). Pick a default on the TV first, or toggle off 'Replace previous launcher'."
                    )
                }
            }
            else -> throw IllegalStateException(
                "Home currently resolves to '${homePkg.ifBlank { "nothing" }}', which is not a recognised stock launcher. Toggle off 'Replace previous launcher' or specify its package name."
            )
        }
    }

    private fun resolveRestoreTarget(
        session: AdbSession,
        preferredPackage: String?,
        log: (String) -> Unit
    ): String {
        val pref = preferredPackage?.trim().orEmpty()
        if (pref.isNotEmpty()) {
            require(PKG_REGEX.matches(pref)) { "Invalid package name: '$pref'" }
            require(pref != ASTRYX_PKG) { "Cannot restore Astryx as the stock launcher." }
            return pref
        }

        val disabled = session.shell("pm list packages -d --user 0", timeoutMs = 15_000)
            .lines().mapNotNull { it.trim().removePrefix("package:").takeIf { s -> it.trim().startsWith("package:") } }
            .toSet()
        val disabledKnown = ALL_KNOWN_LAUNCHERS.filter { it in disabled }
        if (disabledKnown.size == 1) {
            log("Detected disabled launcher: ${disabledKnown.first()}")
            return disabledKnown.first()
        }
        if (disabledKnown.size > 1) {
            // Prefer the Google/Android stock launcher when CleanYourTV + stock are both disabled
            val stockOnly = KNOWN_STOCK_LAUNCHERS.filter { it in disabled }
            if (stockOnly.size == 1) {
                log("Multiple launchers disabled (${disabledKnown.joinToString()}); restoring stock launcher ${stockOnly.first()}.")
                return stockOnly.first()
            }
            throw IllegalStateException(
                "Multiple disabled launchers found (${disabledKnown.joinToString()}). Enter the exact package name to restore."
            )
        }

        val enabled = session.shell("pm list packages -e --user 0", timeoutMs = 15_000)
            .lines().mapNotNull { it.trim().removePrefix("package:").takeIf { s -> it.trim().startsWith("package:") } }
            .toSet()
        val enabledKnown = ALL_KNOWN_LAUNCHERS.filter { it in enabled }
        if (enabledKnown.size == 1) {
            return enabledKnown.first()
        }
        throw IllegalStateException(
            "Could not detect which stock launcher to restore. Enter its package name (e.g. com.google.android.apps.tv.launcherx)."
        )
    }

    private fun rollbackHome(session: AdbSession, previousLauncher: String?, log: (String) -> Unit): Boolean {
        if (previousLauncher == null) return false
        return runCatching {
            session.shell("pm enable --user 0 $previousLauncher", timeoutMs = 20_000)
            val comp = session.shell("$HOME_RESOLVE_CMD $previousLauncher", timeoutMs = 20_000)
                .lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
            if (comp.startsWith("$previousLauncher/")) {
                session.shell("cmd package set-home-activity --user 0 $comp", timeoutMs = 20_000)
            }
            val back = queryHome(session)
            val ok = back.startsWith("$previousLauncher/")
            if (ok) log("Rollback succeeded: $back owns Home again.")
            ok
        }.getOrDefault(false)
    }

    private fun queryHome(session: AdbSession): String {
        return runCatching {
            session.shell(HOME_RESOLVE_CMD, timeoutMs = 15_000)
                .lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
        }.getOrDefault("")
    }

    internal fun shortComponent(component: String): String {
        val slash = component.indexOf('/')
        if (slash <= 0) return component
        val pkg = component.substring(0, slash)
        var act = component.substring(slash + 1)
        if (act.startsWith("$pkg.")) {
            act = "." + act.removePrefix("$pkg.")
        }
        return "$pkg/$act"
    }
}
