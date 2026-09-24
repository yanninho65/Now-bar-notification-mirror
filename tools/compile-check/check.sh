#!/bin/bash
# Type-checks app/ (minus UI files) and wear/ with kotlinc against the real android.jar plus the
# hand-written stubs in ./stubs (androidx, Play Services wearable, coroutines) — for sessions with
# no Android SDK / no Google Maven access. Not a substitute for the CI build (resources, manifest
# merge, R.java and the real library signatures are NOT checked). Stubs are outside every Gradle
# source set, so they never ship.
#
# usage: tools/compile-check/check.sh [workdir]      (run from the repo root)
# Needs network to github.com releases + raw.githubusercontent.com (first run only).
set -e
REPO=$(pwd)
HERE=$(cd "$(dirname "$0")" && pwd)
W=${1:-/tmp/nbm-check}
mkdir -p "$W"
if [ ! -x "$W/kotlinc/bin/kotlinc" ]; then
  curl -sSL -o "$W/kotlinc.zip" https://github.com/JetBrains/kotlin/releases/download/v2.2.20/kotlin-compiler-2.2.20.zip
  (cd "$W" && unzip -q -o kotlinc.zip)
fi
if [ ! -s "$W/android-36.jar" ]; then
  curl -sSL -o "$W/android-36.jar" https://raw.githubusercontent.com/Sable/android-platforms/master/android-36/android.jar
fi
python3 "$HERE/genR.py" "$REPO/app/src/main/res" com.yann.nowbarmirror "$W/RApp.kt"
python3 "$HERE/genR.py" "$REPO/wear/src/main/res" com.yann.nowbarmirror.wear "$W/RWear.kt"
# UI files use appcompat/material/recyclerview/viewbinding — not stubbed, excluded.
APP=$(find "$REPO/app/src/main/java" -name '*.kt' | grep -v -E 'MainActivity|AppSelectionActivity|MessageAppsActivity|AppSelectionAdapter|SofascoreHomeAdapter|SportActivity|SettingsActivity|PermissionsActivity')
WEAR=$(find "$REPO/wear/src/main/kotlin" -name '*.kt')
set +e
"$W/kotlinc/bin/kotlinc" -nowarn -jvm-target 17 -cp "$W/android-36.jar" -d "$W/app.jar" "$HERE"/stubs/*.kt "$W/RApp.kt" $APP 2>&1 | grep -E "error:" > "$W/app.log"
"$W/kotlinc/bin/kotlinc" -nowarn -jvm-target 17 -cp "$W/android-36.jar" -d "$W/wear.jar" "$HERE"/stubs/*.kt "$W/RWear.kt" $WEAR 2>&1 | grep -E "error:" > "$W/wear.log"
echo "app errors: $(wc -l < "$W/app.log")  wear errors: $(wc -l < "$W/wear.log")"
cat "$W/app.log" "$W/wear.log"
