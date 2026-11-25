package com.astryxtv.installer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astryxtv.installer.core.InstallPhase
import com.astryxtv.installer.core.LauncherInstaller

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallerScreen(viewModel: InstallerViewModel) {
    val ui by viewModel.ui.collectAsState()
    val palette = LocalInstallerPalette.current
    val context = LocalContext.current

    var showThemeSheet by remember { mutableStateOf(false) }
    var showConsole by rememberSaveable { mutableStateOf(false) }
    var showAdvancedOverride by rememberSaveable { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }

    val apkPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importLocalApk(uri)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(palette.canvasTop, palette.canvasBottom)
                )
            )
    ) {
        // Atmospheric horizon aura behind glass cards
        if (ui.ambientGlowEnabled) {
            AmbientHorizonBackdrop(
                palette = palette,
                connectionState = ui.connectionState
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TopBrandBar(
                palette = palette,
                themeName = ui.themeId.displayName,
                onOpenThemeSheet = { showThemeSheet = true }
            )

            // Status banner for install / restore results
            AnimatedVisibility(
                visible = ui.statusBanner != null,
                enter = fadeIn(tween(220)) + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
                exit = fadeOut(tween(180)) + shrinkVertically()
            ) {
                ui.statusBanner?.let { banner ->
                    ResultBannerCard(
                        banner = banner,
                        palette = palette,
                        onDismiss = viewModel::dismissBanner
                    )
                }
            }

            // Focal Hero Stage: TV Connection & Telemetry
            HeroConnectionStage(
                ui = ui,
                palette = palette,
                onHostChange = viewModel::updateTvHost,
                onPortChange = viewModel::updateTvPort,
                onScan = viewModel::scanLan,
                onConnect = viewModel::connectAndProbe,
                onPickTv = viewModel::pickDiscoveredTv
            )

            // APK Source Selector
            ApkSourcePanel(
                ui = ui,
                palette = palette,
                onSelectSource = viewModel::selectApkSource,
                onRefreshGithub = viewModel::refreshGithubRelease,
                onPickFile = {
                    apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"))
                }
            )

            // Handover & Permissions Options
            InstallOptionsPanel(
                ui = ui,
                palette = palette,
                showAdvancedOverride = showAdvancedOverride,
                onToggleAdvancedOverride = { showAdvancedOverride = !showAdvancedOverride },
                onDisablePrevChange = viewModel::setDisablePreviousLauncher,
                onOverridePrevChange = viewModel::setOverridePreviousLauncher,
                onOverlayChange = viewModel::setGrantOverlayPermission,
                onAutoStartChange = viewModel::setGrantTclAutoStart,
                onGrantInstallChange = viewModel::setGrantInstallPackages
            )

            // Primary Install CTA + Live 6-Step Handover Sequence
            InstallActionPanel(
                ui = ui,
                palette = palette,
                onInstall = viewModel::installAstryx
            )

            // Collapsible Live ADB Console
            ConsolePanel(
                logs = ui.logs,
                expanded = showConsole,
                palette = palette,
                onToggle = { showConsole = !showConsole },
                onCopy = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Astryx ADB Log", ui.logs.joinToString("\n")))
                    Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
                },
                onClear = viewModel::clearLogs
            )

            // Stock Launcher Restore & Safety Panel
            RestorePanel(
                ui = ui,
                palette = palette,
                onCustomPkgChange = viewModel::setRestoreCustomPackage,
                onUninstallToggle = viewModel::setRestoreAlsoUninstall,
                onRestoreClick = {
                    if (ui.restoreAlsoUninstall) {
                        showRestoreConfirm = true
                    } else {
                        viewModel.restoreStockLauncher()
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    if (showThemeSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showThemeSheet = false },
            sheetState = sheetState,
            containerColor = palette.canvasBottom,
            contentColor = palette.inkPrimary,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            ThemeCustomiserSheet(
                currentTheme = ui.themeId,
                currentAccent = ui.accentOverride,
                ambientGlowEnabled = ui.ambientGlowEnabled,
                palette = palette,
                onSelectTheme = viewModel::selectTheme,
                onSelectAccent = viewModel::selectAccentOverride,
                onToggleAmbientGlow = viewModel::setAmbientGlow,
                onClose = { showThemeSheet = false }
            )
        }
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            containerColor = palette.glassElevated,
            titleContentColor = palette.inkPrimary,
            textContentColor = palette.inkSecondary,
            title = {
                Text("Restore stock Home and uninstall Astryx?", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "The previous launcher will be re-enabled and verified as Home first. Astryx Rust Launcher is only uninstalled after Home is confirmed back on the stock launcher."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreConfirm = false
                        viewModel.restoreStockLauncher()
                    }
                ) {
                    Text("Restore & Uninstall", color = palette.danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) {
                    Text("Cancel", color = palette.inkSecondary)
                }
            }
        )
    }
}

@Composable
private fun AmbientHorizonBackdrop(
    palette: InstallerPalette,
    connectionState: ConnectionState
) {
    val stateColor by animateColorAsState(
        targetValue = when (connectionState) {
            ConnectionState.CONNECTED -> palette.success
            ConnectionState.AWAITING_TV_APPROVAL -> palette.warning
            ConnectionState.ERROR -> palette.danger
            else -> palette.ambientPrimary
        },
        animationSpec = tween(450),
        label = "ambientStateColor"
    )

    val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.fillMaxSize().blur(44.dp)
    } else {
        Modifier.fillMaxSize()
    }

    Canvas(modifier = blurModifier) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(stateColor.copy(alpha = 0.28f), Color.Transparent),
                center = Offset(size.width * 0.22f, size.height * 0.14f),
                radius = size.width * 0.72f
            ),
            radius = size.width * 0.72f,
            center = Offset(size.width * 0.22f, size.height * 0.14f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(palette.ambientSecondary.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(size.width * 0.86f, size.height * 0.34f),
                radius = size.width * 0.65f
            ),
            radius = size.width * 0.65f,
            center = Offset(size.width * 0.86f, size.height * 0.34f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(palette.accentStart.copy(alpha = 0.14f), Color.Transparent),
                center = Offset(size.width * 0.50f, size.height * 0.92f),
                radius = size.width * 0.68f
            ),
            radius = size.width * 0.68f,
            center = Offset(size.width * 0.50f, size.height * 0.92f)
        )
    }
}

@Composable
private fun TopBrandBar(
    palette: InstallerPalette,
    themeName: String,
    onOpenThemeSheet: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .shadow(12.dp, RoundedCornerShape(13.dp), ambientColor = palette.accentStart, spotColor = palette.accentStart)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Brush.linearGradient(listOf(palette.accentStart, palette.accentEnd))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tv,
                    contentDescription = null,
                    tint = Color(0xFF040812),
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "Astryx TV Installer",
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.inkPrimary
                )
                Text(
                    text = "Wi-Fi ADB launcher setup",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkSecondary
                )
            }
        }

        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(palette.glassElevated)
                .border(1.dp, palette.glassBorder, CircleShape)
                .clickable(onClick = onOpenThemeSheet)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Palette,
                contentDescription = "Theme",
                tint = palette.accentStart,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = themeName.substringBefore(' '),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = palette.inkPrimary
            )
        }
    }
}

@Composable
private fun ResultBannerCard(
    banner: StatusBanner,
    palette: InstallerPalette,
    onDismiss: () -> Unit
) {
    val accent = if (banner.isError) palette.danger else palette.success
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = if (banner.isError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.padding(top = 2.dp).size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = banner.title,
                style = MaterialTheme.typography.titleMedium,
                color = palette.inkPrimary
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = banner.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.inkPrimary.copy(alpha = 0.88f)
            )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = palette.inkSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroConnectionStage(
    ui: InstallerUiState,
    palette: InstallerPalette,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onScan: () -> Unit,
    onConnect: () -> Unit,
    onPickTv: (com.astryxtv.installer.adb.DiscoveredTv) -> Unit
) {
    val beaconColor by animateColorAsState(
        targetValue = when (ui.connectionState) {
            ConnectionState.CONNECTED -> palette.success
            ConnectionState.CONNECTING, ConnectionState.AWAITING_TV_APPROVAL -> palette.warning
            ConnectionState.ERROR -> palette.danger
            ConnectionState.DISCONNECTED -> palette.accentStart
        },
        animationSpec = tween(300),
        label = "beaconColor"
    )

    val pulseTransition = rememberInfiniteTransition(label = "beaconPulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val shouldPulse = ui.connectionState == ConnectionState.CONNECTING ||
        ui.connectionState == ConnectionState.AWAITING_TV_APPROVAL ||
        ui.isScanning

    GlassPanel(
        palette = palette,
        cornerRadius = 28.dp,
        elevated = true,
        highlightTopRim = true
    ) {
        // Header status row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(22.dp)) {
                    if (shouldPulse) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .scale(pulseScale)
                                .clip(CircleShape)
                                .background(beaconColor.copy(alpha = 0.28f))
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(beaconColor)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = when {
                            ui.tvInfo != null && ui.connectionState == ConnectionState.CONNECTED -> ui.tvInfo.displayName
                            ui.connectionState == ConnectionState.AWAITING_TV_APPROVAL -> "Waiting for TV approval"
                            ui.connectionState == ConnectionState.CONNECTING -> "Connecting to TV..."
                            else -> "Connect your Android TV"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            ui.tvInfo != null && ui.connectionState == ConnectionState.CONNECTED ->
                                "Android ${ui.tvInfo.androidVersion.ifBlank { "?" }} (SDK ${ui.tvInfo.sdkInt}) at ${ui.tvInfo.host}:${ui.tvInfo.port}"
                            ui.localSubnetHint != null -> "Same Wi-Fi subnet (${ui.localSubnetHint})"
                            else -> "Turn on USB debugging in TV Developer options"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = palette.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Scan Wi-Fi pill button
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(palette.accentStart.copy(alpha = 0.14f))
                    .border(1.dp, palette.accentStart.copy(alpha = 0.35f), CircleShape)
                    .clickable(enabled = !ui.isScanning && !ui.isBusy, onClick = onScan)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (ui.isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = palette.accentStart
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.WifiFind,
                        contentDescription = null,
                        tint = palette.accentStart,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (ui.isScanning) "Scanning" else "Scan Wi-Fi",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = palette.inkPrimary
                )
            }
        }

        // Connected TV Home telemetry strip
        AnimatedVisibility(visible = ui.tvInfo != null && ui.connectionState == ConnectionState.CONNECTED) {
            ui.tvInfo?.let { info ->
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    val isAstryxHome = info.currentHomePackage == LauncherInstaller.ASTRYX_PKG
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black.copy(alpha = 0.24f))
                            .border(1.dp, palette.glassBorder, RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Current Home launcher",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.inkSecondary
                            )
                            Text(
                                text = info.currentHomePackage.ifBlank { "Unknown" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isAstryxHome) palette.success else palette.inkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (!info.isCompatibleSdk) {
                            StatusChip(text = "SDK too old", color = palette.danger)
                        } else if (isAstryxHome) {
                            StatusChip(text = "Astryx active", color = palette.success)
                        } else if (info.currentHomePackage == LauncherInstaller.CLEANYOURTV_PKG) {
                            StatusChip(text = "CleanYourTV", color = palette.accentStart)
                        } else if (info.astryxInstalled) {
                            StatusChip(text = "Astryx installed", color = palette.accentStart)
                        }
                    }
                }
            }
        }

        // Awaiting TV remote approval alert
        AnimatedVisibility(visible = ui.connectionState == ConnectionState.AWAITING_TV_APPROVAL) {
            Row(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.warning.copy(alpha = 0.14f))
                    .border(1.dp, palette.warning.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = palette.warning
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Look at your TV screen and choose Always allow from this computer, then press OK.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary
                )
            }
        }

        // Discovered LAN TV chips
        AnimatedVisibility(visible = ui.discoveredTvs.isNotEmpty()) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Text(
                    text = "Devices on your Wi-Fi with ADB open",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkSecondary
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ui.discoveredTvs.forEach { tv ->
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(palette.accentEnd.copy(alpha = 0.18f))
                                .border(1.dp, palette.accentStart.copy(alpha = 0.4f), CircleShape)
                                .clickable(enabled = !ui.isBusy) { onPickTv(tv) }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Tv,
                                contentDescription = null,
                                tint = palette.accentStart,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = tv.ip,
                                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                fontWeight = FontWeight.Bold,
                                color = palette.inkPrimary
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // IP & Port inputs
        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = palette.accentStart,
            unfocusedBorderColor = palette.glassBorder,
            focusedTextColor = palette.inkPrimary,
            unfocusedTextColor = palette.inkPrimary,
            focusedContainerColor = Color.Black.copy(alpha = 0.22f),
            unfocusedContainerColor = Color.Black.copy(alpha = 0.18f),
            focusedLabelColor = palette.accentStart,
            unfocusedLabelColor = palette.inkSecondary,
            cursorColor = palette.accentStart
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = ui.tvHostInput,
                onValueChange = onHostChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = !ui.isBusy,
                label = { Text("TV IP address") },
                placeholder = { Text("192.168.1.200", color = palette.inkSecondary.copy(alpha = 0.6f)) },
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConnect() })
            )
            OutlinedTextField(
                value = ui.tvPortInput,
                onValueChange = onPortChange,
                modifier = Modifier.width(96.dp),
                singleLine = true,
                enabled = !ui.isBusy,
                label = { Text("Port") },
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConnect() })
            )
        }

        // Connection error message
        AnimatedVisibility(visible = ui.connectionError != null) {
            ui.connectionError?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.danger,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Connect / Refresh TV button
        val connectLabel = when (ui.connectionState) {
            ConnectionState.CONNECTING -> "Connecting..."
            ConnectionState.AWAITING_TV_APPROVAL -> "Waiting for approval..."
            ConnectionState.CONNECTED -> "Refresh TV connection"
            else -> "Connect to TV"
        }
        SecondaryGlassButton(
            text = connectLabel,
            icon = if (ui.connectionState == ConnectionState.CONNECTED) Icons.Rounded.Refresh else Icons.Rounded.Tv,
            palette = palette,
            enabled = !ui.isBusy && ui.connectionState != ConnectionState.CONNECTING && ui.connectionState != ConnectionState.AWAITING_TV_APPROVAL,
            onClick = onConnect
        )
    }
}

@Composable
private fun ApkSourcePanel(
    ui: InstallerUiState,
    palette: InstallerPalette,
    onSelectSource: (ApkSourceMode) -> Unit,
    onRefreshGithub: () -> Unit,
    onPickFile: () -> Unit
) {
    GlassPanel(palette = palette, cornerRadius = 20.dp) {
        Text(
            text = "Launcher APK",
            style = MaterialTheme.typography.titleMedium,
            color = palette.inkPrimary
        )
        Spacer(Modifier.height(10.dp))

        // Segmented glass pill row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(15.dp))
                .background(Color.Black.copy(alpha = 0.25f))
                .border(1.dp, palette.glassBorder, RoundedCornerShape(15.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (ui.hasBundledApk) {
                SourcePill(
                    label = "Bundled ${ui.bundledVersion}",
                    icon = Icons.Rounded.Inventory2,
                    selected = ui.apkSource == ApkSourceMode.BUNDLED,
                    palette = palette,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectSource(ApkSourceMode.BUNDLED) }
                )
            }
            SourcePill(
                label = "GitHub",
                icon = Icons.Rounded.CloudDownload,
                selected = ui.apkSource == ApkSourceMode.GITHUB_LATEST,
                palette = palette,
                modifier = Modifier.weight(1f),
                onClick = { onSelectSource(ApkSourceMode.GITHUB_LATEST) }
            )
            SourcePill(
                label = "Pick file",
                icon = Icons.Rounded.FolderOpen,
                selected = ui.apkSource == ApkSourceMode.LOCAL_FILE,
                palette = palette,
                modifier = Modifier.weight(1f),
                onClick = {
                    onSelectSource(ApkSourceMode.LOCAL_FILE)
                    onPickFile()
                }
            )
        }

        Spacer(Modifier.height(10.dp))

        when (ui.apkSource) {
            ApkSourceMode.BUNDLED -> {
                Text(
                    text = "Ready offline: Astryx Rust Launcher ${ui.bundledVersion} (${ui.bundledSizeMb}) built into this app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.inkSecondary
                )
            }
            ApkSourceMode.GITHUB_LATEST -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        when {
                            ui.isCheckingGithub -> Text(
                                "Checking BurbsKlopek/astryx-rust-launcher...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.inkSecondary
                            )
                            ui.githubRelease != null -> {
                                Text(
                                    text = "Latest release ${ui.githubRelease.tagName}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.inkPrimary
                                )
                                Text(
                                    text = ui.githubRelease.fileName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.inkSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            ui.githubError != null -> Text(
                                text = ui.githubError,
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.danger
                            )
                            else -> Text(
                                text = "Downloads latest release APK automatically on install.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.inkSecondary
                            )
                        }
                    }
                    IconButton(onClick = onRefreshGithub, enabled = !ui.isCheckingGithub) {
                        if (ui.isCheckingGithub) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = palette.accentStart)
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh release", tint = palette.accentStart)
                        }
                    }
                }
            }
            ApkSourceMode.LOCAL_FILE -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = ui.pickedLocalApkName ?: "No APK chosen yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (ui.pickedLocalApkName != null) palette.inkPrimary else palette.inkSecondary,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onPickFile) {
                        Text("Choose APK", color = palette.accentStart, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcePill(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    palette: InstallerPalette,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bgBrush = if (selected) {
        Brush.linearGradient(listOf(palette.accentStart, palette.accentEnd))
    } else {
        Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(bgBrush)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) Color(0xFF040812) else palette.inkSecondary,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color(0xFF040812) else palette.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InstallOptionsPanel(
    ui: InstallerUiState,
    palette: InstallerPalette,
    showAdvancedOverride: Boolean,
    onToggleAdvancedOverride: () -> Unit,
    onDisablePrevChange: (Boolean) -> Unit,
    onOverridePrevChange: (String) -> Unit,
    onOverlayChange: (Boolean) -> Unit,
    onAutoStartChange: (Boolean) -> Unit,
    onGrantInstallChange: (Boolean) -> Unit
) {
    GlassPanel(palette = palette, cornerRadius = 20.dp) {
        Text(
            text = "Setup options",
            style = MaterialTheme.typography.titleMedium,
            color = palette.inkPrimary
        )
        Spacer(Modifier.height(6.dp))

        OptionToggleRow(
            title = "Replace previous launcher",
            subtitle = "Disables stock or CleanYourTV Home for user 0 after Astryx starts cleanly",
            checked = ui.disablePreviousLauncher,
            enabled = !ui.isBusy,
            palette = palette,
            onCheckedChange = onDisablePrevChange
        )

        if (ui.disablePreviousLauncher) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleAdvancedOverride)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (showAdvancedOverride) "Hide manual launcher package" else "Override detected launcher package",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.accentStart
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = if (showAdvancedOverride) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = palette.accentStart,
                    modifier = Modifier.size(16.dp)
                )
            }
            AnimatedVisibility(visible = showAdvancedOverride) {
                OutlinedTextField(
                    value = ui.overridePreviousLauncher,
                    onValueChange = onOverridePrevChange,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                    singleLine = true,
                    enabled = !ui.isBusy,
                    label = { Text("Package to disable (leave blank for auto)") },
                    placeholder = { Text("com.google.android.apps.tv.launcherx") },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accentStart,
                        unfocusedBorderColor = palette.glassBorder,
                        focusedTextColor = palette.inkPrimary,
                        unfocusedTextColor = palette.inkPrimary,
                        focusedContainerColor = Color.Black.copy(alpha = 0.2f),
                        unfocusedContainerColor = Color.Black.copy(alpha = 0.16f)
                    )
                )
            }
        }

        HorizontalDivider(color = palette.glassBorder, modifier = Modifier.padding(vertical = 4.dp))

        OptionToggleRow(
            title = "Grant FPS overlay permission",
            subtitle = "Allows Astryx's live FPS pill without digging into TV settings",
            checked = ui.grantOverlayPermission,
            enabled = !ui.isBusy,
            palette = palette,
            onCheckedChange = onOverlayChange
        )

        HorizontalDivider(color = palette.glassBorder, modifier = Modifier.padding(vertical = 4.dp))

        OptionToggleRow(
            title = "Enable TCL / vendor Auto-Start",
            subtitle = "Sets AUTO_START & persists app-ops so cold boot opens Astryx",
            checked = ui.grantTclAutoStart,
            enabled = !ui.isBusy,
            palette = palette,
            onCheckedChange = onAutoStartChange
        )

        HorizontalDivider(color = palette.glassBorder, modifier = Modifier.padding(vertical = 4.dp))

        OptionToggleRow(
            title = "Allow in-app APK self-updates",
            subtitle = "Grants REQUEST_INSTALL_PACKAGES for one-tap updates on the TV",
            checked = ui.grantInstallPackages,
            enabled = !ui.isBusy,
            palette = palette,
            onCheckedChange = onGrantInstallChange
        )
    }
}

@Composable
private fun OptionToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    palette: InstallerPalette,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = palette.inkPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = palette.inkSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF040812),
                checkedTrackColor = palette.accentStart,
                uncheckedThumbColor = palette.inkSecondary,
                uncheckedTrackColor = Color.Black.copy(alpha = 0.35f),
                uncheckedBorderColor = palette.glassBorder
            )
        )
    }
}

@Composable
private fun InstallActionPanel(
    ui: InstallerUiState,
    palette: InstallerPalette,
    onInstall: () -> Unit
) {
    val isUpdate = ui.tvInfo?.currentHomePackage == LauncherInstaller.ASTRYX_PKG
    val ctaText = when {
        ui.isBusy && ui.activePhase != null -> ui.activePhase.label + "..."
        ui.isBusy -> "Working..."
        isUpdate -> "Update Astryx Launcher"
        else -> "Install Astryx as Home Screen"
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "ctaScale"
    )

    val canRun = !ui.isBusy && ui.tvHostInput.isNotBlank() && (ui.tvInfo == null || ui.tvInfo.isCompatibleSdk)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .scale(scale)
                .shadow(
                    elevation = if (canRun) 18.dp else 4.dp,
                    shape = RoundedCornerShape(22.dp),
                    ambientColor = palette.accentStart,
                    spotColor = palette.accentEnd
                )
                .clip(RoundedCornerShape(22.dp))
                .background(
                    if (canRun) {
                        Brush.linearGradient(listOf(palette.accentStart, palette.accentEnd))
                    } else {
                        Brush.linearGradient(listOf(palette.glassElevated, palette.glassSurface))
                    }
                )
                .clickable(
                    enabled = canRun,
                    interactionSource = interaction,
                    indication = null,
                    onClick = onInstall
                )
                .padding(vertical = 18.dp, horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (ui.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.4.dp,
                        color = if (canRun) Color(0xFF040812) else palette.accentStart
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.RocketLaunch,
                        contentDescription = null,
                        tint = if (canRun) Color(0xFF040812) else palette.inkSecondary,
                        modifier = Modifier.size(21.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = ctaText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (canRun) Color(0xFF040812) else palette.inkSecondary
                )
            }
        }

        // Transfer progress + 6-step handover sequence (shown once install begins)
        val showSequence = ui.activePhase != null || ui.completedPhases.isNotEmpty() || ui.failedPhase != null
        AnimatedVisibility(
            visible = showSequence,
            enter = fadeIn(tween(200)) + expandVertically(),
            exit = fadeOut(tween(150)) + shrinkVertically()
        ) {
            GlassPanel(palette = palette, cornerRadius = 20.dp) {
                if (ui.transferProgress != null) {
                    val animatedProgress by animateFloatAsState(
                        targetValue = ui.transferProgress,
                        animationSpec = tween(180),
                        label = "uploadProgress"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = ui.transferLabel.ifBlank { "Transferring..." },
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.Bold,
                            color = palette.inkPrimary
                        )
                        Text(
                            text = "${(animatedProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.Bold,
                            color = palette.accentStart
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.35f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(animatedProgress.coerceIn(0.02f, 1f))
                                .height(8.dp)
                                .clip(CircleShape)
                                .background(Brush.horizontalGradient(listOf(palette.accentStart, palette.accentEnd)))
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }

                InstallPhase.entries.forEach { phase ->
                    val isDone = phase in ui.completedPhases
                    val isActive = ui.activePhase == phase
                    val isFailed = ui.failedPhase == phase

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isFailed -> palette.danger.copy(alpha = 0.22f)
                                        isDone -> palette.success.copy(alpha = 0.22f)
                                        isActive -> palette.accentStart.copy(alpha = 0.22f)
                                        else -> Color.White.copy(alpha = 0.06f)
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                isFailed -> Icon(Icons.Rounded.Close, null, tint = palette.danger, modifier = Modifier.size(14.dp))
                                isDone -> Icon(Icons.Rounded.Check, null, tint = palette.success, modifier = Modifier.size(14.dp))
                                isActive -> CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = palette.accentStart)
                                else -> Text(
                                    text = phase.stepNumber.toString(),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                    color = palette.inkSecondary
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = phase.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isActive || isDone || isFailed) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                isFailed -> palette.danger
                                isDone -> palette.inkPrimary
                                isActive -> palette.accentStart
                                else -> palette.inkSecondary
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsolePanel(
    logs: List<String>,
    expanded: Boolean,
    palette: InstallerPalette,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit
) {
    GlassPanel(palette = palette, cornerRadius = 18.dp, contentPadding = PaddingValues(14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Terminal, contentDescription = null, tint = palette.accentStart, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "ADB log (${logs.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.inkPrimary
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (logs.isNotEmpty()) {
                    IconButton(onClick = onCopy, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy log", tint = palette.inkSecondary, modifier = Modifier.size(16.dp))
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = palette.inkSecondary
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 10.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.42f))
                        .border(1.dp, palette.glassBorder, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = if (logs.isEmpty()) "No ADB commands sent yet." else logs.takeLast(40).joinToString("\n"),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp
                        ),
                        color = palette.inkPrimary.copy(alpha = 0.88f)
                    )
                }
                if (logs.isNotEmpty()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onClear) {
                            Text("Clear log", color = palette.inkSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RestorePanel(
    ui: InstallerUiState,
    palette: InstallerPalette,
    onCustomPkgChange: (String) -> Unit,
    onUninstallToggle: (Boolean) -> Unit,
    onRestoreClick: () -> Unit
) {
    GlassPanel(palette = palette, cornerRadius = 20.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Restore previous Home",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.inkPrimary
                )
                Text(
                    text = ui.savedPreviousLauncher?.let { "Saved from last install: $it" }
                        ?: "Re-enables your stock Android TV / Google TV launcher and sets it back as Home",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkSecondary
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = ui.restoreCustomPackage,
            onValueChange = onCustomPkgChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !ui.isBusy,
            label = { Text("Launcher package (optional — auto-detects)") },
            placeholder = { Text(ui.savedPreviousLauncher ?: "com.google.android.apps.tv.launcherx") },
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = palette.accentStart,
                unfocusedBorderColor = palette.glassBorder,
                focusedTextColor = palette.inkPrimary,
                unfocusedTextColor = palette.inkPrimary,
                focusedContainerColor = Color.Black.copy(alpha = 0.2f),
                unfocusedContainerColor = Color.Black.copy(alpha = 0.16f)
            )
        )

        Spacer(Modifier.height(6.dp))

        OptionToggleRow(
            title = "Also uninstall Astryx after restore",
            subtitle = "Only runs after the stock launcher is confirmed active as Home",
            checked = ui.restoreAlsoUninstall,
            enabled = !ui.isBusy,
            palette = palette,
            onCheckedChange = onUninstallToggle
        )

        Spacer(Modifier.height(8.dp))

        SecondaryGlassButton(
            text = if (ui.restoreAlsoUninstall) "Restore Stock Home & Uninstall" else "Restore Stock Launcher",
            icon = Icons.Rounded.Restore,
            palette = palette,
            accentTint = if (ui.restoreAlsoUninstall) palette.danger else palette.inkPrimary,
            enabled = !ui.isBusy && ui.tvHostInput.isNotBlank(),
            onClick = onRestoreClick
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeCustomiserSheet(
    currentTheme: InstallerThemeId,
    currentAccent: AccentOverrideId,
    ambientGlowEnabled: Boolean,
    palette: InstallerPalette,
    onSelectTheme: (InstallerThemeId) -> Unit,
    onSelectAccent: (AccentOverrideId) -> Unit,
    onToggleAmbientGlow: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Theme customiser",
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.inkPrimary
                )
                Text(
                    text = "10 glass palettes with mix-and-match glow accents",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkSecondary
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = palette.inkSecondary)
            }
        }

        // 2-column grid of all 10 themes
        val rows = InstallerThemeId.entries.chunked(2)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    pair.forEach { theme ->
                        val selected = theme == currentTheme
                        val p = theme.palette
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(18.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(p.canvasTop, p.glassElevated)
                                    )
                                )
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) p.accentStart else p.glassBorder,
                                    shape = RoundedCornerShape(18.dp)
                                )
                                .clickable { onSelectTheme(theme) }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .shadow(8.dp, CircleShape, ambientColor = p.accentStart, spotColor = p.accentEnd)
                                        .clip(CircleShape)
                                        .background(Brush.linearGradient(listOf(p.accentStart, p.accentEnd))),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selected) {
                                        Icon(
                                            Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = Color(0xFF040812),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                // Miniature 2-colour gradient bar preview
                                Box(
                                    modifier = Modifier
                                        .width(34.dp)
                                        .height(6.dp)
                                        .clip(CircleShape)
                                        .background(Brush.horizontalGradient(listOf(p.accentStart, p.accentEnd)))
                                )
                            }
                            Spacer(Modifier.height(9.dp))
                            Text(
                                text = theme.displayName,
                                style = MaterialTheme.typography.labelLarge,
                                color = p.inkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = theme.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = p.inkSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        HorizontalDivider(color = palette.glassBorder, modifier = Modifier.padding(vertical = 2.dp))

        // Mix-and-match accent colour override swatches
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Accent colour override",
                style = MaterialTheme.typography.titleMedium,
                color = palette.inkPrimary
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AccentOverrideId.entries.forEach { acc ->
                    val selected = acc == currentAccent
                    val swStart = acc.start ?: currentTheme.palette.accentStart
                    val swEnd = acc.end ?: currentTheme.palette.accentEnd
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (selected) swStart.copy(alpha = 0.22f) else palette.glassSurface)
                            .border(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) swStart else palette.glassBorder,
                                shape = CircleShape
                            )
                            .clickable { onSelectAccent(acc) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(swStart, swEnd)))
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = acc.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = palette.inkPrimary
                        )
                    }
                }
            }
        }

        OptionToggleRow(
            title = "Ambient horizon glow",
            subtitle = "Soft luminous backdrop blooms behind frosted glass panels",
            checked = ambientGlowEnabled,
            enabled = true,
            palette = palette,
            onCheckedChange = onToggleAmbientGlow
        )
    }
}

@Composable
private fun GlassPanel(
    palette: InstallerPalette,
    cornerRadius: Dp = 22.dp,
    elevated: Boolean = false,
    highlightTopRim: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val borderBrush = if (highlightTopRim) {
        Brush.verticalGradient(
            colors = listOf(
                palette.accentStart.copy(alpha = 0.45f),
                palette.glassBorder,
                palette.glassBorder.copy(alpha = 0.10f)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                palette.glassBorder,
                palette.glassBorder.copy(alpha = 0.10f)
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (elevated) 20.dp else 10.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.55f),
                spotColor = Color.Black.copy(alpha = 0.65f)
            )
            .clip(shape)
            .background(if (elevated) palette.glassElevated else palette.glassSurface)
            .border(1.dp, borderBrush, shape)
            .padding(contentPadding)
    ) {
        content()
    }
}

@Composable
private fun SecondaryGlassButton(
    text: String,
    icon: ImageVector,
    palette: InstallerPalette,
    accentTint: Color = palette.inkPrimary,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = if (enabled) 0.08f else 0.03f))
            .border(1.dp, palette.glassBorder, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) accentTint else palette.inkSecondary.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) accentTint else palette.inkSecondary.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.45f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}
