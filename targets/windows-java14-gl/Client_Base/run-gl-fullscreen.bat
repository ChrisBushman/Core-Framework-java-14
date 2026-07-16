@echo off
REM Runs the experimental OpenGL/Voodoo2 (MiniGL) renderer in real
REM exclusive-mode fullscreen, at the classic Voodoo2-era 640x480
REM resolution (letterboxed - the game's own 512x346 content is centered
REM within it, not stretched). Uses 640x480 rather than the game's native
REM resolution because that's a standard, universally-supported VGA mode -
REM confirmed on real hardware that requesting the native 512x346 as a
REM raw ChangeDisplaySettings mode gets silently rejected (falling back to
REM windowed) since it isn't a real, enumerated display mode. See
REM README.md for other flags you can add below.
REM
REM Console output is redirected to gl-fullscreen-out.log (normal output)
REM and gl-fullscreen-err.log (Java exceptions/stack traces) next to this
REM file, both overwritten each run - easier to send along when reporting
REM an issue than a photo of the console. Two separate files, not the
REM Unix-style "2>&1" merge - Windows 98's COMMAND.COM doesn't understand
REM that file-descriptor-duplication syntax at all (it's an NT-family
REM cmd.exe feature) and silently mishandles it (confirmed: it created a
REM literal file named "&1" instead).
java -mx256m -Dsun.java2d.noddraw=true -Dorsc.renderer=gl -Dorsc.gl.fullscreen=true -Dorsc.gl.fullscreen.width=640 -Dorsc.gl.fullscreen.height=480 -jar Open_RSC_Client.jar > gl-fullscreen-out.log 2> gl-fullscreen-err.log
