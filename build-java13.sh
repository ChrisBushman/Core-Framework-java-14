#!/bin/bash
# Build the OpenRSC client targeting Java 1.3 using ECJ.
#
# Prerequisites:
#   java13/ecj.jar  — included in repo (reused from java14/)
#   java13/rt.jar   — NOT included; extract from J2SE 1.3.1 (see java13/README)
#
# Usage:
#   ./build-java13.sh          # build (compile target cleans first automatically)
#   ./build-java13.sh clean    # clean only

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

ant -f "$DIR/Client_Base/build.xml" \
    -lib "$ECJ" \
    -Djavac.source=1.3 \
    -Djavac.target=1.3 \
    -Dcompile.bootclasspath="$RT" \
    -Dcompile.compiler=org.eclipse.jdt.core.JDTCompilerAdapter \
    "${@:-compile}"
