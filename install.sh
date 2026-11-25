#!/usr/bin/env bash
# Install Astryx Rust Launcher on an Android TV / Google TV over Wi-Fi ADB and make it
# the default home screen, with automatic stock-launcher detection and rollback on failure.
#
# Adapted from CleanYourTV's launcher/build-and-install.sh (MIT License, Gathin):
# https://github.com/Gathin23/cleanyourtv
set -euo pipefail
export LC_ALL=C
export MSYS_NO_PATHCONV=1

PKG="com.astryxtv.rustlauncher"
ACTIVITY="com.astryxtv.rustlauncher/.RustLauncherActivity"
REMOTE_APK="/data/local/tmp/astryx-rust-launcher.apk"
GITHUB_REPO="BurbsKlopek/astryx-rust-launcher"
KNOWN_STOCK_LAUNCHERS="com.google.android.tvlauncher com.google.android.apps.tv.launcherx com.google.android.leanbacklauncher com.mitv.tvhome"
CLEANYOURTV_LAUNCHER="com.gathin.tvhome"

ORIG_PWD="$(pwd)"
REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"

usage() {
  cat <<'EOF'
Usage: TV_HOST=<tv-ip> ./install.sh [options]

Install Astryx Rust Launcher on your Android TV / Google TV and set it as Home.
Uses CleanYourTV's pure-Python ADB transport and safe Home handover + rollback.

Options:
  --tv-host <ip[:port]>       TV IP address (or set TV_HOST env var)
  --apk <path>                Install a specific local APK file
  --build                     Compile Rust (armv7 + arm64) + Gradle release APK from source
  --download                  Download the latest GitHub Release APK even if a local APK exists
  --version <tag>             Download a specific GitHub Release tag (e.g. v1.2.0)
  --stock-launcher <pkg|none> Override detected previous launcher, or 'none' to leave it enabled
  --no-overlay-grant          Skip granting SYSTEM_ALERT_WINDOW (live FPS overlay pill)
  --no-autostart              Skip TCL/vendor AUTO_START & APP_AUTO_START app-op grants
  --grant-install             Also grant REQUEST_INSTALL_PACKAGES (for in-app self-updates)
  -h, --help                  Show this help and exit

Environment variables (CleanYourTV-compatible):
  TV_HOST                     TV IP address (or IP:port), e.g. 192.168.1.50
  TV_PORT                     Optional ADB port (default 5555)
  TV_ADB_BINARY               Optional path to native adb binary (paired Wireless debugging)
  STOCK_LAUNCHER              Same as --stock-launcher
  APK                         Same as --apk
  BUILD=1                     Same as --build (overridden by SKIP_BUILD=1)
  SKIP_BUILD=1                Never build from source
  CLEANYOURTV_TOOLS           Optional path to an existing cleanyourtv/tools directory

Examples:
  TV_HOST=192.168.1.50 ./install.sh
  TV_HOST=192.168.1.50 ./install.sh --build
  TV_HOST=192.168.1.50 ./install.sh --apk ~/Downloads/Astryx-Rust-Launcher-v1.2.0.apk
  TV_HOST=192.168.1.50 ./install.sh --download
  TV_HOST=192.168.1.50 ./install.sh --stock-launcher none
EOF
}

APK_ARG="${APK:-}"
DO_BUILD="${BUILD:-0}"
DO_DOWNLOAD=0
RELEASE_TAG=""
GRANT_OVERLAY=1
GRANT_AUTOSTART=1
GRANT_INSTALL=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help)
      usage
      exit 0 ;;
    --tv-host)
      [ "$#" -ge 2 ] || { echo "ERROR: --tv-host requires an IP address." >&2; exit 2; }
      TV_HOST="$2"; shift 2 ;;
    --apk)
      [ "$#" -ge 2 ] || { echo "ERROR: --apk requires a file path." >&2; exit 2; }
      APK_ARG="$2"; shift 2 ;;
    --build)
      DO_BUILD=1; shift ;;
    --download)
      DO_DOWNLOAD=1; shift ;;
    --version)
      [ "$#" -ge 2 ] || { echo "ERROR: --version requires a release tag (e.g. v1.2.0)." >&2; exit 2; }
      RELEASE_TAG="$2"; DO_DOWNLOAD=1; shift 2 ;;
    --stock-launcher)
      [ "$#" -ge 2 ] || { echo "ERROR: --stock-launcher requires a package name or 'none'." >&2; exit 2; }
      STOCK_LAUNCHER="$2"; shift 2 ;;
    --no-overlay-grant)
      GRANT_OVERLAY=0; shift ;;
    --no-autostart)
      GRANT_AUTOSTART=0; shift ;;
    --grant-install)
      GRANT_INSTALL=1; shift ;;
    *)
      echo "ERROR: unknown argument: $1" >&2
      echo "Run $0 --help for usage." >&2
      exit 2 ;;
  esac
done

if [ "${SKIP_BUILD:-}" = "1" ]; then
  DO_BUILD=0
fi

# Resolve any caller-supplied APK path before leaving ORIG_PWD.
resolve_path() {
  local p="$1"
  case "$p" in
    /*|[A-Za-z]:/*|[A-Za-z]:\\*) printf '%s\n' "$p" ;;
    *) printf '%s/%s\n' "$ORIG_PWD" "$p" ;;
  esac
}

if [ -n "$APK_ARG" ]; then
  APK_ARG="$(resolve_path "$APK_ARG")"
fi

if [ -z "${TV_HOST:-}" ]; then
  echo "ERROR: set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0" >&2
  echo "       or pass --tv-host 192.168.1.50" >&2
  exit 2
fi
export TV_HOST

cd "$REPO_ROOT"

# Convert a directory to its native absolute form (Windows path under Git Bash, POSIX otherwise)
# so Python inside a Windows venv does not choke on /c/... with MSYS_NO_PATHCONV=1.
native_dir() {
  (cd "$1" && (pwd -W 2>/dev/null || pwd))
}

native_file() {
  local d f
  d="$(native_dir "$(dirname "$1")")"
  f="$(basename "$1")"
  printf '%s/%s\n' "$d" "$f"
}

find_venv_python() {
  local dir="$1"
  if [ -x "$dir/venv/bin/python" ]; then
    printf '%s\n' "$dir/venv/bin/python"
    return 0
  elif [ -x "$dir/venv/Scripts/python.exe" ]; then
    printf '%s\n' "$dir/venv/Scripts/python.exe"
    return 0
  fi
  return 1
}

# Locate CleanYourTV's tv.py / b64push.py and Python venv:
#   1. Explicit CLEANYOURTV_TOOLS env var
#   2. Local tools/venv inside this repo
#   3. Sibling ../cleanyourtv/tools (reuses existing venv + already-authorized ADB key!)
#   4. Local bundled tools/ (auto-bootstraps tools/venv on first run)
TOOLS=""
PY=""
if [ -n "${CLEANYOURTV_TOOLS:-}" ]; then
  [ -f "$CLEANYOURTV_TOOLS/tv.py" ] && [ -f "$CLEANYOURTV_TOOLS/b64push.py" ] || {
    echo "ERROR: CLEANYOURTV_TOOLS=$CLEANYOURTV_TOOLS does not contain tv.py and b64push.py." >&2
    exit 1
  }
  TOOLS="$(native_dir "$CLEANYOURTV_TOOLS")"
  PY="$(find_venv_python "$TOOLS" || true)"
elif PY="$(find_venv_python "$REPO_ROOT/tools" 2>/dev/null)"; then
  TOOLS="$(native_dir "$REPO_ROOT/tools")"
elif [ -f "$REPO_ROOT/../cleanyourtv/tools/tv.py" ] && [ -f "$REPO_ROOT/../cleanyourtv/tools/b64push.py" ] \
    && PY="$(find_venv_python "$REPO_ROOT/../cleanyourtv/tools" 2>/dev/null)"; then
  TOOLS="$(native_dir "$REPO_ROOT/../cleanyourtv/tools")"
  echo "Reusing CleanYourTV tools (venv + ADB key): $TOOLS"
else
  TOOLS="$(native_dir "$REPO_ROOT/tools")"
fi

if [ -z "$PY" ]; then
  if [ -f "$TOOLS/requirements.txt" ] && command -v python3 >/dev/null 2>&1; then
    echo "Setting up Python virtual environment in $TOOLS/venv ..."
    if python3 -m venv "$TOOLS/venv"; then
      PY="$(find_venv_python "$TOOLS" || true)"
      if [ -n "$PY" ]; then
        "$PY" -m pip install -q -r "$TOOLS/requirements.txt" || PY=""
      fi
    fi
  fi
fi

if [ -z "$PY" ] || [ ! -x "$PY" ]; then
  echo "ERROR: Python ADB environment not ready. Run:" >&2
  echo "  python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2
  exit 1
fi

tv() { "$PY" -I "$TOOLS/tv.py" "$@"; }
b64push() { "$PY" -I "$TOOLS/b64push.py" "$@"; }

# Resolve Astryx Rust Launcher source repo (optional, only needed for --build or local build pickup).
LAUNCHER_REPO=""
for cand in "$REPO_ROOT" "$REPO_ROOT/../astryx-tv-launcher" "$REPO_ROOT/../astryx-rust-launcher"; do
  if [ -d "$cand/rust" ] && [ -d "$cand/android-rust" ]; then
    LAUNCHER_REPO="$(cd "$cand" && pwd)"
    break
  fi
done

# Pre-flight SDK check (CleanYourTV #576): fail fast before building, downloading, or pushing
# if the TV runs an Android version older than Astryx Rust Launcher's minSdk.
MIN_SDK=""
if [ -n "$LAUNCHER_REPO" ]; then
  MIN_SDK=$(sed -n 's/^[[:space:]]*minSdk[[:space:]]*=[[:space:]]*\([0-9]\{1,3\}\)[[:space:]]*$/\1/p' "$LAUNCHER_REPO/android-rust/app/build.gradle.kts" 2>/dev/null | head -n 1 || true)
fi
MIN_SDK="${MIN_SDK:-26}"
TV_SDK=$(tv 'getprop ro.build.version.sdk' 2>/dev/null | tr -dc '0-9' || true)
if [ -n "$MIN_SDK" ] && [ -n "$TV_SDK" ] && [ "$TV_SDK" -lt "$MIN_SDK" ]; then
  echo "ERROR: this TV runs Android SDK $TV_SDK and Astryx Rust Launcher requires minSdk $MIN_SDK (Android 8.0+)," >&2
  echo "so pm install would answer INSTALL_FAILED_OLDER_SDK. Nothing was built, pushed, or disabled." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# APK acquisition: --build | --apk | --download/--version | smart auto-detect
# ---------------------------------------------------------------------------
build_from_source() {
  [ -n "$LAUNCHER_REPO" ] || {
    echo "ERROR: --build requires a local clone of $GITHUB_REPO beside this folder (../astryx-rust-launcher or ../astryx-tv-launcher)." >&2
    echo "Run without --build to download the latest GitHub Release APK automatically." >&2
    exit 1
  }
  echo "Building Astryx Rust Launcher from source ($LAUNCHER_REPO)..."

  # Make sure cargo is reachable even in non-login shells.
  if ! command -v cargo >/dev/null 2>&1 && [ -x "$HOME/.cargo/bin/cargo" ]; then
    export PATH="$HOME/.cargo/bin:$PATH"
  fi
  command -v cargo >/dev/null 2>&1 || {
    echo "ERROR: cargo not found. Install Rust (https://rustup.rs) or run without --build to use a prebuilt APK." >&2
    exit 1
  }

  # JDK 17+ detection (full JDK with jlink, matching CleanYourTV #1255).
  if [ -z "${JAVA_HOME:-}" ]; then
    JH="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    if [ -n "$JH" ]; then
      case "$("$JH/bin/java" -version 2>&1 || true)" in
        *'version "17.'*|*'version "21.'*) ;;
        *) JH="" ;;
      esac
    fi
    if [ -n "$JH" ] && [ ! -x "$JH/bin/jlink" ]; then JH=""; fi
    if [ -z "$JH" ] && [ -d /opt/homebrew/opt/openjdk@17 ]; then JH=/opt/homebrew/opt/openjdk@17; fi
    if [ -z "$JH" ] && [ -d /usr/local/opt/openjdk@17 ]; then JH=/usr/local/opt/openjdk@17; fi
    if [ -z "$JH" ]; then
      JH="$(java -XshowSettings:properties -version 2>&1 | awk '/java.home/ {print $3}' || true)"
    fi
    JAVA_HOME="$JH"
  fi
  [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] || {
    echo "ERROR: JDK 17+ not found. Set JAVA_HOME (macOS: brew install openjdk@17)." >&2
    exit 1
  }
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"

  # Android SDK detection (Android Studio default on macOS/Linux, then Homebrew cmdline-tools).
  if [ -z "${ANDROID_HOME:-}" ]; then
    for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" \
             /opt/homebrew/share/android-commandlinetools /usr/local/share/android-commandlinetools; do
      if [ -d "$d" ]; then ANDROID_HOME="$d"; break; fi
    done
  fi
  ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
  [ -d "$ANDROID_HOME" ] || {
    echo "ERROR: Android SDK not found at '$ANDROID_HOME'. Set ANDROID_HOME." >&2
    exit 1
  }
  export ANDROID_HOME

  # Locate newest Android NDK LLVM toolchain.
  NDK_DIR="${ANDROID_NDK_HOME:-}"
  if [ -z "$NDK_DIR" ] && [ -d "$ANDROID_HOME/ndk" ]; then
    NDK_DIR="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -n 1 || true)"
  fi
  [ -n "$NDK_DIR" ] && [ -d "$NDK_DIR/toolchains/llvm/prebuilt" ] || {
    echo "ERROR: Android NDK not found under '$ANDROID_HOME/ndk'. Install NDK or set ANDROID_NDK_HOME." >&2
    exit 1
  }
  NDK_BIN=""
  for candidate in "$NDK_DIR"/toolchains/llvm/prebuilt/*/bin; do
    if [ -d "$candidate" ]; then NDK_BIN="$candidate"; break; fi
  done
  [ -n "$NDK_BIN" ] || { echo "ERROR: could not find NDK LLVM bin directory in $NDK_DIR." >&2; exit 1; }
  export PATH="$NDK_BIN:$PATH"

  if command -v rustup >/dev/null 2>&1; then
    INSTALLED_TARGETS="$(rustup target list --installed 2>/dev/null || true)"
    for t in armv7-linux-androideabi aarch64-linux-android; do
      if ! printf '%s\n' "$INSTALLED_TARGETS" | grep -xF "$t" >/dev/null; then
        echo "ERROR: Rust target '$t' is not installed. Run: rustup target add $t" >&2
        exit 1
      fi
    done
  fi

  (
    cd "$LAUNCHER_REPO/rust"
    CC_armv7_linux_androideabi=armv7a-linux-androideabi26-clang \
    AR_armv7_linux_androideabi=llvm-ar \
    CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER=armv7a-linux-androideabi26-clang \
      cargo build --release --target armv7-linux-androideabi

    CC_aarch64_linux_android=aarch64-linux-android26-clang \
    AR_aarch64_linux_android=llvm-ar \
    CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=aarch64-linux-android26-clang \
      cargo build --release --target aarch64-linux-android

    mkdir -p ../android-rust/app/src/main/jniLibs/armeabi-v7a ../android-rust/app/src/main/jniLibs/arm64-v8a
    cp target/armv7-linux-androideabi/release/libastryx_tv_rust.so ../android-rust/app/src/main/jniLibs/armeabi-v7a/
    cp target/aarch64-linux-android/release/libastryx_tv_rust.so ../android-rust/app/src/main/jniLibs/arm64-v8a/
  )

  # local.properties needs forward slashes even on Git Bash for Windows.
  [ -f "$LAUNCHER_REPO/android-rust/local.properties" ] || \
    echo "sdk.dir=$(printf '%s' "$ANDROID_HOME" | tr '\\' '/')" > "$LAUNCHER_REPO/android-rust/local.properties"

  (
    cd "$LAUNCHER_REPO/android-rust"
    chmod +x gradlew
    ./gradlew assembleRelease -q --console=plain
  )

  APK="$LAUNCHER_REPO/android-rust/app/build/outputs/apk/release/app-release.apk"
  [ -f "$APK" ] || { echo "ERROR: build succeeded but APK not found at $APK." >&2; exit 1; }
}

download_release_apk() {
  local tag="$1"
  local cache_dir="$REPO_ROOT/tools/.cache"
  mkdir -p "$cache_dir"
  local cache_native
  cache_native="$(native_dir "$cache_dir")"

  echo "Fetching Astryx Rust Launcher release info from GitHub ($GITHUB_REPO)..." >&2
  APK=$("$PY" - "$GITHUB_REPO" "$tag" "$cache_native" <<'PYEOF'
import json, os, sys, urllib.request, urllib.error

repo, tag, cache_dir = sys.argv[1], sys.argv[2], sys.argv[3]
if tag:
    tags = [tag] if tag.startswith("v") else [f"v{tag}", tag]
    urls = [f"https://api.github.com/repos/{repo}/releases/tags/{t}" for t in tags]
else:
    urls = [f"https://api.github.com/repos/{repo}/releases/latest"]

data = None
last_err = None
for url in urls:
    req = urllib.request.Request(url, headers={"User-Agent": "astryx-rust-launcher-installer"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            break
    except urllib.error.URLError as e:
        last_err = e

if data is None:
    sys.stderr.write(f"ERROR: could not fetch GitHub release ({last_err}). Pass --apk <path> or --build instead.\n")
    sys.exit(1)

assets = [a for a in data.get("assets", []) if a.get("name", "").endswith(".apk")]
if not assets:
    sys.stderr.write(f"ERROR: release {data.get('tag_name', '?')} has no .apk asset.\n")
    sys.exit(1)

asset = assets[0]
name = os.path.basename(asset["name"])
dl_url = asset["browser_download_url"]
dest = os.path.join(cache_dir, name)
part = dest + ".part"

if os.path.isfile(dest) and os.path.getsize(dest) > 100000:
    sys.stderr.write(f"Using cached release APK: {dest}\n")
    print(dest)
    sys.exit(0)

sys.stderr.write(f"Downloading {name} ({data.get('tag_name', '')})...\n")
req = urllib.request.Request(dl_url, headers={"User-Agent": "astryx-rust-launcher-installer"})
try:
    with urllib.request.urlopen(req, timeout=180) as resp, open(part, "wb") as out:
        while True:
            buf = resp.read(65536)
            if not buf:
                break
            out.write(buf)
except Exception as e:
    try:
        os.remove(part)
    except OSError:
        pass
    sys.stderr.write(f"ERROR: download failed: {e}\n")
    sys.exit(1)

with open(part, "rb") as f:
    magic = f.read(2)
if magic != b"PK":
    os.remove(part)
    sys.stderr.write("ERROR: downloaded file is not a valid APK (missing ZIP/PK header).\n")
    sys.exit(1)

os.replace(part, dest)
print(dest)
PYEOF
  )
  [ -n "$APK" ] && [ -f "$APK" ] || { echo "ERROR: failed to download release APK." >&2; exit 1; }
}

APK=""
if [ "$DO_BUILD" = "1" ]; then
  build_from_source
elif [ -n "$APK_ARG" ]; then
  [ -r "$APK_ARG" ] || { echo "ERROR: cannot read APK file: $APK_ARG" >&2; exit 2; }
  APK="$APK_ARG"
elif [ "$DO_DOWNLOAD" = "1" ]; then
  download_release_apk "$RELEASE_TAG"
else
  # Smart default: prefer freshly built APK in android-rust/, then local Astryx-Rust-Launcher-*.apk,
  # then latest GitHub Release.
  if [ -n "$LAUNCHER_REPO" ] && [ -f "$LAUNCHER_REPO/android-rust/app/build/outputs/apk/release/app-release.apk" ]; then
    APK="$LAUNCHER_REPO/android-rust/app/build/outputs/apk/release/app-release.apk"
  else
    CANDIDATE=$("$PY" - "$REPO_ROOT" "$HOME/Downloads/Android Development" <<'PYEOF'
import glob, os, re, sys

def ver_key(path):
    name = os.path.basename(path)
    nums = tuple(int(x) for x in re.findall(r"\d+", name))
    return (nums, os.path.getmtime(path))

paths = []
for root in sys.argv[1:]:
    if os.path.isdir(root):
        paths.extend(glob.glob(os.path.join(root, "Astryx-Rust-Launcher-*.apk")))
paths = [p for p in paths if os.path.isfile(p)]
if paths:
    paths.sort(key=ver_key)
    print(paths[-1])
PYEOF
    )
    if [ -n "$CANDIDATE" ] && [ -r "$CANDIDATE" ]; then
      APK="$CANDIDATE"
    else
      download_release_apk ""
    fi
  fi
fi

APK_NATIVE="$(native_file "$APK")"
APK_SIZE_BYTES=$(wc -c < "$APK" | tr -d '[:space:]')
APK_SIZE_MB=$(awk -v b="$APK_SIZE_BYTES" 'BEGIN { printf "%.2f", b / 1048576 }')
echo "APK: $APK (${APK_SIZE_MB} MB)"

# ---------------------------------------------------------------------------
# Stock / previous launcher detection (CleanYourTV safety logic)
# ---------------------------------------------------------------------------
# Which app owns Home differs by TV firmware (com.google.android.tvlauncher on Android TV,
# com.google.android.apps.tv.launcherx on Google TV, com.mitv.tvhome on PatchWall, or
# com.gathin.tvhome if upgrading from CleanYourTV's launcher). Ask the TV instead of guessing,
# and refuse unknown launchers unless the caller explicitly sets --stock-launcher / STOCK_LAUNCHER.
HOME_QUERY='cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
if [ -z "${STOCK_LAUNCHER:-}" ]; then
  HOME_PKG=$(tv "$HOME_QUERY" | tail -n 1 | tr -d '\r' | cut -d/ -f1) || {
    echo "ERROR: could not ask the TV which launcher owns Home. Check TV_HOST and the debugging prompt on the TV," >&2
    echo "or pass --stock-launcher <package> yourself." >&2
    exit 1
  }
  case " $KNOWN_STOCK_LAUNCHERS " in
    *" $HOME_PKG "*) STOCK_LAUNCHER="$HOME_PKG" ;;
    *)
      if [ "$HOME_PKG" = "$PKG" ]; then
        STOCK_LAUNCHER=none
        echo "Home already belongs to Astryx Rust Launcher; nothing will be disabled."
      elif [ "$HOME_PKG" = "$CLEANYOURTV_LAUNCHER" ]; then
        STOCK_LAUNCHER="$CLEANYOURTV_LAUNCHER"
        echo "Home currently belongs to CleanYourTV launcher ($CLEANYOURTV_LAUNCHER); it will be disabled once Astryx starts (pass --stock-launcher none to keep both enabled)."
      elif [ "$HOME_PKG" = "android" ]; then
        CLAIMANTS=$(tv 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME' \
          | tr -d '\r' | grep -oE '[A-Za-z0-9_.]*/' | tr -d / | sort -u)
        FOUND=""; N=0
        for k in $KNOWN_STOCK_LAUNCHERS; do
          if printf '%s\n' "$CLAIMANTS" | grep -xF "$k" >/dev/null; then FOUND="$k"; N=$((N + 1)); fi
        done
        if [ "$N" -eq 1 ]; then
          STOCK_LAUNCHER="$FOUND"
          echo "Home is Android's chooser; the stock launcher among the candidates is $STOCK_LAUNCHER."
        else
          echo "ERROR: Home is Android's launcher chooser and $N known stock launchers claim Home: pick a default on the TV first," >&2
          echo "or run again with --stock-launcher <package to disable> or --stock-launcher none." >&2
          exit 1
        fi
      else
        echo "ERROR: Home currently resolves to '${HOME_PKG:-nothing}', which is not a stock launcher this script knows." >&2
        echo "  android                                  = Android's launcher chooser (two launchers tie); pick a default on the TV first" >&2
        echo "  com.google.android.tungsten.setupwraith  = Google TV's setup wizard" >&2
        echo "  anything else                            = another third-party launcher is the default" >&2
        echo "Run again with --stock-launcher <package to disable> or --stock-launcher none." >&2
        exit 1
      fi ;;
  esac
fi

if [ "$STOCK_LAUNCHER" != "none" ]; then
  if ! printf '%s\n' "$STOCK_LAUNCHER" | grep -E '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$' >/dev/null; then
    echo "ERROR: invalid --stock-launcher package name: '$STOCK_LAUNCHER' (expected a package name or 'none')." >&2
    exit 2
  fi
  if [ "$STOCK_LAUNCHER" = "$PKG" ]; then
    echo "ERROR: refusing to disable Astryx Rust Launcher itself ($PKG). Use --stock-launcher none instead." >&2
    exit 2
  fi
fi

echo "Previous launcher to disable: $STOCK_LAUNCHER"
if [ "$STOCK_LAUNCHER" = "none" ]; then
  DISABLE_STOCK="echo previous launcher left alone"
else
  DISABLE_STOCK="pm disable-user --user 0 $STOCK_LAUNCHER"
fi

# ---------------------------------------------------------------------------
# Upload & install APK (BEFORE touching the previous launcher)
# ---------------------------------------------------------------------------
echo "Uploading APK to TV ($REMOTE_APK)..."
if [ -n "${TV_ADB_BINARY:-}" ]; then
  tv --push "$APK_NATIVE" "$REMOTE_APK" || b64push "$APK_NATIVE" "$REMOTE_APK"
else
  b64push "$APK_NATIVE" "$REMOTE_APK"
fi

# Install on its own: a failed install must never disable the stock launcher.
OUT=$(tv "pm install -r $REMOTE_APK; rm -f $REMOTE_APK")
echo "$OUT"
case "$OUT" in
  *Success*) ;;
  *INSTALL_FAILED_OLDER_SDK*)
    echo "ERROR: pm install refused the APK as too new for this TV (INSTALL_FAILED_OLDER_SDK): the TV runs an older SDK than minSdk $MIN_SDK." >&2
    echo "The previous launcher ($STOCK_LAUNCHER) was left untouched." >&2
    exit 1 ;;
  *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
    echo "ERROR: pm install failed with INSTALL_FAILED_UPDATE_INCOMPATIBLE (existing install uses a different signing key)." >&2
    echo "Make sure another launcher owns Home, uninstall the old $PKG first, then re-run this script." >&2
    echo "The previous launcher ($STOCK_LAUNCHER) was left untouched." >&2
    exit 1 ;;
  *)
    echo "ERROR: pm install failed; the previous launcher ($STOCK_LAUNCHER) was left untouched." >&2
    exit 1 ;;
esac

# Force-stop any instance Android restarted mid-update so the fresh start below comes up
# cleanly with mOccludesParent=true (CleanYourTV #1101, especially on TCL firmware).
tv "am force-stop $PKG" >/dev/null

# Start Astryx on its own BEFORE touching Home or disabling the previous launcher (CleanYourTV #239).
if [ "$STOCK_LAUNCHER" = "none" ]; then
  START_CMD="am start -W -n $ACTIVITY"
else
  START_CMD="am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n $ACTIVITY"
fi
START_OUT=$(tv "$START_CMD")
echo "$START_OUT"
if ! printf '%s\n' "$START_OUT" | grep -F 'Status: ok' >/dev/null || printf '%s\n' "$START_OUT" | grep -iE '^Error' >/dev/null; then
  echo "ERROR: Astryx Rust Launcher did not start cleanly (see above); $STOCK_LAUNCHER was left enabled." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Handover batch: AOT compile, Astryx app-ops, disable old launcher, set Home
# ---------------------------------------------------------------------------
BATCH_CMDS=("cmd package compile -m speed -f $PKG")
if [ "$GRANT_OVERLAY" = "1" ]; then
  BATCH_CMDS+=("cmd appops set $PKG SYSTEM_ALERT_WINDOW allow 2>/dev/null; echo overlay-allowed")
fi
if [ "$GRANT_AUTOSTART" = "1" ]; then
  # TCL firmware (TclAppBoot) blocks BootReceiver / background launches without AUTO_START / APP_AUTO_START,
  # and drops lazy app-op writes on reboot unless `appops write-settings` flushes them to disk.
  BATCH_CMDS+=("cmd appops set $PKG AUTO_START allow 2>/dev/null; cmd appops set $PKG APP_AUTO_START allow 2>/dev/null; appops write-settings 2>/dev/null; echo autostart-allowed")
fi
if [ "$GRANT_INSTALL" = "1" ]; then
  BATCH_CMDS+=("cmd appops set $PKG REQUEST_INSTALL_PACKAGES allow 2>/dev/null; echo install-allowed")
fi
BATCH_CMDS+=("$DISABLE_STOCK")
BATCH_CMDS+=("cmd package set-home-activity --user 0 $ACTIVITY")

# Do not abort before verification/rollback if the batch drops partway after disabling Home.
BATCH_RC=0
printf '%s\n' "${BATCH_CMDS[@]}" | tv --batch || BATCH_RC=$?
if [ "$BATCH_RC" -ne 0 ]; then
  echo "WARNING: tv.py --batch exited with code $BATCH_RC; verifying where Home ended up..." >&2
fi

# ---------------------------------------------------------------------------
# Verify Home handover & automatic rollback if Home did not land on Astryx
# ---------------------------------------------------------------------------
query_home() {
  tv "$HOME_QUERY" 2>/dev/null | tail -n 1 | tr -d '\r' || true
}

SAFE_HOST="$(printf '%s' "$TV_HOST" | tr -c 'A-Za-z0-9._-' '_')"

rollback_home() {
  if [ "$STOCK_LAUNCHER" = "none" ]; then
    echo "ERROR: Home is not Astryx Rust Launcher, and no previous launcher was disabled; nothing to roll back." >&2
    return 1
  fi
  tv "pm enable --user 0 $STOCK_LAUNCHER" >/dev/null 2>&1 || true
  tv "cmd package set-home-activity --user 0 \$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $STOCK_LAUNCHER | tail -n 1)" >/dev/null 2>&1 || true
  BACK=$(query_home)
  case "$BACK" in
    "$STOCK_LAUNCHER"/*)
      echo "$STOCK_LAUNCHER owns Home again." >&2
      return 0 ;;
  esac
  REC="${HOME:-$PWD}/astryx-launcher-handover-${SAFE_HOST}.txt"
  {
    echo "# Astryx Rust Launcher: the launcher handover on $TV_HOST did not finish. Run:"
    echo "TV_HOST=$TV_HOST $PY -I $TOOLS/tv.py 'pm enable --user 0 $STOCK_LAUNCHER'"
    echo "TV_HOST=$TV_HOST $PY -I $TOOLS/tv.py 'cmd package set-home-activity --user 0 \$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $STOCK_LAUNCHER | tail -n 1)'"
    echo "# then check: TV_HOST=$TV_HOST $PY -I $TOOLS/tv.py '$HOME_QUERY'"
  } > "$REC" 2>/dev/null || REC=""
  echo "ERROR: recovery is incomplete; Home still resolves to '${BACK:-nothing}'." >&2
  if [ -n "$REC" ]; then
    echo "The commands that finish recovery were written to: $REC" >&2
  else
    echo "Re-run once the TV answers: pm enable --user 0 $STOCK_LAUNCHER, then set its Home activity." >&2
  fi
  return 1
}

HOME_NOW=$(query_home)
case "$HOME_NOW" in
  "$PKG"/*)
    echo "Home now resolves to $HOME_NOW" ;;
  *)
    if [ -z "$HOME_NOW" ]; then
      echo "ERROR: the TV stopped answering while Home was being switched, so the outcome is unknown." >&2
    else
      echo "ERROR: Home resolves to $HOME_NOW, not to Astryx Rust Launcher." >&2
    fi
    rollback_home || true
    if [ -n "$HOME_NOW" ]; then
      echo "On Google TV this usually means com.google.android.tungsten.setupwraith also claims Home:" >&2
      echo "make sure your Wi-Fi network is re-joined in Settings (not owned by setupwraith), disable" >&2
      echo "com.google.android.tungsten.setupwraith, then run this script again." >&2
    fi
    exit 1 ;;
esac

# Record which launcher was disabled so restore-stock-launcher.sh can undo cleanly.
if [ "$STOCK_LAUNCHER" != "none" ]; then
  mkdir -p "$REPO_ROOT/tools/.cache"
  printf '%s\n' "$STOCK_LAUNCHER" > "$REPO_ROOT/tools/.cache/previous-home-${SAFE_HOST}.txt"
fi

echo "Installed Astryx Rust Launcher. Press Home on the remote to see it."
if [ "$STOCK_LAUNCHER" != "none" ]; then
  echo "To switch back later: TV_HOST=$TV_HOST ./restore-stock-launcher.sh"
fi
