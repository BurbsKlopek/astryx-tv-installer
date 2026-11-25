# Windows PowerShell twin of restore-stock-launcher.sh: re-enable the previous / stock
# Android TV launcher and restore it as Home. Optionally uninstalls Astryx Rust Launcher
# ONLY AFTER confirming the stock launcher owns Home.
#
# Adapted from CleanYourTV's tools/restore.ps1 (MIT License, Gathin):
# https://github.com/Gathin23/cleanyourtv
#
# Usage:
#   .\restore-stock-launcher.ps1 -TvHost 192.168.1.50
#   .\restore-stock-launcher.ps1 -TvHost 192.168.1.50 -Uninstall
#   .\restore-stock-launcher.ps1 -TvHost 192.168.1.50 -Launcher com.google.android.apps.tv.launcherx
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$Launcher = '',
    [switch]$Uninstall,
    [switch]$Help
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = New-Object System.Text.UTF8Encoding $false

if ($Help) {
    Write-Host @'
Usage: .\restore-stock-launcher.ps1 -TvHost <tv-ip> [options]

Re-enable the stock (or previous) Android TV launcher and set it back as Home.
If -Launcher is omitted, uses the record saved by install.ps1, or detects the
disabled stock launcher on the TV.

Parameters:
  -TvHost <ip[:port]>   TV IP address (or set $env:TV_HOST)
  -Launcher <package>   Specific launcher package to restore
  -Uninstall            Also uninstall Astryx Rust Launcher after Home is restored
  -Help                 Show this help and exit
'@
    exit 0
}

$pkg = 'com.astryxtv.rustlauncher'
$knownLaunchers = @('com.google.android.apps.tv.launcherx', 'com.google.android.tvlauncher', 'com.google.android.leanbacklauncher', 'com.mitv.tvhome', 'com.gathin.tvhome')
$homeQuery = 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'

if ($Launcher) {
    if ($Launcher -notmatch '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$') {
        throw "Invalid launcher package name: '$Launcher'"
    }
    if ($Launcher -eq $pkg) {
        throw "$pkg is Astryx Rust Launcher itself; pass the stock launcher package to restore."
    }
}

if (-not $TvHost) { throw 'Pass -TvHost <tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost

$repoRoot = $PSScriptRoot
Set-Location $repoRoot

function Find-VenvPython([string]$ToolsDir) {
    foreach ($rel in @('venv\Scripts\python.exe', 'venv/bin/python')) {
        $cand = Join-Path $ToolsDir $rel
        if (Test-Path $cand) { return $cand }
    }
    return $null
}

$toolsDir = $null
$py = $null
if ($env:CLEANYOURTV_TOOLS) {
    $toolsDir = [System.IO.Path]::GetFullPath($env:CLEANYOURTV_TOOLS)
    if (-not (Test-Path (Join-Path $toolsDir 'tv.py'))) { throw "CLEANYOURTV_TOOLS=$toolsDir has no tv.py." }
    $py = Find-VenvPython $toolsDir
} else {
    $localTools = Join-Path $repoRoot 'tools'
    $siblingTools = [System.IO.Path]::GetFullPath((Join-Path $repoRoot '..\cleanyourtv\tools'))
    $py = Find-VenvPython $localTools
    if ($py) {
        $toolsDir = $localTools
    } elseif ((Test-Path (Join-Path $siblingTools 'tv.py')) -and ($sibPy = Find-VenvPython $siblingTools)) {
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
$safeHost = ($TvHost -replace '[^A-Za-z0-9._-]', '_')
$record = Join-Path $repoRoot "tools\.cache\previous-home-$safeHost.txt"

if (-not $Launcher -and (Test-Path $record)) {
    $saved = (Get-Content -Raw $record).Trim()
    if ($saved -match '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$') {
        $Launcher = $saved
        Write-Host "Using saved previous launcher from install record: $Launcher"
    }
}

if (-not $Launcher) {
    $disabledRaw = @(& $py -I $tvPy 'pm list packages -d --user 0' 2>$null)
    $disabled = @($disabledRaw | ForEach-Object { if ("$_".Trim() -match '^package:(\S+)') { $Matches[1] } })
    $matchesList = @($knownLaunchers | Where-Object { $disabled -contains $_ })
    if ($matchesList.Count -eq 1) {
        $Launcher = $matchesList[0]
        Write-Host "Detected disabled launcher on TV: $Launcher"
    } elseif ($matchesList.Count -gt 1) {
        throw "Multiple disabled launchers found on this TV: $($matchesList -join ', '). Pass -Launcher <package> to pick one."
    } else {
        $enabledRaw = @(& $py -I $tvPy 'pm list packages -e --user 0' 2>$null)
        $enabled = @($enabledRaw | ForEach-Object { if ("$_".Trim() -match '^package:(\S+)') { $Matches[1] } })
        $matchesList = @($knownLaunchers | Where-Object { $enabled -contains $_ })
        if ($matchesList.Count -eq 1) {
            $Launcher = $matchesList[0]
            Write-Host "Found enabled stock launcher on TV: $Launcher"
        } else {
            throw 'Could not automatically determine which launcher to restore. Pass -Launcher com.google.android.apps.tv.launcherx or -Launcher com.google.android.tvlauncher.'
        }
    }
}

Write-Host "Re-enabling $Launcher and restoring it as Home..."
& $py -I $tvPy "pm enable --user 0 $Launcher"
if ($LASTEXITCODE -ne 0) { throw "Failed to re-enable $Launcher" }

$compLine = @(& $py -I $tvPy "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $Launcher") |
    Where-Object { $_ -match '\S' } | Select-Object -Last 1
$comp = "$compLine".Trim()
if ($comp -notmatch ('^' + [regex]::Escape($Launcher) + '/[A-Za-z0-9_.$]+$')) {
    throw "Enabled $Launcher, but could not resolve its HOME activity (got '$comp'). Astryx Rust Launcher was NOT uninstalled."
}

& $py -I $tvPy "cmd package set-home-activity --user 0 $comp"
if ($LASTEXITCODE -ne 0) { throw "Failed to set Home activity to $comp" }

function Short-Comp([string]$C) {
    $parts = $C.Split('/', 2)
    if ($parts.Count -ne 2) { return $C }
    $p = $parts[0]; $a = $parts[1]
    if ($a.StartsWith("$p.")) { $a = '.' + $a.Substring($p.Length + 1) }
    return "$p/$a"
}

$nowLine = @(& $py -I $tvPy $homeQuery 2>$null) | Where-Object { $_ -match '\S' } | Select-Object -Last 1
$now = "$nowLine".Trim()
if ((Short-Comp $now) -ne (Short-Comp $comp)) {
    throw "Home still resolves to '$now' instead of '$comp'. Refusing to uninstall $pkg automatically."
}

Write-Host "Home now resolves to $now"
Remove-Item -Force -ErrorAction SilentlyContinue $record

if ($Uninstall) {
    Write-Host "Uninstalling Astryx Rust Launcher ($pkg)..."
    $unOut = (& $py -I $tvPy "pm uninstall --user 0 $pkg") -join "`n"
    Write-Host $unOut
    if ($LASTEXITCODE -ne 0 -or "$unOut" -notmatch 'Success') {
        throw "Could not uninstall $pkg cleanly (output above); Home is already back on $now."
    }
    Write-Host "Uninstalled $pkg."
}

Write-Host "Restored. Press Home on the remote to return to $Launcher."
