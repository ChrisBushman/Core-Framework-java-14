#!/bin/bash
# Run the experimental OpenGL/Voodoo2 renderer under Wine, using a real
# Java 1.3.1_28 JVM (see ../../../MEMORY wine-java13-setup for how the
# prefix was created).
cd "$(dirname "$0")"
WINEPREFIX=~/.wine-java13 wine ~/.wine-java13/drive_c/jdk1.3.1_28/bin/java.exe \
    -mx256m -Dsun.java2d.noddraw=true -Dorsc.renderer=gl -jar Open_RSC_Client.jar
