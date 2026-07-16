#!/bin/bash
# Run the GL-renderer OpenRSC client under Wine, on the real Java 1.4.2_19
# JVM, with the flag that actually activates the GL path.
#
# -Dorsc.renderer=gl does two things (see mudclient.getScene() and
# OpenRSC.createAndShowGUI(), both keyed off this same property):
#   1. Selects GLSceneRenderer instead of the plain software Scene.
#   2. Skips scaledWindow.launchScaledWindow(), so the old AWT/Swing
#      window never becomes visible - only the native GL window
#      (NativeGL.createContext(), titled "OpenRSC (GL, experimental)")
#      should appear. Without this flag, you get the OLD software
#      renderer AND the Swing window - which looks like "the wrong
#      client" (confirmed: that's exactly what omitting it produces).
#
# Usage: ./run-gl-wine.sh [-Dextra.flag=value ...]
# Prerequisites: built via ../../../build-java14-gl.sh, and the Wine
# prefix ~/.wine-java14 with a real Java 1.4.2_19 JVM installed (see
# PLAN.md).
#
# Any extra args (e.g. -Dorsc.fps=true) MUST be passed before -jar on the
# actual java command line, not after - everything after "-jar <file>" is
# handed to the app's own main(String[] args) as a plain argument, not
# parsed as a JVM flag at all, so a -D flag placed there is silently
# ignored (confirmed: this bit us once already, spent a whole test run
# with -Dorsc.fps=true having no effect for exactly this reason).

set -e

DIR=$(cd "$(dirname "$0")"; pwd)
cd "$DIR"

WINEPREFIX="${WINEPREFIX:-$HOME/.wine-java14}"
JAVA_EXE="$WINEPREFIX/drive_c/j2sdk1.4.2_19/bin/java.exe"

if [ ! -f "$JAVA_EXE" ]; then
  echo "ERROR: $JAVA_EXE not found. Expected a Java 1.4.2_19 JVM installed in $WINEPREFIX."
  exit 1
fi

# Kill any leftover instance from a previous run first, so a stale window
# can't be mistaken for this one.
pkill -f "j2sdk1.4.2_19/bin/java.exe" 2>/dev/null || true
sleep 1

WINEPREFIX="$WINEPREFIX" wine "$JAVA_EXE" \
    -mx256m \
    -Dsun.java2d.noddraw=true \
    -Dorsc.renderer=gl \
    "$@" \
    -jar Open_RSC_Client.jar
