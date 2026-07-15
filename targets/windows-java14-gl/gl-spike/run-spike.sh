#!/bin/bash
# Runs the Phase 0 GL spike (GLTest) under the real Java 1.4.2 JVM via Wine.
set -e

DIR=$(cd "$(dirname "$0")"; pwd)
cd "$DIR"

WINE_JDK="$HOME/.wine-java14/drive_c/j2sdk1.4.2_19"

WINEPREFIX="$HOME/.wine-java14" wine "$WINE_JDK/bin/java.exe" \
	-Djava.library.path=. \
	GLTest
