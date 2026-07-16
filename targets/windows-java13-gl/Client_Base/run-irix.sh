#!/bin/sh
# Run OpenRSC client on SGI IRIX (Java 1.4)
cd `dirname $0`
/usr/java2/bin/java -mx256m -Dsun.java2d.opengl=true -jar Open_RSC_Client.jar
