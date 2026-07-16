@echo off
REM DIAGNOSTIC BUILD - not the normal fullscreen mode. Forces the GL
REM viewport to start at (0,0) instead of centering it within the 640x480
REM display mode, to test whether this hardware's MiniGL driver mishandles
REM a non-origin viewport (suspected cause of fullscreen mode rendering as
REM two smaller side-by-side copies instead of one correct image). Content
REM will appear uncentered in the top-left corner - that's expected here,
REM not a bug. Report back whether the doubling is gone or still present.
REM
REM Console output is redirected to gl-fullscreen-test-out.log (normal
REM output) and gl-fullscreen-test-err.log (Java exceptions/stack traces)
REM next to this file, both overwritten each run.
java -mx256m -Dsun.java2d.noddraw=true -Dorsc.renderer=gl -Dorsc.gl.fullscreen=true -Dorsc.gl.fullscreen.width=640 -Dorsc.gl.fullscreen.height=480 -Dorsc.gl.fullscreen.zerooffset=true -jar Open_RSC_Client.jar > gl-fullscreen-test-out.log 2> gl-fullscreen-test-err.log
