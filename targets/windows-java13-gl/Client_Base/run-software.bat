@echo off
REM Runs the original software (CPU) renderer - no GL/Voodoo2 involved at
REM all. Use this if you don't have (or don't want to use) a MiniGL-capable
REM 3D card. See README.md for other flags you can add below.
REM
REM Console output is redirected to software-out.log (normal output) and
REM software-err.log (Java exceptions/stack traces) next to this file,
REM both overwritten each run - easier to send along when reporting an
REM issue than a photo of the console. Two separate files, not the
REM Unix-style "2>&1" merge - Windows 98's COMMAND.COM doesn't understand
REM that file-descriptor-duplication syntax at all (it's an NT-family
REM cmd.exe feature) and silently mishandles it (confirmed: it created a
REM literal file named "&1" instead).
java -mx256m -Dsun.java2d.noddraw=true -jar Open_RSC_Client.jar > software-out.log 2> software-err.log
