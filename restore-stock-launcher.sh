#!/usr/bin/env bash
# Re-enable the previous / stock Android TV launcher and restore it as Home.
# Optionally uninstalls Astryx Rust Launcher ONLY AFTER confirming the stock launcher owns Home.
#
# Adapted from CleanYourTV's tools/restore.sh (MIT License, Gathin):
# https://github.com/Gathin23/cleanyourtv
set -euo pipefail
export LC_ALL=C
export MSYS_NO_PATHCONV=1

PKG="com.astryxtv.rustlauncher"
KNOWN_LAUNCHERS="com.google.android.apps.tv.launcherx com.google.android.tvlauncher com.google.android.leanbacklauncher com.mitv.tvhome com.gathin.tvhome"
HOME_QUERY='cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'

REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"

usage() {
  cat <<'EOF'
Usage: TV_HOST=<tv-ip> ./restore-stock-launcher.sh [options] [launcher.package]

Re-enable the stock (or previous) Android TV launcher and set it back as Home.
If no launcher package is given, uses the record saved by install.sh, or detects
the disabled stock launcher on the TV.

Options:
  --tv-host <ip[:port]>   TV IP address (or set TV_HOST env var)
  --launcher <package>    Specific launcher package to restore
  --uninstall             Also uninstall Astryx Rust Launcher after Home is restored
  -h, --help              Show this help and exit

Environment variables:
  TV_HOST                 TV IP address (or IP:port), e.g. 192.168.1.50
  TV_PORT                 Optional ADB port (default 5555)
  TV_ADB_BINARY           Optional path to native adb binary
  CLEANYOURTV_TOOLS       Optional path to an existing cleanyourtv/tools directory

Examples:
  TV_HOST=192.168.1.50 ./restore-stock-launcher.sh
  TV_HOST=192.168.1.50 ./restore-stock-launcher.sh --uninstall
  TV_HOST=192.168.1.50 ./restore-stock-launcher.sh com.google.android.apps.tv.launcherx
EOF
}

TARGET_LAUNCHER=""
DO_UNINSTALL=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help)
      usage
      exit 0 ;;
    --tv-host)
      [ "$#" -ge 2 ] || { echo "ERROR: --tv-host requires an IP address." >&2; exit 2; }
      TV_HOST="$2"; shift 2 ;;
    --launcher)
      [ "$#" -ge 2 ] || { echo "ERROR: --launcher requires a package name." >&2; exit 2; }
      TARGET_LAUNCHER="$2"; shift 2 ;;
    --uninstall)
      DO_UNINSTALL=1; shift ;;
    -*)
      echo "ERROR: unknown flag: $1" >&2
      echo "Run $0 --help for usage." >&2
      exit 2 ;;
    *)
      if [ -n "$TARGET_LAUNCHER" ]; then
        echo "ERROR: unexpected extra argument: $1" >&2
        exit 2
      fi
      TARGET_LAUNCHER="$1"; shift ;;
  esac
done

if [ -n "$TARGET_LAUNCHER" ]; then
  if ! printf '%s\n' "$TARGET_LAUNCHER" | grep -E '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$' >/dev/null; then
    echo "ERROR: invalid launcher package name: '$TARGET_LAUNCHER'" >&2
    exit 2
  fi
  if [ "$TARGET_LAUNCHER" = "$PKG" ]; then
    echo "ERROR: $PKG is Astryx Rust Launcher itself; pass the stock launcher package to restore." >&2
    exit 2
  fi
fi

if [ -z "${TV_HOST:-}" ]; then
  echo "ERROR: set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0" >&2
  exit 2
fi
export TV_HOST

cd "$REPO_ROOT"

native_dir() {
  (cd "$1" && (pwd -W 2>/dev/null || pwd))
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

TOOLS=""
PY=""
if [ -n "${CLEANYOURTV_TOOLS:-}" ]; then
  [ -f "$CLEANYOURTV_TOOLS/tv.py" ] || { echo "ERROR: CLEANYOURTV_TOOLS=$CLEANYOURTV_TOOLS has no tv.py." >&2; exit 1; }
  TOOLS="$(native_dir "$CLEANYOURTV_TOOLS")"
  PY="$(find_venv_python "$TOOLS" || true)"
elif PY="$(find_venv_python "$REPO_ROOT/tools" 2>/dev/null)"; then
  TOOLS="$(native_dir "$REPO_ROOT/tools")"
elif [ -f "$REPO_ROOT/../cleanyourtv/tools/tv.py" ] \
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

SAFE_HOST="$(printf '%s' "$TV_HOST" | tr -c 'A-Za-z0-9._-' '_')"
RECORD="$REPO_ROOT/tools/.cache/previous-home-${SAFE_HOST}.txt"

if [ -z "$TARGET_LAUNCHER" ] && [ -r "$RECORD" ]; then
  SAVED="$(tr -d '[:space:]' < "$RECORD")"
  if printf '%s\n' "$SAVED" | grep -E '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$' >/dev/null; then
    TARGET_LAUNCHER="$SAVED"
    echo "Using saved previous launcher from install record: $TARGET_LAUNCHER"
  fi
fi

if [ -z "$TARGET_LAUNCHER" ]; then
  DISABLED="$(tv 'pm list packages -d --user 0' 2>/dev/null | tr -d '\r' | sed -n -E 's/^package:([^[:space:]]+).*/\1/p' || true)"
  MATCHES=()
  for k in $KNOWN_LAUNCHERS; do
    if printf '%s\n' "$DISABLED" | grep -xF "$k" >/dev/null; then
      MATCHES+=("$k")
    fi
  done
  if [ "${#MATCHES[@]}" -eq 1 ]; then
    TARGET_LAUNCHER="${MATCHES[0]}"
    echo "Detected disabled launcher on TV: $TARGET_LAUNCHER"
  elif [ "${#MATCHES[@]}" -gt 1 ]; then
    echo "ERROR: multiple disabled launchers found on this TV:" >&2
    for m in "${MATCHES[@]}"; do echo "  $m" >&2; done
    echo "Pass the one you want to restore, e.g.:" >&2
    echo "  TV_HOST=$TV_HOST $0 ${MATCHES[0]}" >&2
    exit 1
  else
    # Nothing disabled — check if a known stock launcher is already enabled on the TV.
    ENABLED="$(tv 'pm list packages -e --user 0' 2>/dev/null | tr -d '\r' | sed -n -E 's/^package:([^[:space:]]+).*/\1/p' || true)"
    for k in $KNOWN_LAUNCHERS; do
      if printf '%s\n' "$ENABLED" | grep -xF "$k" >/dev/null; then
        MATCHES+=("$k")
      fi
    done
    if [ "${#MATCHES[@]}" -eq 1 ]; then
      TARGET_LAUNCHER="${MATCHES[0]}"
      echo "Found enabled stock launcher on TV: $TARGET_LAUNCHER"
    else
      echo "ERROR: could not automatically determine which launcher to restore." >&2
      echo "Pass the package name explicitly, e.g.:" >&2
      echo "  TV_HOST=$TV_HOST $0 com.google.android.apps.tv.launcherx" >&2
      echo "  TV_HOST=$TV_HOST $0 com.google.android.tvlauncher" >&2
      exit 1
    fi
  fi
fi

echo "Re-enabling $TARGET_LAUNCHER and restoring it as Home..."
tv "pm enable --user 0 $TARGET_LAUNCHER"

# Resolve exact Home component (required by set-home-activity on Android 9+).
COMP="$(tv "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $TARGET_LAUNCHER" \
  | tr -d '\r' | tail -n 1)"
if ! printf '%s\n' "$COMP" | grep -E "^${TARGET_LAUNCHER//./\\.}/[A-Za-z0-9_.\$]+$" >/dev/null; then
  echo "ERROR: enabled $TARGET_LAUNCHER, but could not resolve its HOME activity (got '${COMP:-nothing}')." >&2
  echo "Astryx Rust Launcher was NOT uninstalled." >&2
  exit 1
fi

tv "cmd package set-home-activity --user 0 $COMP"

# Verify Home actually switched back before considering uninstall.
NOW="$(tv "$HOME_QUERY" 2>/dev/null | tr -d '\r' | tail -n 1 || true)"
short_comp() {
  local p="${1%%/*}" a="${1#*/}"
  case "$1" in */"$p".*) a=".${a#"$p".}" ;; esac
  printf '%s\n' "$p/$a"
}

if [ "$(short_comp "$NOW")" != "$(short_comp "$COMP")" ]; then
  # On some builds with two launchers enabled, Android keeps the current Home until it's disabled/uninstalled,
  # OR refuses set-home-activity. Never blindly uninstall unless the target launcher is verified enabled & launchable!
  echo "WARNING: Home still resolves to '${NOW:-nothing}' instead of '$COMP'." >&2
  if [ "$DO_UNINSTALL" = "1" ]; then
    echo "Refusing to --uninstall $PKG automatically while Home has not switched to $TARGET_LAUNCHER." >&2
    echo "Press Home on the TV to verify $TARGET_LAUNCHER works, or run:" >&2
    echo "  TV_HOST=$TV_HOST $PY -I $TOOLS/tv.py 'am start -W -n $COMP'" >&2
  fi
  exit 1
fi

echo "Home now resolves to $NOW"
rm -f "$RECORD" 2>/dev/null || true

if [ "$DO_UNINSTALL" = "1" ]; then
  echo "Uninstalling Astryx Rust Launcher ($PKG)..."
  UNOUT="$(tv "pm uninstall --user 0 $PKG" 2>/dev/null || true)"
  echo "$UNOUT"
  case "$UNOUT" in
    *Success*) echo "Uninstalled $PKG." ;;
    *)
      echo "WARNING: could not uninstall $PKG cleanly (output above); Home is already back on $NOW." >&2
      exit 1 ;;
  esac
fi

echo "Restored. Press Home on the remote to return to $TARGET_LAUNCHER."
