#!/bin/bash
# Builds the Phase 0 GL spike:
#   1. Cross-compiles NativeGL.dll (32-bit Windows) from macOS using
#      i686-w64-mingw32-gcc, linking against the real Java 1.4.2 JNI headers
#      from the Wine-hosted JDK (so jni.h/jni_md.h match exactly what that
#      JVM expects).
#   2. Compiles NativeGL.java / GLTest.java with that same JDK's javac.exe
#      (run under Wine, since it's a Windows binary).
#
# Prerequisites: `brew install mingw-w64`, and the ~/.wine-java14 prefix
# with Java 1.4.2_19 installed (see java14/README / project memory for how
# that prefix was set up).

set -e

DIR=$(cd "$(dirname "$0")"; pwd)
cd "$DIR"

WINE_JDK="$HOME/.wine-java14/drive_c/j2sdk1.4.2_19"
CC=i686-w64-mingw32-gcc

if [ ! -d "$WINE_JDK" ]; then
	echo "ERROR: $WINE_JDK not found. Expected the Java 1.4.2 JDK installed"
	echo "under the ~/.wine-java14 Wine prefix."
	exit 1
fi

if ! command -v "$CC" >/dev/null 2>&1; then
	echo "ERROR: $CC not found. Install with: brew install mingw-w64"
	exit 1
fi

echo "== Cross-compiling NativeGL.dll =="
"$CC" -shared -O2 \
	-I"$WINE_JDK/include" -I"$WINE_JDK/include/win32" \
	-o NativeGL.dll NativeGL.c \
	-lopengl32 -lgdi32 -luser32 \
	-Wl,--kill-at
echo "Built NativeGL.dll"

echo "== Compiling Java sources with the Wine-hosted javac =="
wine "$WINE_JDK/bin/javac.exe" -source 1.4 -target 1.4 NativeGL.java GLTest.java

echo "== Done. Run with ./run-spike.sh =="
