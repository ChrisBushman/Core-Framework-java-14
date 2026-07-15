#!/bin/bash
# Cross-compiles NativeGL.dll (32-bit Windows) for orsc.graphics.gl.NativeGL,
# straight into Client_Base/ so it sits next to Open_RSC_Client.jar and is
# found via the default java.library.path when run from that directory
# (matching run-win98.bat's `java -jar Open_RSC_Client.jar` convention).
#
# Uses the real Java 1.4.2 JNI headers (jni.h/jni_md.h) from the
# Wine-hosted JDK so the ABI matches exactly what that JVM expects. See
# ../../gl-spike/build-spike.sh for the Phase 0 precedent this follows.

set -e

DIR=$(cd "$(dirname "$0")"; pwd)
CLIENT_BASE=$(cd "$DIR/.."; pwd)

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
	-o "$CLIENT_BASE/NativeGL.dll" "$DIR/NativeGL.c" \
	-lopengl32 -lgdi32 -luser32 \
	-Wl,--kill-at
echo "Built $CLIENT_BASE/NativeGL.dll"
