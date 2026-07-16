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
# -nostdlib (no CRT startup object, no default libs) + our own DllMain as
# the real PE entry point (see NativeGL.c) + -ffreestanding/-fno-builtin
# (stop GCC from re-inserting memset/memcpy calls under -O2) together mean
# this DLL only ever imports from kernel32/user32/gdi32 - no opengl32
# (every gl*/wgl* call is resolved manually at runtime via LoadLibrary/
# GetProcAddress instead of a static import - see NativeGL.c's "Dynamic
# OpenGL loading" section for why: it lets a local OpenGL32.dll next to
# the jar be tried before the system one, deterministically, which a
# static import or a bare LoadLibraryA("opengl32.dll") call can't
# guarantee on every Windows version), no msvcrt.dll, no
# api-ms-win-crt-*.dll. That last part matters because
# mingw-w64's default CRT import library routes even "msvcrt"-mode builds
# through api-ms-win-crt-*.dll API-set forwarders that only exist on
# Windows 10+ - confirmed the hard way: a build without these flags loaded
# fine everywhere it was tested (Wine) but failed with
# `java.lang.UnsatisfiedLinkError: ... : One of the library files needed to
# run this application cannot be found` on a real Windows 98 machine, since
# Win98's loader has no concept of API sets at all. -fno-stack-protector/
# -fno-stack-clash-protection avoid pulling in libssp's stack-check helpers,
# which -nostdlib would otherwise leave unresolved. -lgcc is still linked
# explicitly (it's a static archive, not a DLL - no import-table cost) for
# any compiler-generated intrinsics (e.g. 64-bit divide helpers).
# -march=pentium-mmx (the real target: Voodoo2-era boxes go down to a
# genuine Pentium MMX) overrides this toolchain's default of
# -march=pentiumpro (P6 family - Pentium Pro/II/III). That default lets
# GCC emit CMOV under -O2, which a Pentium MMX (P5/i586 family) doesn't
# have - confirmed the hard way: EXCEPTION_ILLEGAL_INSTRUCTION on a real
# 233MHz Pentium MMX + Voodoo2 machine, and objdump showed the exact
# faulting PC was a `cmovle`. pentium-mmx also implies -mno-sse/-mno-sse2/
# etc (this CPU class predates SSE entirely, introduced with Pentium III).
"$CC" -shared -O2 -march=pentium-mmx -mtune=pentium-mmx \
	-ffreestanding -fno-builtin -fno-stack-protector -fno-stack-clash-protection \
	-nostdlib \
	-I"$WINE_JDK/include" -I"$WINE_JDK/include/win32" \
	-o "$CLIENT_BASE/NativeGL.dll" "$DIR/NativeGL.c" \
	-Wl,--entry=_DllMain@12 \
	-lkernel32 -lgdi32 -luser32 -lgcc \
	-Wl,--kill-at
echo "Built $CLIENT_BASE/NativeGL.dll"
