# Astryx TV Installer

A standalone **Android phone & Android TV Wi-Fi ADB installer** plus **macOS / Linux / Windows CLI scripts** for installing [Astryx Rust Launcher](https://github.com/BurbsKlopek/astryx-rust-launcher) as your Android TV / Google TV default home screen — built on [CleanYourTV](https://github.com/Gathin23/cleanyourtv)'s safe Home handover and automatic rollback design.

## Highlights
- **Zero PC Required (Android App):** Pure-Kotlin ADB-over-TCP client with persistent RSA-2048 ADB key signing, automatic Wi-Fi `/24` LAN TV discovery on port `5555`, fast ADB `sync:` APK transfer with MD5 verification and automatic `b64push` fallback.
- **CleanYourTV Safe Handover & Rollback:**
  - Checks Android SDK (`minSdk 26`) before uploading or disabling anything.
  - Auto-detects stock launchers (`launcherx`, `tvlauncher`, `leanbacklauncher`, `mitv.tvhome`) and CleanYourTV (`com.gathin.tvhome`).
  - Installs APK, force-stops stale instances, and verifies `am start -W` succeeds **before** disabling the previous launcher.
  - AOT-compiles (`speed`), grants `SYSTEM_ALERT_WINDOW` (FPS overlay pill) and TCL `AUTO_START` / `APP_AUTO_START` + `appops write-settings`.
  - Verifies Home switched to Astryx and **automatically rolls back** to the previous launcher if handover fails.
- **One-Tap Stock Launcher Restore:** Re-enables the previous launcher, restores it as Home, and optionally uninstalls Astryx **only after** confirming Home is back.
- **10 Glass Colour Themes (Tokyo Cyber Default):** Switch instantly between **Tokyo Cyber** *(default)*, Zenith Sapphire, Hyper Violet, Arctic Frost, Solar Amber, Emerald Pulse, Crimson Dusk, Blood Moon, Mint Aurora, and Obsidian Chrome — plus mix-and-match **Accent Colour Override** swatches and Ambient Horizon Glow toggle.
- **Flexible APK Sources:** Bundled offline APK (when built locally alongside `astryx-rust-launcher`), one-tap **GitHub Latest Release** download, or local `.apk` file picker.

## Option 1: Android Phone / TV App (`Astryx TV Installer`)
1. On your TV: **Settings → System / Device Preferences → About** → click **Build** 7 times → **Developer options** → enable **USB debugging** (and Network/ADB debugging if shown).
2. Open **Astryx TV Installer** on your phone (or on the TV itself using `127.0.0.1:5555`).
3. Tap **Scan Wi-Fi** or enter your TV's IP address, tap **Connect to TV**, approve the debugging prompt on your TV, and tap **Install Astryx as Home Screen**.

### Building the Android APK from source
```bash
./gradlew testDebugUnitTest assembleRelease
```

## Option 2: Computer CLI Scripts (No `adb` binary required)

**macOS / Linux / Git Bash:**
```bash
TV_HOST=192.168.1.50 ./install.sh                  # downloads latest GitHub Release or uses local APK
TV_HOST=192.168.1.50 ./install.sh --apk path/to/Astryx-Rust-Launcher.apk
TV_HOST=192.168.1.50 ./restore-stock-launcher.sh
TV_HOST=192.168.1.50 ./restore-stock-launcher.sh --uninstall
```

**Windows (PowerShell):**
```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1 -TvHost 192.168.1.50
powershell -ExecutionPolicy Bypass -File .\restore-stock-launcher.ps1 -TvHost 192.168.1.50
```

## Attribution & License
The Python ADB transport helpers (`tools/tv.py`, `tools/b64push.py`) and safe Home handover/rollback workflow are adapted from [CleanYourTV](https://github.com/Gathin23/cleanyourtv) under the MIT License (see [tools/LICENSE-CLEANYOURTV.txt](tools/LICENSE-CLEANYOURTV.txt)).
