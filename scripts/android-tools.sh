#!/usr/bin/env bash

# Shared Android SDK discovery for both Termux/ARM and conventional desktop CI.
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}"

# Termux SDK bootstrap installs under $PREFIX/tmp so it remains usable even when
# callers do not export Android-specific variables in every shell.
if [ ! -d "$SDK_ROOT" ] && [ -n "${PREFIX:-}" ] && [ -d "$PREFIX/tmp/android-sdk" ]; then
    SDK_ROOT="$PREFIX/tmp/android-sdk"
fi

if [ ! -d "$SDK_ROOT" ]; then
    echo "Android SDK not found. Set ANDROID_SDK_ROOT or ANDROID_HOME." >&2
    return 1 2>/dev/null || exit 1
fi

if [ -z "${ANDROID_JAR:-}" ]; then
    if [ -f "$SDK_ROOT/platforms/android-35/android.jar" ]; then
        ANDROID_JAR="$SDK_ROOT/platforms/android-35/android.jar"
    else
        ANDROID_JAR="$(find "$SDK_ROOT/platforms" -mindepth 2 -maxdepth 2 \
            -name android.jar -type f 2>/dev/null | sort -V | tail -n 1)"
    fi
fi

if [ ! -f "$ANDROID_JAR" ]; then
    echo "No Android platform android.jar found under $SDK_ROOT/platforms." >&2
    return 1 2>/dev/null || exit 1
fi

resolve_android_tool() {
    local name="$1"
    local candidate

    if [ -n "${PREFIX:-}" ]; then
        candidate="$PREFIX/bin/$name"
        if [ -x "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    fi

    candidate="$(command -v "$name" 2>/dev/null || true)"
    if [ -n "$candidate" ] && [ -x "$candidate" ]; then
        printf '%s\n' "$candidate"
        return 0
    fi

    if [ "$name" = "d8" ]; then
        candidate="$SDK_ROOT/cmdline-tools/latest/bin/d8"
        if [ -x "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    fi

    candidate="$(find "$SDK_ROOT/build-tools" -mindepth 2 -maxdepth 2 \
        -name "$name" -type f 2>/dev/null | sort -V | tail -n 1)"
    if [ -n "$candidate" ] && [ -x "$candidate" ]; then
        printf '%s\n' "$candidate"
        return 0
    fi

    echo "Required Android tool not found: $name" >&2
    return 1
}

AAPT2="${AAPT2:-$(resolve_android_tool aapt2)}"
D8="${D8:-$(resolve_android_tool d8)}"
ZIPALIGN="${ZIPALIGN:-$(resolve_android_tool zipalign)}"
APKSIGNER="${APKSIGNER:-$(resolve_android_tool apksigner)}"

export SDK_ROOT ANDROID_JAR AAPT2 D8 ZIPALIGN APKSIGNER
