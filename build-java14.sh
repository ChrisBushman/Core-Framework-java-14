#!/bin/bash
# Build the OpenRSC client targeting Java 1.4 using ECJ.
#
# Prerequisites:
#   java14/ecj.jar  — included in repo
#   java14/rt.jar   — NOT included; extract from JDK 1.4.2 (see java14/README)
#
# Usage:
#   ./build-java14.sh          # build (compile target cleans first automatically)
#   ./build-java14.sh clean    # clean only

set -e

DIR=$(cd "$(dirname "$0")"; pwd)
ECJ="$DIR/java14/ecj.jar"
RT="$DIR/java14/rt.jar"

if [ ! -f "$RT" ]; then
  echo "ERROR: $RT not found."
  echo "See java14/README for instructions on obtaining rt.jar."
  exit 1
fi

export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
export PATH="$JAVA_HOME/bin:$PATH"

ant -f "$DIR/Client_Base/build.xml" \
    -lib "$ECJ" \
    -Djavac.source=1.4 \
    -Djavac.target=1.4 \
    -Dcompile.bootclasspath="$RT" \
    -Dcompile.compiler=org.eclipse.jdt.core.JDTCompilerAdapter \
    "${@:-compile}"
