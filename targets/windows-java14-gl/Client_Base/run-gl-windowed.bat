@echo off
REM Runs the experimental OpenGL/Voodoo2 (MiniGL) renderer in a normal
REM window - opens its own top-level window, not embedded in the usual
REM Swing UI (that window stays hidden - see README.md). See README.md for
REM other flags you can add below.
REM
REM Console output is redirected to gl-windowed-out.log (normal output)
REM and gl-windowed-err.log (Java exceptions/stack traces) next to this
REM file, both overwritten each run - easier to send along when reporting
REM an issue than a photo of the console. Two separate files, not the
REM Unix-style "2>&1" merge - Windows 98's COMMAND.COM doesn't understand
REM that file-descriptor-duplication syntax at all (it's an NT-family
REM cmd.exe feature) and silently mishandles it (confirmed: it created a
REM literal file named "&1" instead).
java -mx256m -Dsun.java2d.noddraw=true -Dorsc.renderer=gl -jar Open_RSC_Client.jar > gl-windowed-out.log 2> gl-windowed-err.log
