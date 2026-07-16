#!/bin/bash
# Build the OpenRSC client (OpenGL/Voodoo2 renderer fork) targeting Java 1.3
# using ECJ. Same source/assets as targets/windows-java13, forked so the
# GL rendering work (ported from targets/windows-java14-gl, see that
# target's PLAN.md) can proceed without touching the working
# software-rendered java13 target.
#
# Prerequisites:
#   java13/ecj.jar  — included in repo (shared with build-java13.sh)
#   java13/rt.jar   — NOT included; extract from J2SE 1.3.1 (see java13/README)
#
# Usage:
#   ./build-java13-gl.sh          # build (compile target cleans first automatically)
#   ./build-java13-gl.sh clean    # clean only

set -e

DIR=$(cd "$(dirname "$0")"; pwd)
ECJ="$DIR/java13/ecj.jar"
RT="$DIR/java13/rt.jar"

if [ ! -f "$RT" ]; then
  echo "ERROR: $RT not found."
  echo "See java13/README for instructions on obtaining rt.jar."
  exit 1
fi

export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
export PATH="$JAVA_HOME/bin:$PATH"

ant -f "$DIR/targets/windows-java13-gl/Client_Base/build.xml" \
    -lib "$ECJ" \
    -Djavac.source=1.3 \
    -Djavac.target=1.3 \
    -Dcompile.bootclasspath="$RT" \
    -Dcompile.compiler=org.eclipse.jdt.core.JDTCompilerAdapter \
    "${@:-compile}"
