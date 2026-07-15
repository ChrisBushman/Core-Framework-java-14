#!/bin/bash
# Build the OpenRSC client targeting MRJ 2.2.5 (JDK 1.1.8 + Swing 1.1.1) using ECJ.
#
# Prerequisites (see mrj225/README):
#   java14/ecj.jar        — reused compiler (any of java13/java14's ecj.jar works)
#   mrj225/JDKClasses.zip — NOT included; MRJ 2.2.5's core class library
#   mrj225/MRJClasses.zip — NOT included; Apple's native AWT/sound layer
#                           (sun.audio.AudioPlayer lives here, not JDKClasses.zip)
#   mrj225/swing.jar      — NOT included; genuine Swing 1.1.1 FCS release
#   mrj225/mac.jar        — NOT included; Swing 1.1.1's Mac look-and-feel
#
# Usage:
#   ./build-mrj225.sh          # build (compile target cleans first automatically)
#   ./build-mrj225.sh clean    # clean only

set -e

DIR=$(cd "$(dirname "$0")"; pwd)
ECJ="$DIR/java14/ecj.jar"
RT="$DIR/mrj225/JDKClasses.zip"
MRJ="$DIR/mrj225/MRJClasses.zip"
SWING="$DIR/mrj225/swing.jar"
MAC="$DIR/mrj225/mac.jar"

for f in "$RT" "$MRJ" "$SWING" "$MAC"; do
  if [ ! -f "$f" ]; then
    echo "ERROR: $f not found."
    echo "See mrj225/README for instructions on obtaining it."
    exit 1
  fi
done

export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
export PATH="$JAVA_HOME/bin:$PATH"

ant -f "$DIR/targets/macos9-mrj225/Client_Base/build.xml" \
    -lib "$ECJ" \
    -Djavac.source=1.3 \
    -Djavac.target=1.1 \
    -Dcompile.bootclasspath="$RT:$MRJ" \
    -Dcompile.extraclasspath="$SWING:$MAC" \
    -Dcompile.compiler=org.eclipse.jdt.core.JDTCompilerAdapter \
    "${@:-compile}"
