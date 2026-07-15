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
# Usage: ./run-gl-wine.sh
# Prerequisites: built via ../../../build-java14-gl.sh, and the Wine
# prefix ~/.wine-java14 with a real Java 1.4.2_19 JVM installed (see
# PLAN.md).

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
    -jar Open_RSC_Client.jar "$@"
