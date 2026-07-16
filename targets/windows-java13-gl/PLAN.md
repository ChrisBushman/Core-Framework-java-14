# OpenRSC Client — OpenGL/Voodoo2 Renderer — Java 1.3 port

This target is a straight port of `targets/windows-java14-gl` (see that
target's `PLAN.md` for the full design background, phase history, and all
the bugs/fixes that went into the GL renderer) onto the `targets/windows-java13`
source tree. No rendering-logic changes were made here — this is a build-
compatibility exercise, not a redesign.

## How it was built — 2026-07-16

`targets/windows-java13` (the existing Java 1.3 backport) and
`targets/windows-java14-gl` (the GL renderer work) both branched from
`targets/windows-java14` and touched an overlapping set of files
(`Scene.java`, `World.java`, `GraphicsController.java`, `mudclient.java`,
`ORSCApplet.java`, `OpenRSC.java`). Since neither the 1.3 backport's
changes nor the GL renderer's changes overlapped at the line level, a
3-way merge (`git merge-file`, base = `windows-java14`, mine =
`windows-java13`, theirs = `windows-java14-gl`) applied cleanly with zero
conflicts on all six files. The GL-only new files (`NativeGL.java`,
`GLSceneRenderer.java`, `SceneRenderer.java`, `native-gl/`) were copied
over verbatim — a grep of every line the GL work added or touched
confirmed none of it uses any Java-1.4-only API (`assert`, regex,
`LinkedHashMap`, `CharSequence`, `String.split`/`replaceAll`, NIO,
`StringBuilder`), so no source-level backporting was expected to be
needed there.

`NativeGL.dll` is the exact same binary `windows-java14-gl` already built
and tested — not rebuilt. JNI's native ABI doesn't depend on the Java
bytecode version, so the same 32-bit DLL loads and works identically under
a Java 1.3.1 JVM.

**Build errors found and fixed**: `ORSCApplet.pollGLInput()` (added by the
GL work's Phase 5, forwards native Win32 input events into synthetic AWT
events for the GL window) used several Java-1.4-only AWT additions that
the earlier "none of it uses 1.4-only APIs" grep pass had missed because
they're APIs, not language features covered by that grep's keyword list:
`MouseEvent.NOBUTTON`/`BUTTON1`/`BUTTON3` and the 9-arg `MouseEvent`
constructor (button param added in 1.4), plus the entire `MouseWheelEvent`
class (added in 1.4 - used for forwarding native mouse-wheel input).
Fixed the same way `targets/windows-java13`'s original backport
(`26b2e6657`) already handled the identical problem elsewhere in this same
file and in `ScaledWindow.mapMouseEvent()`: switched to the 8-arg
`MouseEvent` constructor (no button field) and encoded button state via
`modifiers | InputEvent.BUTTON1_MASK/BUTTON3_MASK` instead of a dedicated
button parameter; dropped `INPUT_MOUSE_WHEEL` handling entirely (falls
through to the existing `default: break;`), matching the base 1.3 client's
"no scroll-wheel support pre-1.4" behavior for the normal AWT/Swing input
path.

Verified: `./build-java13-gl.sh compile` succeeds (0 errors, 101
pre-existing warnings, same unused-import/variable noise as the base 1.3
build). Under `~/.wine-java13`'s real Java 1.3.1_28 JVM (via
`run-gl-wine.sh`, `-Dorsc.renderer=gl`), the client reached "Started
applet" → "Got server configs!" → a real login attempt ("login
response:4"), with `GLSceneRenderer`'s periodic profiling output showing
active frame rendering (`triVerts=22992`, sub-5ms per-frame costs) —
functionally equivalent to `windows-java14-gl`'s own verification.

## Real login/gameplay bug found and fixed — 2026-07-16

The build-level verification above only covered the handshake, not real
gameplay. First real interactive login (via the user actually playing
through `run-gl-wine.sh`, not an automated check) showed a blank teal/black
gradient after logging in - no 3D scene, no UI, just the GL clear color -
along with a stuck "user is already logged in" server error on a retry
(the server hadn't seen a clean logout from the earlier crashed process).

Root-caused via temporary diagnostic logging rather than guessed at: added
a `System.err.println` to `GraphicsController.copyPixelDataToSurface()`
(dumping `width2`/`height2`/`pixelData.length` and the call's arguments)
and another to `GraphicsController.resize()` (dumping the new width/height
plus a stack trace), rebuilt, and had the log-in reproduced once more with
these in place. That log showed the real 512x346 buffer working correctly
for the first region load, then - right before the crash - a
`GraphicsController.resize(56, 5)` call arriving via
`mudclient.reposition()`, shrinking the buffer out from under the running
game. The very next `World.generateLandscapeModel()` call (fixed
`copyPixelDataToSurface(MINIMAP, 0, 0, 285, 285)`, sized for the real
512x346 buffer) then threw `ArrayIndexOutOfBoundsException` reading past
the now-tiny `pixelData`, caught and logged as `orsc.util.RSRuntimeError`
every frame thereafter (not fatal, but nothing useful rendered again -
explaining the blank gradient).

Traced `56x17` (the pre-`-12` height adjustment `reposition()` does) back
to `ScaledWindow`/`ORSCApplet`: `OpenRSC.createAndShowGUI()` skips
`scaledWindow.launchScaledWindow()` for the GL renderer (see
`windows-java14-gl/PLAN.md`'s "Swing window hidden for the GL path"), but
that's also the *only* call that ever resizes `ScaledWindow` to its
correct, scalar-based dimensions - so it's left permanently stuck at
whatever tiny default size Swing's initial `pack()` assigned it (confirmed:
56x17). `ScaledWindow.resizeApplet()`/`validateAppletSize()` still fire
later regardless (`validateAppletSize()` specifically is called from a
`PacketHandler` server-config handler, so this reliably happens once per
real login) and were propagating that stale tiny size straight into
`mudclient.resizeWidth/Height`.

Fixed by gating `ScaledWindow.resizeApplet()`/`validateAppletSize()` and
`ORSCApplet.componentResized()` (a second, independent path writing the
same fields directly) to no-op when `-Dorsc.renderer=gl` is active - the
hidden Swing chrome's layout is meaningless noise in GL mode, where the
real game viewport size is owned by the GL window (`NativeGL`'s own
top-level Win32 window) instead. This is a GL-mode bug, not a Java-1.3-
specific one (identical code path exists in `windows-java14-gl`), so the
same fix was applied to both targets in the same session - see
`windows-java14-gl/PLAN.md`'s matching entry. Diagnostic prints removed
once confirmed fixed.

**Visually/functionally confirmed by the user**: logged in twice more
after the fix, both times stable through gameplay with no crash or
blank-scene regression ("looks stable to me").

## Fullscreen refresh-rate override + settle delay — 2026-07-16

Ported from `windows-java14-gl` the same session (see that target's
PLAN.md for the full writeup and the real-Voodoo-5 sync-loss investigation
that motivated it): `NativeGL.createContext()` gained a
`fullscreenRefreshHz` parameter (`-Dorsc.gl.fullscreen.refresh`, 0 =
match the desktop's current rate, same as before), a `Sleep(500)` settle
delay after a successful fullscreen mode switch, and a
`nativegl-fullscreen.log` diagnostic recording requested vs.
actually-applied `DEVMODE`. Native code and DLL are shared byte-for-byte
with `windows-java14-gl` (see "How it was built" above) - not re-derived
independently. Not yet re-verified on real hardware.

## Real Pentium MMX + Voodoo2 crash: CMOV instruction — 2026-07-16

User-tested this target on genuine period hardware (233MHz Pentium 1 MMX,
Windows 95, Voodoo2) - crashed on first fullscreen context creation with
`EXCEPTION_ILLEGAL_INSTRUCTION` inside `NativeGL.createContext`. Root
cause and fix are entirely in the shared native build - see
`windows-java14-gl/PLAN.md`'s matching entry for the full write-up
(short version: the mingw-w64 toolchain's default `-march=pentiumpro`
let GCC emit a `CMOV` instruction, which a real Pentium MMX doesn't have;
fixed with `-march=pentium-mmx` in `native-gl/build-nativegl.sh`, verified
via `objdump` showing zero `cmov`/`sse` instructions in the rebuilt DLL).
Not a Java-1.3-specific bug - this target only needed the rebuilt
`NativeGL.dll` copied over (done), no source changes here. Re-shipped to
`/Volumes/32GB/java-14-gl`. Not yet re-verified on the real hardware with
the fix in place.

## Game-loop/render-pipeline performance pass — 2026-07-16

Ported from `windows-java14-gl` the same session - see that target's
matching PLAN.md entry for the full write-up (bounding-box-limited sprite
depth readback, `GetPrimitiveArrayCritical` in `drawTriangles()`/
`readDepthRect()`, and pooled per-frame scratch buffers in `endScene()`/
`performPicking()`/`drawSpriteBillboards()`). `GLSceneRenderer.java` and
`native-gl/NativeGL.c` are identical between both targets - copied over
directly, not re-derived. Verified: `./build-java13-gl.sh compile`
succeeds (0 errors), regression-tested under `~/.wine-java13` - reached
"Got server configs!", no exceptions. Not yet re-verified on real
hardware.

## Real Pentium MMX + Voodoo2 fullscreen test: still windowed, and catastrophically slow — 2026-07-16

User-tested `run-gl-fullscreen.bat` (windowed both times, including with
an explicit `-Dorsc.gl.fullscreen.refresh=60`) on the real Pentium MMX +
Voodoo2 box - see `windows-java14-gl/PLAN.md`'s matching entry for the
full write-up (fullscreen silently staying windowed, `nativegl-fullscreen.log`
confirmed genuinely absent, ~0.6-0.7 FPS performance consistent with
Microsoft's software OpenGL fallback rather than the Voodoo2's real
MiniGL ICD, and a new `logGlInfo()` diagnostic added to `NativeGL.c` -
`nativegl-glinfo.log`, `GL_VENDOR`/`GL_RENDERER`/`GL_VERSION` straight
from the driver). Entirely native-code work, shared byte-for-byte with
`windows-java14-gl` - `NativeGL.c`/`NativeGL.dll` copied over, no source
changes needed in this target. Regression-tested under `~/.wine-java13` -
`nativegl-glinfo.log` written correctly. Next real-hardware test should
report what that file says.

## Explicit local OpenGL32.dll loading, tried before the system one — 2026-07-16

Ported from `windows-java14-gl` the same session - see that target's
matching PLAN.md entry for the full write-up (every `gl*()`/`wgl*()` call
converted from a static import to a function pointer, resolved by a new
`ensureOpenGLLoaded()` that tries `.\OpenGL32.dll` in the current
directory before falling back to the system one - lets a WickedGL/MesaFx/
etc. install be dropped next to the jar and take precedence
deterministically, useful for the ongoing real-hardware fullscreen
investigation above). Entirely native-code work, shared byte-for-byte
with `windows-java14-gl` - `NativeGL.c`/`build-nativegl.sh`/`NativeGL.dll`
copied over, no Java-side changes needed. Regression-tested under
`~/.wine-java13` - reached "Got server configs!", confirming the fallback
path still works. The actual local-DLL-preferred behavior is untested on
both Wine and real hardware so far - needs an actual replacement driver
DLL renamed to `OpenGL32.dll` to verify.
