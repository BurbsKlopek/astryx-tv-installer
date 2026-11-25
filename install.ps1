# Windows PowerShell version of install.sh: install Astryx Rust Launcher on an Android TV /
# Google TV over Wi-Fi ADB and make it the default home screen, with automatic stock-launcher
# detection and rollback on failure.
#
# Adapted from CleanYourTV's launcher/build-and-install.ps1 (MIT License, Gathin):
# https://github.com/Gathin23/cleanyourtv
#
# Usage:
#   .\install.ps1 -TvHost 192.168.1.50
#   .\install.ps1 -TvHost 192.168.1.50 -Build
#   .\install.ps1 -TvHost 192.168.1.50 -Apk C:\path\to\Astryx-Rust-Launcher-v1.2.0.apk
#   .\install.ps1 -TvHost 192.168.1.50 -Download [-Version v1.2.0]
#   .\install.ps1 -TvHost 192.168.1.50 -StockLauncher <package>|none
#   .\install.ps1 -TvHost 192.168.1.50 [-NoOverlayGrant] [-NoAutoStart] [-GrantInstall]
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$Apk = $env:APK,
    [switch]$Build,
    [switch]$Download,
    [string]$Version = '',
    [string]$StockLauncher = $env:STOCK_LAUNCHER,
    [switch]$NoOverlayGrant,
    [switch]$NoAutoStart,
    [switch]$GrantInstall,
    [switch]$Help
)

$ErrorActionPreference = 'Stop'
# Pipe plain UTF-8 to native commands; PowerShell 5.1 defaults to UTF-8 with BOM or OEM codepage.
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
try { [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12 } catch {}

if ($Help) {
    Write-Host @'
Usage: .\install.ps1 -TvHost <tv-ip> [options]

Install Astryx Rust Launcher on your Android TV / Google TV and set it as Home.
Uses CleanYourTV's pure-Python ADB transport and safe Home handover + rollback.

Parameters:
  -TvHost <ip[:port]>          TV IP address (or set $env:TV_HOST)
  -Apk <path>                  Install a specific local APK file
  -Build                       Compile Rust (armv7 + arm64) + Gradle release APK from source
  -Download                    Download the latest GitHub Release APK even if a local APK exists
  -Version <tag>               Download a specific GitHub Release tag (e.g. v1.2.0)
  -StockLauncher <pkg|none>    Override detected previous launcher, or 'none' to leave it enabled
  -NoOverlayGrant              Skip granting SYSTEM_ALERT_WINDOW (live FPS overlay pill)
  -NoAutoStart                 Skip TCL/vendor AUTO_START & APP_AUTO_START app-op grants
  -GrantInstall                Also grant REQUEST_INSTALL_PACKAGES (for in-app self-updates)
  -Help                        Show this help and exit

Environment variables (CleanYourTV-compatible):
  TV_HOST, TV_PORT, TV_ADB_BINARY, STOCK_LAUNCHER, APK, BUILD=1, SKIP_BUILD=1, CLEANYOURTV_TOOLS
'@
    exit 0
}

$pkg = 'com.astryxtv.rustlauncher'
$activity = 'com.astryxtv.rustlauncher/.RustLauncherActivity'
$remoteApk = '/data/local/tmp/astryx-rust-launcher.apk'
$githubRepo = 'BurbsKlopek/astryx-rust-launcher'
$knownStock = @('com.google.android.tvlauncher', 'com.google.android.apps.tv.launcherx', 'com.google.android.leanbacklauncher', 'com.mitv.tvhome')
$cleanYourTvLauncher = 'com.gathin.tvhome'

if (-not $TvHost) { throw 'Pass -TvHost <tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost

$doBuild = $Build.IsPresent -or ($env:BUILD -eq '1')
if ($env:SKIP_BUILD -eq '1') { $doBuild = $false }
$doDownload = $Download.IsPresent -or ([bool]$Version)

# Resolve caller-supplied APK path before switching to repo root.
if ($Apk) {
    if (-not [System.IO.Path]::IsPathRooted($Apk)) {
        $Apk = Join-Path (Get-Location).Path $Apk
    }
    $Apk = [System.IO.Path]::GetFullPath($Apk)
}

$repoRoot = $PSScriptRoot
Set-Location $repoRoot

function Find-VenvPython([string]$ToolsDir) {
    foreach ($rel in @('venv\Scripts\python.exe', 'venv/bin/python')) {
        $cand = Join-Path $ToolsDir $rel
        if (Test-Path $cand) { return $cand }
    }
    return $null
}

# Locate CleanYourTV's tv.py / b64push.py and Python venv:
#   1. Explicit CLEANYOURTV_TOOLS env var
#   2. Local tools\venv inside this repo
#   3. Sibling ..\cleanyourtv\tools (reuses existing venv + already-authorized ADB key)
#   4. Local bundled tools\ (auto-bootstraps venv on first run)
$toolsDir = $null
$py = $null

if ($env:CLEANYOURTV_TOOLS) {
    $toolsDir = [System.IO.Path]::GetFullPath($env:CLEANYOURTV_TOOLS)
    if (-not (Test-Path (Join-Path $toolsDir 'tv.py')) -or -not (Test-Path (Join-Path $toolsDir 'b64push.py'))) {
        throw "CLEANYOURTV_TOOLS=$toolsDir does not contain tv.py and b64push.py."
    }
    $py = Find-VenvPython $toolsDir
} else {
    $localTools = Join-Path $repoRoot 'tools'
    $siblingTools = [System.IO.Path]::GetFullPath((Join-Path $repoRoot '..\cleanyourtv\tools'))
    $py = Find-VenvPython $localTools
    if ($py) {
        $toolsDir = $localTools
    } elseif ((Test-Path (Join-Path $siblingTools 'tv.py')) -and (Test-Path (Join-Path $siblingTools 'b64push.py')) -and ($sibPy = Find-VenvPython $siblingTools)) {
        $toolsDir = $siblingTools
        $py = $sibPy
        Write-Host "Reusing CleanYourTV tools (venv + ADB key): $toolsDir"
    } else {
        $toolsDir = $localTools
    }
}

if (-not $py) {
    $req = Join-Path $toolsDir 'requirements.txt'
    if (Test-Path $req) {
        $bootstrap = $null
        if (Get-Command py -ErrorAction SilentlyContinue) { $bootstrap = @('py', '-3') }
        elseif (Get-Command python -ErrorAction SilentlyContinue) { $bootstrap = @('python') }
        elseif (Get-Command python3 -ErrorAction SilentlyContinue) { $bootstrap = @('python3') }
        if ($bootstrap) {
            Write-Host "Setting up Python virtual environment in $toolsDir\venv ..."
            $venvPath = Join-Path $toolsDir 'venv'
            if ($bootstrap.Count -eq 2) { & $bootstrap[0] $bootstrap[1] -m venv $venvPath }
            else { & $bootstrap[0] -m venv $venvPath }
            if ($LASTEXITCODE -eq 0) {
                $py = Find-VenvPython $toolsDir
                if ($py) {
                    & $py -m pip install -q -r $req
                    if ($LASTEXITCODE -ne 0) { $py = $null }
                }
            }
        }
    }
}

if (-not $py -or -not (Test-Path $py)) {
    throw 'Python ADB environment not ready. Run: py -3 -m venv tools\venv; tools\venv\Scripts\python.exe -m pip install -r tools\requirements.txt'
}

$tvPy = Join-Path $toolsDir 'tv.py'
$b64PushPy = Join-Path $toolsDir 'b64push.py'

# Resolve optional sibling Astryx Rust Launcher source repo (for -Build or local build pickup).
$launcherRepo = @($repoRoot, (Join-Path $repoRoot '..\astryx-tv-launcher'), (Join-Path $repoRoot '..\astryx-rust-launcher')) |
    ForEach-Object { [System.IO.Path]::GetFullPath($_) } |
    Where-Object { (Test-Path (Join-Path $_ 'rust')) -and (Test-Path (Join-Path $_ 'android-rust')) } |
    Select-Object -First 1

# Pre-flight SDK check (CleanYourTV #576).
$minSdk = if ($launcherRepo) {
    Select-String -Path (Join-Path $launcherRepo 'android-rust\app\build.gradle.kts') -Pattern '^\s*minSdk\s*=\s*(\d{1,3})\s*$' -ErrorAction SilentlyContinue |
        Select-Object -First 1 | ForEach-Object { [int]$_.Matches[0].Groups[1].Value }
} else { $null }
if (-not $minSdk) { $minSdk = 26 }

$ErrorActionPreference = 'Continue'
$tvSdk = "$(@(& $py -I $tvPy 'getprop ro.build.version.sdk' 2>$null) -join '')" -replace '[^0-9]', ''
$ErrorActionPreference = 'Stop'
if ($minSdk -and $tvSdk -and [int]$tvSdk -lt $minSdk) {
    throw "This TV runs Android SDK $tvSdk and Astryx Rust Launcher requires minSdk $minSdk (Android 8.0+), so pm install would answer INSTALL_FAILED_OLDER_SDK. Nothing was built, pushed, or disabled."
}

# ---------------------------------------------------------------------------
# APK acquisition: -Build | -Apk | -Download/-Version | smart auto-detect
# ---------------------------------------------------------------------------
function Build-FromSource {
    if (-not $launcherRepo) {
        throw "Building from source requires a local clone of $githubRepo beside this folder (..\astryx-rust-launcher or ..\astryx-tv-launcher). Run without -Build to download the latest GitHub Release automatically."
    }
    Write-Host "Building Astryx Rust Launcher from source ($launcherRepo)..."

    if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
        $cargoBin = Join-Path $env:USERPROFILE '.cargo\bin'
        if (Test-Path (Join-Path $cargoBin 'cargo.exe')) {
            $env:PATH = "$cargoBin;$env:PATH"
        } else {
            throw 'cargo not found. Install Rust (https://rustup.rs) or run without -Build to install a prebuilt APK.'
        }
    }

    if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        $jbr = Get-ChildItem 'C:\Program Files\Android' -Directory -ErrorAction SilentlyContinue |
            ForEach-Object { Join-Path $_.FullName 'jbr' } | Where-Object { Test-Path "$_\bin\java.exe" } | Select-Object -First 1
        if (-not $jbr) { throw 'Set JAVA_HOME to a JDK 17+ (Android Studio ships one in its jbr folder).' }
        $env:JAVA_HOME = $jbr
    }
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
    if (-not (Test-Path $sdk)) { throw "Android SDK not found at '$sdk'. Set ANDROID_HOME." }
    $env:ANDROID_HOME = $sdk

    $ndkDir = $env:ANDROID_NDK_HOME
    if (-not $ndkDir -and (Test-Path (Join-Path $sdk 'ndk'))) {
        $ndkDir = Get-ChildItem (Join-Path $sdk 'ndk') -Directory |
            Sort-Object { try { [version]($_.Name -replace '-.*$', '') } catch { [version]'0.0' } } |
            Select-Object -Last 1 -ExpandProperty FullName
    }
    if (-not $ndkDir -or -not (Test-Path (Join-Path $ndkDir 'toolchains\llvm\prebuilt'))) {
        throw "Android NDK not found under '$sdk\ndk'. Install NDK or set ANDROID_NDK_HOME."
    }
    $ndkBin = Get-ChildItem (Join-Path $ndkDir 'toolchains\llvm\prebuilt') -Directory |
        ForEach-Object { Join-Path $_.FullName 'bin' } | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $ndkBin) { throw "Could not find NDK LLVM bin directory in $ndkDir." }
    $env:PATH = "$ndkBin;$env:PATH"

    $clangExt = if (Test-Path (Join-Path $ndkBin 'aarch64-linux-android26-clang.cmd')) { '.cmd' } else { '' }
    $armv7Clang = Join-Path $ndkBin ("armv7a-linux-androideabi26-clang$clangExt")
    $arm64Clang = Join-Path $ndkBin ("aarch64-linux-android26-clang$clangExt")
    $llvmAr = Join-Path $ndkBin 'llvm-ar.exe'
    if (-not (Test-Path $llvmAr)) { $llvmAr = 'llvm-ar' }

    Push-Location (Join-Path $launcherRepo 'rust')
    try {
        $env:CC_armv7_linux_androideabi = $armv7Clang
        $env:AR_armv7_linux_androideabi = $llvmAr
        $env:CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER = $armv7Clang
        & cargo build --release --target armv7-linux-androideabi
        if ($LASTEXITCODE -ne 0) { throw 'Rust armv7-linux-androideabi build failed' }

        $env:CC_aarch64_linux_android = $arm64Clang
        $env:AR_aarch64_linux_android = $llvmAr
        $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $arm64Clang
        & cargo build --release --target aarch64-linux-android
        if ($LASTEXITCODE -ne 0) { throw 'Rust aarch64-linux-android build failed' }

        $jniV7 = Join-Path $launcherRepo 'android-rust\app\src\main\jniLibs\armeabi-v7a'
        $jniV8 = Join-Path $launcherRepo 'android-rust\app\src\main\jniLibs\arm64-v8a'
        New-Item -ItemType Directory -Force -Path $jniV7, $jniV8 | Out-Null
        Copy-Item -Force 'target\armv7-linux-androideabi\release\libastryx_tv_rust.so' $jniV7
        Copy-Item -Force 'target\aarch64-linux-android\release\libastryx_tv_rust.so' $jniV8
    } finally {
        Pop-Location
    }

    $localProps = Join-Path $launcherRepo 'android-rust\local.properties'
    if (-not (Test-Path $localProps)) {
        "sdk.dir=$($sdk -replace '\\', '/')" | Out-File -Encoding ascii $localProps
    }

    Push-Location (Join-Path $launcherRepo 'android-rust')
    try {
        & .\gradlew.bat assembleRelease -q --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Gradle assembleRelease failed' }
    } finally {
        Pop-Location
    }

    $built = Join-Path $launcherRepo 'android-rust\app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path $built)) { throw "Build succeeded but APK not found at $built" }
    return $built
}

function Get-ReleaseApk([string]$Tag) {
    $cacheDir = Join-Path $repoRoot 'tools\.cache'
    New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null

    Write-Host "Fetching Astryx Rust Launcher release info from GitHub ($githubRepo)..."
    $headers = @{ 'User-Agent' = 'astryx-rust-launcher-installer' }
    $urls = if ($Tag) {
        if ($Tag.StartsWith('v')) { @("https://api.github.com/repos/$githubRepo/releases/tags/$Tag") }
        else { @("https://api.github.com/repos/$githubRepo/releases/tags/v$Tag", "https://api.github.com/repos/$githubRepo/releases/tags/$Tag") }
    } else {
        @("https://api.github.com/repos/$githubRepo/releases/latest")
    }

    $release = $null
    $lastErr = $null
    foreach ($u in $urls) {
        try {
            $release = Invoke-RestMethod -Uri $u -Headers $headers -TimeoutSec 30
            break
        } catch {
            $lastErr = $_
        }
    }
    if (-not $release) { throw "Could not fetch GitHub release ($lastErr). Pass -Apk <path> or -Build instead." }

    $asset = @($release.assets | Where-Object { $_.name -like '*.apk' }) | Select-Object -First 1
    if (-not $asset) { throw "Release $($release.tag_name) has no .apk asset." }

    $name = [System.IO.Path]::GetFileName($asset.name)
    $dest = Join-Path $cacheDir $name
    $part = "$dest.part"

    if ((Test-Path $dest) -and ((Get-Item $dest).Length -gt 100000)) {
        Write-Host "Using cached release APK: $dest"
        return $dest
    }

    Write-Host "Downloading $name ($($release.tag_name))..."
    try {
        Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $part -Headers $headers -UseBasicParsing -TimeoutSec 180
    } catch {
        Remove-Item -Force -ErrorAction SilentlyContinue $part
        throw "Download failed: $_"
    }

    $bytes = [System.IO.File]::ReadAllBytes($part)
    if ($bytes.Length -lt 2 -or $bytes[0] -ne 0x50 -or $bytes[1] -ne 0x4B) {
        Remove-Item -Force -ErrorAction SilentlyContinue $part
        throw 'Downloaded file is not a valid APK (missing ZIP/PK header).'
    }
    Move-Item -Force $part $dest
    return $dest
}

$apkPath = $null
if ($doBuild) {
    $apkPath = Build-FromSource
} elseif ($Apk) {
    if (-not (Test-Path $Apk)) { throw "Cannot read APK file: $Apk" }
    $apkPath = $Apk
} elseif ($doDownload) {
    $apkPath = Get-ReleaseApk $Version
} else {
    $builtLocal = if ($launcherRepo) { Join-Path $launcherRepo 'android-rust\app\build\outputs\apk\release\app-release.apk' } else { '' }
    if ($builtLocal -and (Test-Path $builtLocal)) {
        $apkPath = $builtLocal
    } else {
        $dlFolder = Join-Path $env:USERPROFILE 'Downloads\Android Development'
        $searchRoots = @($repoRoot)
        if (Test-Path $dlFolder) { $searchRoots += $dlFolder }
        $cand = $searchRoots | ForEach-Object { Get-ChildItem -Path $_ -Filter 'Astryx-Rust-Launcher-*.apk' -File -ErrorAction SilentlyContinue } |
            Sort-Object LastWriteTime | Select-Object -Last 1 -ExpandProperty FullName
        if ($cand) { $apkPath = $cand }
        else { $apkPath = Get-ReleaseApk '' }
    }
}

$apkSizeMb = [math]::Round((Get-Item $apkPath).Length / 1MB, 2)
Write-Host "APK: $apkPath ($apkSizeMb MB)"

# ---------------------------------------------------------------------------
# Stock / previous launcher detection (CleanYourTV safety logic)
# ---------------------------------------------------------------------------
$homeQuery = 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
if (-not $StockLauncher) {
    $homePkg = & $py -I $tvPy $homeQuery | Select-Object -Last 1
    if ($LASTEXITCODE -ne 0 -or -not $homePkg) {
        throw 'Could not ask the TV which launcher owns Home; check -TvHost and the debugging prompt, or pass -StockLauncher <package>'
    }
    $homePkg = "$homePkg".Trim().Split('/')[0]
    if ($knownStock -contains $homePkg) {
        $StockLauncher = $homePkg
    } elseif ($homePkg -eq $pkg) {
        $StockLauncher = 'none'
        Write-Host 'Home already belongs to Astryx Rust Launcher; nothing will be disabled.'
    } elseif ($homePkg -eq $cleanYourTvLauncher) {
        $StockLauncher = $cleanYourTvLauncher
        Write-Host "Home currently belongs to CleanYourTV launcher ($cleanYourTvLauncher); it will be disabled once Astryx starts (pass -StockLauncher none to keep both enabled)."
    } elseif ($homePkg -eq 'android') {
        $claimants = (& $py -I $tvPy 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME') -join "`n"
        if ($LASTEXITCODE -ne 0) { throw 'Could not query Home claimants; stock launcher was left untouched.' }
        $found = @($knownStock | Where-Object { $claimants -match ([regex]::Escape($_) + '/') })
        if ($found.Count -eq 1) {
            $StockLauncher = $found[0]
            Write-Host "Home is Android's chooser; the stock launcher among the candidates is $StockLauncher."
        } else {
            throw "Home is Android's launcher chooser and $($found.Count) known stock launchers claim Home: pick a default on the TV first, or run again with -StockLauncher <package to disable> or -StockLauncher none."
        }
    } else {
        throw "Home currently resolves to '$homePkg', which is not a stock launcher this script knows (android = chooser, com.google.android.tungsten.setupwraith = setup wizard, anything else = another launcher). Run again with -StockLauncher <package to disable> or -StockLauncher none."
    }
}

if ($StockLauncher -ne 'none') {
    if ($StockLauncher -notmatch '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$') {
        throw "Invalid -StockLauncher package name: '$StockLauncher' (expected a package name or 'none')."
    }
    if ($StockLauncher -eq $pkg) {
        throw "Refusing to disable Astryx Rust Launcher itself ($pkg). Use -StockLauncher none instead."
    }
}

Write-Host "Previous launcher to disable: $StockLauncher"
$disableStock = if ($StockLauncher -eq 'none') { 'echo previous launcher left alone' } else { "pm disable-user --user 0 $StockLauncher" }

# ---------------------------------------------------------------------------
# Upload & install APK (BEFORE touching the previous launcher)
# ---------------------------------------------------------------------------
Write-Host "Uploading APK to TV ($remoteApk)..."
if ($env:TV_ADB_BINARY) {
    $ErrorActionPreference = 'Continue'
    & $py -I $tvPy --push $apkPath $remoteApk
    $pushExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($pushExit -ne 0) {
        & $py -I $b64PushPy $apkPath $remoteApk
        if ($LASTEXITCODE -ne 0) { throw 'APK upload failed' }
    }
} else {
    & $py -I $b64PushPy $apkPath $remoteApk
    if ($LASTEXITCODE -ne 0) { throw 'APK upload failed' }
}

$out = (& $py -I $tvPy "pm install -r $remoteApk; rm -f $remoteApk") -join "`n"
Write-Host $out
if ("$out" -match 'INSTALL_FAILED_OLDER_SDK') {
    throw "pm install refused the APK as too new for this TV (INSTALL_FAILED_OLDER_SDK): the TV runs an older SDK than minSdk $minSdk. The previous launcher ($StockLauncher) was left untouched."
}
if ("$out" -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
    throw "pm install failed with INSTALL_FAILED_UPDATE_INCOMPATIBLE (existing install uses a different signing key). Make sure another launcher owns Home, uninstall the old $pkg first, then re-run this script. The previous launcher ($StockLauncher) was left untouched."
}
if ($LASTEXITCODE -ne 0 -or "$out" -notmatch 'Success') {
    throw "pm install failed; the previous launcher ($StockLauncher) was left untouched."
}

# Force-stop any instance Android restarted mid-update (CleanYourTV #1101).
& $py -I $tvPy "am force-stop $pkg" | Out-Null

# Start Astryx BEFORE disabling previous launcher (CleanYourTV #239).
$startCmd = if ($StockLauncher -eq 'none') { "am start -W -n $activity" }
            else { "am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n $activity" }
$start = (& $py -I $tvPy $startCmd) -join "`n"
Write-Host $start
if ($LASTEXITCODE -ne 0 -or $start -notmatch 'Status: ok' -or $start -match '(?m)^Error') {
    throw "Astryx Rust Launcher did not start cleanly (see above); $StockLauncher was left enabled."
}

# ---------------------------------------------------------------------------
# Handover batch: AOT compile, Astryx app-ops, disable old launcher, set Home
# ---------------------------------------------------------------------------
$batch = [System.Collections.Generic.List[string]]::new()
$batch.Add("cmd package compile -m speed -f $pkg")
if (-not $NoOverlayGrant) {
    $batch.Add("cmd appops set $pkg SYSTEM_ALERT_WINDOW allow 2>/dev/null; echo overlay-allowed")
}
if (-not $NoAutoStart) {
    $batch.Add("cmd appops set $pkg AUTO_START allow 2>/dev/null; cmd appops set $pkg APP_AUTO_START allow 2>/dev/null; appops write-settings 2>/dev/null; echo autostart-allowed")
}
if ($GrantInstall) {
    $batch.Add("cmd appops set $pkg REQUEST_INSTALL_PACKAGES allow 2>/dev/null; echo install-allowed")
}
$batch.Add($disableStock)
$batch.Add("cmd package set-home-activity --user 0 $activity")

$ErrorActionPreference = 'Continue'
$batch | & $py -I $tvPy --batch
$batchExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($batchExit -ne 0) {
    Write-Warning "tv.py --batch failed (exit code $batchExit); verifying where Home ended up"
}

# ---------------------------------------------------------------------------
# Verify Home handover & automatic rollback if Home did not land on Astryx
# ---------------------------------------------------------------------------
function Get-HomeNow {
    $ErrorActionPreference = 'Continue'
    $line = @(& $py -I $tvPy $homeQuery 2>$null) | Where-Object { $_ -match '\S' } | Select-Object -Last 1
    $rc = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($rc -ne 0 -or -not $line) { return '' }
    "$line".Trim()
}

$safeHost = ($TvHost -replace '[^A-Za-z0-9._-]', '_')

function Undo-Handover {
    if ($StockLauncher -eq 'none') {
        Write-Warning 'Home is not Astryx Rust Launcher, and no previous launcher was disabled; nothing to roll back.'
        return $false
    }
    $ErrorActionPreference = 'Continue'
    & $py -I $tvPy "pm enable --user 0 $StockLauncher" 2>$null | Out-Null
    $enableExit = $LASTEXITCODE
    & $py -I $tvPy "cmd package set-home-activity --user 0 `$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $StockLauncher | tail -n 1)" 2>$null | Out-Null
    $setHomeExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    $back = Get-HomeNow
    if ($enableExit -eq 0 -and $setHomeExit -eq 0 -and $back -like "$StockLauncher/*") {
        Write-Warning "$StockLauncher owns Home again."
        return $true
    }
    $homeDir = if ($env:USERPROFILE) { $env:USERPROFILE } elseif ($env:HOME) { $env:HOME } else { (Get-Location).Path }
    $rec = Join-Path $homeDir "astryx-launcher-handover-$safeHost.txt"
    $lines = @(
        "# Astryx Rust Launcher: the launcher handover on $TvHost did not finish. Run:",
        "`$env:TV_HOST='$TvHost'; & '$py' -I '$tvPy' 'pm enable --user 0 $StockLauncher'",
        "`$env:TV_HOST='$TvHost'; & '$py' -I '$tvPy' 'cmd package set-home-activity --user 0 `$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $StockLauncher | tail -n 1)'",
        "# then check: & '$py' -I '$tvPy' '$homeQuery'"
    )
    try { $lines | Out-File -Encoding ascii $rec } catch { $rec = '' }
    Write-Warning "Recovery is incomplete; Home still resolves to '$(if ($back) { $back } else { 'nothing' })'."
    if ($rec) { Write-Warning "The commands that finish recovery were written to: $rec" }
    else { Write-Warning "Re-run once the TV answers: pm enable --user 0 $StockLauncher, then set its Home activity." }
    return $false
}

$homeNow = Get-HomeNow
if ($homeNow -notlike "$pkg/*") {
    $reason = if ($homeNow) { "Home resolves to '$homeNow', not to Astryx Rust Launcher" } else { 'the TV stopped answering while Home was being switched, so the outcome is unknown' }
    $restored = Undo-Handover
    $hint = if ($homeNow) { ' On Google TV this usually means com.google.android.tungsten.setupwraith also claims Home: re-join Wi-Fi in Settings, disable setupwraith, then run this script again.' } else { '' }
    throw "$reason; $(if ($restored) { "$StockLauncher was re-enabled." } else { 'recovery is incomplete (see above).' })$hint"
}

Write-Host "Home now resolves to $homeNow"
if ($StockLauncher -ne 'none') {
    $cacheDir = Join-Path $repoRoot 'tools\.cache'
    New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null
    $StockLauncher | Out-File -Encoding ascii -NoNewline (Join-Path $cacheDir "previous-home-$safeHost.txt")
}

Write-Host 'Installed Astryx Rust Launcher. Press Home on the remote to see it.'
if ($StockLauncher -ne 'none') {
    Write-Host "To switch back later: .\restore-stock-launcher.ps1 -TvHost $TvHost"
}
