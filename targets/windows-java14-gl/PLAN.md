# OpenRSC Client — OpenGL/Voodoo2 Renderer — Build Plan

## Goal
Move as much of the Open RSC client's rendering as possible off the CPU
software rasterizer and onto a GPU path, targeting compatibility down to a
3dfx Voodoo2 (via its MiniGL `opengl32.dll` ICD, OpenGL 1.1 fixed-function
feature level). Game logic, networking, and asset formats are untouched;
only the rendering backend is replaced/extended.

This target (`targets/windows-java14-gl`) is a fork of `targets/windows-java14`
(same source, same Cache assets, same build.xml shape) so this work can
proceed without risking the working software-rendered java14/java13/MRJ225
targets. Build it the same way as java14, via the repo-root
`build-java14-gl.sh` (ECJ, `-source/-target 1.4`, `java14/rt.jar`
bootclasspath) — see that script for exact invocation.

## Current state (what we're replacing)
Everything renders into one CPU-side `int[] pixelData` ARGB buffer:

- `orsc.graphics.three` (`Scene.java` ~3000 lines, plus `Polygon`, `RSModel`,
  `Scanline`, `Shader`, `World`) is a full software 3D rasterizer: polygons
  are depth-sorted with explicit pairwise occlusion tests
  (`polygonHit1`/`polygonHit2` — a painter's-algorithm sort, no BSP tree, no
  Z-buffer), then each visible face is scanline-filled with texture/Gouraud
  interpolation directly into `pixelData`.
- `orsc.graphics.two` (`GraphicsController`, `MudClientGraphics`) blits
  sprites/text/UI into the same buffer with hand-written pixel loops — not
  AWT `Graphics` calls.
- `ORSCApplet.draw()` copies `pixelData` into a `BufferedImage`
  (`DataBufferInt`-backed) every frame; Swing (`ScaledWindow`) blits that to
  screen. `-Dsun.java2d.opengl=true` is already passed in `run-irix.sh`
  (and `-Dsun.java2d.noddraw=true` in `run-win98.bat`) — but that only
  affects Java2D's *own* blit of the finished frame, not the rasterizer
  that built it. The GPU currently does none of the actual scene rendering.

## Hard constraint driving the design
3dfx's Voodoo2 MiniGL ICD historically only supported **fullscreen-exclusive**
rendering — no windowed GL context. That's incompatible with today's
Swing/Applet chrome (`ScaledWindow`) unless the GL path becomes its own
fullscreen window rather than an embedded `Canvas`. Phase 0 exists to get
real signal on this before Phase 5 commits to an integration shape.

## Why hand-rolled JNI, not JOGL
Same reasoning as the prior `gl2d` effort (RealmSpeak on Irix/O2, see that
project's `gl2d/PLAN.md` for the full writeup): period-correct old JOGL
(1.1.x) binaries for 32-bit Windows are hard to source/trust today, and its
`GLCanvas`/AWT-peer integration assumes a windowed-GL model that MiniGL may
not support anyway. A small JNI wrapper around raw `opengl32.dll` (WGL) is
fully within our control, matches the existing NativeGL precedent, and
keeps the dependency footprint at zero — consistent with this project's
general approach (no Ant plugins, no big frameworks, ECJ + a bootclasspath
jar for the whole 1.4 build).

## OpenGL vs. Glide
Recommendation: **OpenGL (via MiniGL) first**. It's the more maintainable,
inspectable path, and the existing renderer's data (per-vertex position,
per-face texture id, per-vertex/face lighting) maps onto GL's fixed-function
pipeline fairly directly. Glide-native (`glide2x.dll` — `grDrawTriangle`,
`grLfbLock`, etc.) is historically the more robust/performant path on actual
Voodoo2 hardware and drivers, and stays on the table as a second backend
later if MiniGL proves too limited (no multitexture, buggy fogging, etc.)
in Phase 0/3 testing — but it is a genuinely separate API, not an
OpenGL variant, so it would roughly double the native-layer work.

## Dev/test environment
No physical Voodoo2 + period Windows box in this loop. Test matrix, weakest
to strongest signal:
1. **Wine on macOS** (`~/.wine-java14`, real Java 1.4.2_19 JVM, `i686-w64-mingw32-gcc`
   cross-compiled 32-bit DLL) — proves the JNI/WGL *plumbing* works at all
   (library loads, context creates, a triangle draws and swaps) under the
   exact old JVM we ship for. Wine's GL is a translation to the host's
   OpenGL, so this tells us nothing about MiniGL's fullscreen-exclusive
   restriction or its limited feature set specifically.
2. **dgVoodoo2** (Glide/MiniGL emulation over modern D3D/GL, on real or
   virtualized old Windows) — closer to real MiniGL behavior, and notably
   dgVoodoo2 *adds* windowed-mode support as an enhancement over original
   drivers, so a positive result here is encouraging but not proof the
   original hardware/driver combo behaves the same way.
3. **Real Voodoo2 + period OS** (real hardware or a Win98/2000 VM with a
   passed-through/emulated Voodoo2) — the only source of ground truth on
   the fullscreen-exclusive question and real MiniGL feature/bug set.
   Not required to make progress on Phases 1-4, but required before
   Phase 5 integration decisions are treated as final.

## Phased plan

**Phase 0 — Spike (this session).** Standalone JNI test, no AWT/Swing
involved: native code opens its own Win32 window + WGL context, draws one
immediate-mode triangle, swaps buffers, runs a short message loop. Proves
the mechanism end-to-end under Wine + real Java 1.4.2. Lives in
`gl-spike/`, outside `Client_Base/src` — it is not part of the game build.

**Phase 1 — Renderer abstraction. DONE — 2026-07-15.**
Added `orsc.graphics.three.SceneRenderer`: a mechanical extraction of every
method (and 4 fog fields, exposed as new setters, plus a `m_T` field read
exposed as `getSpriteBillboardModel()`) that `mudclient.java`/`World.java`/
`PacketHandler.java` actually call on `Scene` today — names and signatures
otherwise unchanged, including the obfuscation-leftover ones (`b(int)`,
`b(byte)`, `d(int,int)`, the two `setFrustum` overloads). `Scene` now
`implements SceneRenderer` with zero internal logic changes; two
package-private methods (`resourceToColor`, `removeAllGameObjects`) were
widened to `public` since interface methods must be public. `mudclient`'s
`scene` field, `getScene()`, and `World`'s `scene` field/constructor
parameter are now typed `SceneRenderer` instead of `Scene`; `new Scene(...)`
call sites are unchanged (a `Scene` instance still satisfies the interface).

Verified: `./build-java14-gl.sh compile` succeeds (0 errors, same 100
pre-existing warnings as before this change), and the rebuilt jar runs
under `~/.wine-java14`'s real Java 1.4.2 JVM exactly as before — window
creation, cache setup, "Started applet", and a real handshake with the
OpenRSC server ("Got server configs!"). Behaviorally transparent, as
intended: this phase only inserts a seam, it doesn't change what runs
through it.

Not done yet, deliberately deferred to Phase 3: `Scene` was *not* renamed
to `SoftwareSceneRenderer`. The interface + `implements` clause is the part
that unblocks a parallel `GLSceneRenderer`; renaming the concrete class is
a zero-risk cleanup that can happen anytime and isn't worth the extra diff
right now.

**Phase 2 — Texture upload. WORKING FIRST RESULT — 2026-07-15.**

Investigated first: this engine stores **no per-vertex UV data anywhere**
(checked `RSModel`, `Polygon` - neither has it). Texture mapping must
happen procedurally during `Scene`'s scanline fill, which isn't something
`GLSceneRenderer` reuses. So real per-vertex UVs aren't a "Phase 2 extracts
them" job - there's nothing to extract. `cornerUV()` in `GLSceneRenderer`
instead assigns each face's own corners a unit-square UV in insertion
order. That's an approximation, documented as such in the class doc
comment, but it's exact for the case it matters most for: axis-aligned
ground/floor tile quads inserted corner-by-corner in a consistent winding
- which is also the case flat single-pixel shading (Phase 3's interim
approach) looked worst for.

Native additions: `NativeGL.uploadTexture(ctx, width, height, byte[] rgba)`
(a real `glTexImage2D`, `GL_LINEAR` filtering, `GL_REPEAT` wrapping - point
sampling would be more period-accurate for Voodoo2 but bilinear is what
the hardware actually did by default) and `deleteTexture`. `drawTriangles`
gained a `texId` parameter and grew its per-vertex stride from 6 floats
(x,y,z,r,g,b) to 8 (+u,v); `texId == 0` disables texturing and draws flat
per-vertex color as before, unchanged from Phase 3.

`GLSceneRenderer` decodes each resource's stored palette + indexed bitmap
(already captured in Phase 1.5/3's stub for `resourceToColor`, now actually
used for more than one pixel) into a full RGBA byte[] and uploads it once,
lazily, the first time that resource id is actually drawn in a frame
(`ensureTextureUploaded()`); `endScene()` now does two passes per frame -
count vertices per bucket (flat-color, or per texture id), then fill
pre-sized arrays - so it can batch one `drawTriangles()` call per texture
instead of one per triangle or one per face. Faces whose `faceTextureFront`
is a real resource id get texture-mapped; faces where it's a negative
packed color (most scenery/furniture - genuinely not bitmaps) stay
flat-shaded, which is correct for them.

**Visually confirmed by the user**: stone brick wall texture and
wood-plank table texture both render correctly, tiling cleanly via
`GL_REPEAT`.

**Magenta artifact - root-caused and fixed, same day.** The roof section
that first showed a magenta/checkered pattern turned out to be neither a
missing-texture placeholder nor a dimension bug - it was a color-key
transparency mechanism Phase 2's first cut didn't replicate. `Scene`'s real
per-pixel texture decode (the private `b(int,boolean)` / `setFrustum(int,
byte)` routine, read back in Phase 1 but not carried forward into
`GLSceneRenderer`) masks every raw palette color with `0xF8F8FF` and
treats a masked result of exactly `0xF800FF` (magenta) as a sentinel
meaning "no pixel here" - it zeroes that texel and flags the whole texture
(`m_S`) so `Scene` picks a shader variant that skips those pixels when
rasterizing. `GLSceneRenderer` was uploading the raw palette color
straight through, so those sentinel texels rendered as opaque magenta
instead of being punched out.

Fix: added `GLSceneRenderer.decodeTexel()`, replicating that exact
mask/sentinel rule, used by both `resourceToColor()` (which had been
silently wrong the same way since Phase 1.5 - a latent bug this surfaced)
and `ensureTextureUploaded()` (which now uploads alpha 0 for sentinel
texels). `NativeGL.setPerspectiveFrustum()` gained `GL_ALPHA_TEST` /
`glAlphaFunc(GL_GREATER, 0.5)` to discard those fragments - alpha-test
rather than alpha-blend deliberately, since discarding needs no
back-to-front sort and stays correct under `GL_DEPTH_TEST`, matching
Scene's original "just don't draw this pixel" behavior more closely than
blending would. Also fixed in passing: `drawTriangles` used `glColor3f`
(which leaves alpha unchanged from whatever a previous call left it at) -
changed to `glColor4f(r,g,b,1.0f)` so vertex alpha is never stale.

Visually confirmed fixed: the same roof section now renders proper
reddish roof-tile texture instead of the checkerboard, everything else
unchanged.

Not done: no back-face culling, no per-vertex lighting/shading (Phase 3's
punch list still applies), and uploaded GL textures are never freed
(`deleteTexture` exists but nothing calls it yet) - fine for a session but
would leak VRAM over a long play session.

### GLSceneRenderer scaffold status: IN PROGRESS — 2026-07-15
A first `GLSceneRenderer implements SceneRenderer` exists
(`Client_Base/src/orsc/graphics/three/GLSceneRenderer.java`), built on a
promoted (non-spike) copy of the Phase 0 native bridge:
`orsc.graphics.gl.NativeGL` + `Client_Base/native-gl/NativeGL.c` (same
Win32/WGL approach, `beginFrame`/`endFrame` split out of the spike's single
`renderFrame()` so real geometry submission has somewhere to go in Phase 3).
Build with `Client_Base/native-gl/build-nativegl.sh` — it drops
`NativeGL.dll` straight into `Client_Base/` next to the jar.

Every interface method is implemented, but most are still stubs pending
later phases: `endScene()` opens its own native window on first call (lazy
init, same thread-affinity requirement as the spike), clears it, and swaps
- no model geometry is submitted yet, that's Phase 3. Picking
(`b(int)`/`b(byte)`/`getQB(byte)`) always reports zero hits, which callers
already handle correctly (they loop the reported count). `loadTexture`/
`resourceToColor` are *not* stubs, though - they store the real
palette+indexed-bitmap data `Scene.loadTexture` receives and decode
`resourceToColor`'s resource>=0 case exactly the way `Scene` does (first
palette-mapped pixel), so that data is both correct today and is what
Phase 2's real GL texture upload will consume.

Wired behind a runtime switch rather than replacing `Scene` outright: added
`mudclient.createSceneRenderer()`, which returns `new GLSceneRenderer(...)`
if `-Dorsc.renderer=gl` is set, else the existing `new Scene(...)`. Both of
the two `this.scene = new Scene(...)` call sites now go through this method.

Verified: `./build-java14-gl.sh compile` succeeds (0 errors). Under
`~/.wine-java14`, the default path (no property set) reaches "Got server
configs!" identically to before this change (regression check). With
`-Dorsc.renderer=gl`, the same run also reaches "Got server configs!",
stays alive afterward (CPU time climbing, no exceptions/crash in output),
and a second MoltenVK/Vulkan context-info dump appears in Wine's log output
*after* "Got server configs!" - consistent with (though not conclusive
proof of) `NativeGL.createContext()` firing successfully once asset
loading reaches scene construction, since an identical dump appears at
*window-creation* time in every run (Wine's GL-on-Metal translation logs
this once per new GL context).

**Visually confirmed by the user**: a second window does appear, showing a
black/near-black screen - exactly the expected result, since `beginFrame()`
clears to (0.05, 0.05, 0.08) and `endScene()` doesn't submit any model
geometry yet. The seam is confirmed end-to-end: mudclient ->
GLSceneRenderer -> NativeGL -> WGL -> a real presented frame.

**Phase 3 — Geometry submission. WORKING FIRST RESULT — 2026-07-15.**
`GLSceneRenderer.endScene()` now submits real model geometry:

- `setCamera()` replicates `Scene.setCamera()`'s trig verbatim (can't
  inherit it - `GLSceneRenderer` doesn't extend `Scene`) to produce the
  `rot1024_off_x/y/z` / `cameraProjX/Y/Z` values `RSModel.rotate1024()`
  needs.
- Each added model is transformed via `model.rotate1024(...)` -
  **reused as-is**, not reimplemented; it's the same package-private
  method `Scene` calls, doing the identical camera-space rotation.
- `RSModel.rotate1024()` has a built-in CPU frustum-cull check against
  `MiscFunctions`' static frustum-corner fields, which `Scene` populates
  every frame via its own frustum-corner projection. `GLSceneRenderer`
  instead forces the check to always pass (sets those statics to
  always-true bounds) and lets GL's own clipping/depth-test handle
  visibility - consistent with the plan's goal of moving this work to the
  GPU rather than duplicating `Scene`'s frustum-corner math.
- Faces are fan-triangulated (`indexCount` 3 or 4 → 1 or 2 triangles) and
  flat-shaded, one color per face via the already-real
  `resourceToColor(faceTextureFront)` - no textures yet (Phase 2), no
  per-vertex lighting, no back-face culling.
- All triangles for a frame are batched into one `float[]` and submitted
  via one `NativeGL.drawTriangles()` JNI call (not one call per triangle -
  JNI overhead would dominate otherwise). `NativeGL` gained
  `setPerspectiveFrustum()` (a real `glFrustum`, replacing the Phase
  0/1.5 placeholder ortho projection, with bounds derived to match
  `Scene`'s own screen-projection scale) and enables `GL_DEPTH_TEST` -
  this **is** the elimination of the CPU painter's-algorithm sort the plan
  called for; `Scene`'s sort code was never called at all on this path.

**Visually confirmed by the user**: a real, correctly proportioned and
oriented 3D scene renders - the OpenRSC character-creation room (long
table, chairs, ladder), camera angle and perspective looking right,
flat-shaded faces in plausible per-material colors (white tablecloth,
brown/orange wood). This is a first-result cut, not final: translucency
ordering (the risk flagged below), texturing, lighting, and back-face
handling are all still open. Anywhere the software renderer relied on
draw *order* for translucency (water, transparent overlays) will need
re-checking once textures/alpha are added, since order-independent
transparency isn't free on a depth-buffered fixed-function pipeline.

**Back-face culling + lighting - WORKING FIRST RESULT — 2026-07-15.**
Layered on top of Phase 3's geometry submission (not one of the original
six phases, an enhancement to Phase 3):

- **Back-face culling.** `NativeGL.setPerspectiveFrustum()` now also
  enables `GL_CULL_FACE`/`glCullFace(GL_BACK)`/`glFrontFace(GL_CCW)`. The
  winding convention was chosen by *reasoning*, not by reading a Scene
  winding check directly: `putVertex()`'s Y/Z negation is a proper
  rotation (determinant +1 - flipping exactly two of three axes), which
  preserves winding sense, so whatever winding the original engine used
  should still hold in GL screen-space. `glFrontFace` is a one-line flip
  if that reasoning turns out wrong on some other view.
- **Double-sided faces.** Faces with a real `faceTextureBack` (not
  `Scene.TRANSPARENT`) are genuinely double-sided in this engine (e.g. a
  single wall quad visible from both sides, sometimes with different
  textures per side). `GLSceneRenderer` emits such faces twice: once at
  normal winding with the front texture/color, once at reversed winding
  with the back texture/color. Whichever copy faces the camera survives
  culling - no per-face GL state toggling needed, and it stays compatible
  with batching triangles by texture across the whole frame (see
  `emitFaceSide()`).
- **Lighting** reuses `RSModel`'s own computed diffuse values
  (`faceDiffuseLight[]`/`vertDiffuseLight[]`/`vertLightOther[]`/
  `diffuseParam1`) rather than reimplementing the underlying normal/light
  math - not a style choice, a hard constraint: the raw inputs to that
  math (`faceNormX/Y/Z`, `diffuseDirX/Y/Z`, `diffuseMag`) are `private` on
  `RSModel`, not package-private, so they're genuinely inaccessible from
  `GLSceneRenderer` even though it's in the same package. `setFrustum()`'s
  6-arg overload (Scene's "relight models from index" call) now forwards
  to `model.setDiffuseLight(...)` with the exact argument order/constants
  Scene uses (including a literal `-115` and a `var4==0&&var6==0&&var1==0
  → var4=32` quirk, both copied faithfully). Scene itself turns these
  values into an index picking one of a handful of pre-darkened texture
  copies (the same `& 0xF8F8FF`-adjacent decode code read during the
  magenta-texture investigation); `GLSceneRenderer` instead treats them as
  a continuous brightness multiplier on GL's per-vertex color
  (`lightScalarToBrightness()`) - smoother than the original's discrete
  banding, and a better fit for GL's Gouraud interpolation than
  replicating the exact table lookup would be. The normalization constant
  (`LIGHT_NORM = 256`) is an estimate, not a derived value, since Scene's
  own scale factors depend on those same private fields - a visual-tuning
  candidate, same situation as the winding convention above. Faces where
  `faceDiffuseLight[f] != Scene.TRANSPARENT` get flat per-face brightness
  (mirrors Scene's own flat/Gouraud branch selection); otherwise each
  vertex gets its own brightness for real Gouraud shading.

**Visually confirmed by the user**: a large visual jump in one round - a
previously-black floor now renders as a lit wood-plank floor with a
visible directional highlight band, background trees/greenery that
weren't rendering before are now visible, and stone walls show a clear
light-to-shadow gradient. No inverted-geometry artifacts (missing walls,
inside-out surfaces) observed, so the guessed winding convention appears
correct on the first try - worth re-checking from other camera angles as
a cheap confirmation, but not blocking further work.

**Phase 4 — UI compositing. WORKING FIRST RESULT — 2026-07-15.**
`GraphicsController`'s 2D software output is left entirely as-is (chat,
inventory, minimap, login/character-creation screens - none of that
rendering code changed); it's uploaded as one texture and drawn as a
full-screen quad over whatever `GLSceneRenderer` drew to the frame.

Key integration point: `mudclient` draws the 2D UI *after* calling
`scene.endScene()` each frame (confirmed by grepping - `scene.endScene()`
and the next `clientPort.draw()` are ~400 lines apart, with UI-panel
drawing in between), and `clientPort.draw()` (implemented by `ORSCApplet`)
is the actual "frame is fully composited, present it" moment for every
game state, not just gameplay - it's what already blits `pixelData` to the
Swing window today. So `endScene()` no longer swaps buffers itself; a new
`GLSceneRenderer.presentUIOverlay(pixelData, width, height)` - deliberately
*not* added to the shared `SceneRenderer` interface, since `Scene` has no
equivalent need - is called from `ORSCApplet.draw()` (additively, alongside
the existing Swing blit code, not replacing it - which window to keep
showing is a Phase 5 decision) and does the actual composite-and-swap.
A `beginFrameDone` flag lets it work whether or not `endScene()` ran this
frame - pure-2D screens (login, etc.) get their own lazy `beginFrame()`
so they still render into the GL window rather than showing a stale 3D
frame.

`pixelData` has no real alpha channel (`DirectColorModel` with no alpha
mask), so pure black (`0x000000`) is treated as a color-key transparent
hole when building the RGBA upload - the same technique already used for
`Scene`'s real magenta color-key sentinel, applied here as a heuristic
rather than extracted ground truth (a UI pixel that's genuinely pure black
would incorrectly show the 3D scene through it). The UI texture is
uploaded once and updated in place every frame via a new
`NativeGL.updateTexture()` (`glTexSubImage2D`, no reallocation) rather than
creating a new GL texture object every frame, which would leak VRAM
continuously. The overlay quad itself temporarily swaps in an identity/
ortho projection and disables depth test and back-face culling for that
one draw call (`NativeGL.drawUIOverlay()`), then restores the perspective
projection via `glPushMatrix`/`glPopMatrix` for the next frame's 3D
geometry - and uses real alpha blending rather than the alpha-test cutout
3D textures use, since a single full-screen quad drawn last has no
per-triangle sort-order problem.

**Visually confirmed by the user**: the login/welcome screen (world map
thumbnail, "Welcome to RSC Cabbage" text, "New User"/"Existing User"
buttons) renders correctly in the GL window - right-side up, no
mirroring, confirming the texcoord mapping was correct on the first try.
Since this screen has no 3D scene yet, it's also a live confirmation of
the "`endScene()` didn't run this frame" fallback path. Not yet visually
confirmed: UI overlaid *together with* an actual 3D scene during gameplay
(e.g. the character-creation room from earlier phases) - worth checking
next, though nothing in the implementation suggests it wouldn't work.

**Phase 5 — Window integration. WORKING FIRST RESULT — 2026-07-15.**
Rather than embedding a GL `Canvas` in `ScaledWindow` or building a
fullscreen mode, the pragmatic first cut made the native GL window (which
already existed since Phase 0, display-only) genuinely interactive - it
still exists *alongside* the original Swing window (both stayed up), but
can now be used entirely on its own.

- **Input capture**: `NativeGL.c`'s `WndProc` now handles
  `WM_MOUSEMOVE`/`WM_L`/`RBUTTONDOWN`/`UP`/`WM_KEYDOWN`/`UP`/`WM_CHAR`,
  pushing them into a small per-context ring buffer. A new
  `NativeGL.pollInputEvent()` drains it, one event at a time.
- **Dispatch**: `ORSCApplet.pollGLInput()` (called from `draw()`, right
  after `presentUIOverlay()`) converts each polled event into a synthetic
  `java.awt.event.MouseEvent`/`KeyEvent` and calls the *exact same*
  `MouseHandler`/`KeyHandler` inner-class methods `ScaledWindow`'s real AWT
  listeners already call (`getMouseHandler()`/`getKeyHandler()`) - none of
  mudclient's actual input-handling logic was touched or reimplemented.
  Investigated `KeyHandler.keyPressed()` first: it reads both `getKeyCode()`
  and `getKeyChar()` from *one* event, but WM_KEYDOWN (code) and WM_CHAR
  (char) are separate Win32 messages. Checked every branch in that method
  before assuming a fix was needed - none check keyCode and keyChar
  together, so two independent synthetic `keyPressed` calls (one per raw
  message, with the other field set to its proper AWT "undefined" sentinel)
  work correctly with no event-correlation logic needed.
- **Coordinate compensation**: `MouseHandler.mousePressed/Released/Dragged`
  subtract `mudclient.screenOffsetX/Y` from event coordinates (real AWT
  embeddings can offset the game viewport within a larger canvas). This
  native window has no such offset, so `pollGLInput()` adds it back to
  cancel the subtraction out.

**Bug found via user testing, root-caused and fixed same session**: button
clicks registered only near a button's bottom-right corner, never its
visual position. Added temporary native (`fprintf`) and Java
(`System.out.println`) logging to compare raw coordinates against
expected button positions rather than guessing - this showed coordinates
were in-range (not a scale-factor bug) but the interactive area was
compressed relative to the rendered content. Root cause:
`CreateWindowEx`'s width/height parameters specify the *entire* window
rect including the title bar and borders, not the client (drawable) area
- so the window's actual client area was smaller than the `width2 x
height2` both `glViewport` and this engine's button hit-testing assume,
compressing rendered content into less space than its own coordinates
expect. Fixed with `AdjustWindowRect` (computes the outer size needed so
the client area ends up exactly `width2 x height2`) - the standard Win32
fix for exactly this class of bug. Debug logging removed once confirmed
fixed.

**Visually/functionally confirmed by the user**: logged in, navigated the
settings menu, and logged out - entirely through the GL window, no
interaction with the Swing window needed.

Deliberately out of scope for this first cut (noted in
`ORSCApplet.pollGLInput()`'s doc comment): modifiers beyond shift/control
(no alt/meta), `mouseClicked`/`mouseEntered`/`mouseExited`/
`mouseWheelMoved` aren't forwarded, and `mouseMoved` vs `mouseDragged` is
selected only by current button-down state rather than a full drag
gesture. Still untested on anything but Wine's translation layer: the
original fullscreen-exclusive MiniGL question this phase was originally
framed around.

### Swing window hidden for the GL path — 2026-07-15
`OpenRSC.createAndShowGUI()` now skips `scaledWindow.launchScaledWindow()`
(the only call that actually does `setVisible(true)`) when
`-Dorsc.renderer=gl` is set. `ScaledWindow` still gets constructed either
way - other code (`ORSCApplet.draw()`) calls `setGameImage()` on it every
frame regardless of renderer, so the object needs to exist even though
nothing ever shows it.

That raised a real follow-up question: is keeping that Swing pipeline
alive (just invisible) actually free? Checked rather than assumed -
`setGameImage()` does a real `Graphics2D.drawImage()` blit every call
regardless of window visibility (Swing's invisibility check only skips
the final on-screen *paint*, not this upstream copy), and `draw()` did a
`System.arraycopy()` of the entire `pixelData` buffer into `game_image`
immediately before it. Both were unconditional - genuine wasted per-frame
work when GL is active, on top of the separate copy of that same
`pixelData` `presentUIOverlay()` already does for the GL texture upload.
Both are now skipped entirely when the GL renderer is active (`draw()`
computes `glActive` once and gates the whole Swing-image-update block on
it), rather than just leaving them running against an invisible window.

Verified: default (non-GL) path regression-checked - unaffected, still
shows its window and reaches "Got server configs!" normally. GL path
retested end-to-end after this change: logged in, reached in-game chat
("Welcome to RSC Cabbage!", a real chat message received) - no crash, no
Swing window shown, single-window operation confirmed working.

### Sprite billboards + FOV/zoom fix — 2026-07-15, from user feedback
User-reported feedback after playing with the GL renderer, investigated
rather than guessed at:

- **"Cannot find my character."** Traced `drawPlayer()`/`drawNPC()`:
  characters in this engine are 2D sprite billboards, not 3D models -
  they call `getSurface().drawSpriteClipping()` (the 2D layer) directly.
  That call only happens from inside `Scene.endScene()`'s processing of
  sprites registered via `Scene.drawSprite()`, which projects each
  sprite's world position through the camera to get on-screen size/
  position before calling `graphics.drawEntity()`. `GLSceneRenderer.
  drawSprite()` had been a complete no-op stub since Phase 1.5 - so
  players, NPCs, projectiles, and items were 100% invisible, not just
  small. Fixed by implementing `drawSprite()`/`drawSpriteBillboards()`,
  reusing `RSModel.rotate1024()` again (a second, dedicated
  `spriteBillboardModel`, mirroring Scene's `m_T`) and replicating
  Scene's exact projection math for the final `graphics.drawEntity()`
  call - confirmed parameter-by-parameter against both Scene's source
  and a real call site in mudclient, not assumed from either alone.
  Downstream drawing code (`drawPlayer`/`drawNPC`/etc.) is completely
  unchanged.
- **"Camera is zoomed out significantly more than the original."**
  Root cause found while implementing the above: `Scene.setMidpoints()`
  (which `GLSceneRenderer` had also stubbed out) sets `rot1024_vp_src`
  from `mudclient.m_qd`, which defaults to **9** - not the `8` this
  renderer had hardcoded since Phase 3 (copied from `Scene`'s
  *constructor* default without noticing `setMidpoints()` overwrites it
  immediately after). That value directly sets the glFrustum field of
  view (see `ensureContext()`), so the mismatch wasn't cosmetic or
  sprite-specific - it affected the whole 3D scene's zoom level the
  entire time. Fixed by implementing `setMidpoints()` for real.
- **Crash found via testing, fixed same session**: the first frame any
  sprite was drawn, the game crashed (`ArrayIndexOutOfBoundsException`
  in `RSModel.computeNormals()`, thrown repeatedly until the window
  closed). Root cause: `computeNormals()` unconditionally assumes every
  face has 3+ vertices; sprite "poles" only have 2 (base, top).
  `RSModel`'s public 2-arg constructor (which `spriteBillboardModel` used
  initially, matching `Scene`'s own `new RSModel(var4*2, var4)` call)
  leaves the two flags that gate `computeNormals()`'s body
  (`m_c`/`dontComputeDiffuse`) both false, so it ran anyway and crashed -
  exactly wrong for a model that only ever needs rotate1024()'s rotation
  step, never normals/lighting. Fixed by switching to `RSModel`'s
  package-private 7-arg constructor, which can set both flags directly
  (the other three flags passed as `false` to match what the 2-arg
  constructor already left them at, so nothing else changes).

**Visually confirmed by the user**: characters, other players, and NPCs
now render correctly (labeled, correctly positioned, legible), and the
zoom/scale now looks right - not "zoomed out" anymore. Still open, called
out by the user in the same round: the login-screen "carousel" of images
still doesn't show (this turned out *not* to share a root cause with the
sprite-billboard fix - unconfirmed root cause, lower priority, cosmetic/
login-only), and mouse click-to-move still doesn't work (this is exactly
the picking stub called out as a known gap since Phase 1.5 - `b(int)`/
`b(byte)`/`getQB(byte)` always report zero hits - now the main blocker for
actually playing, and the natural next thing to prioritize).

### Real picking (click-to-move) — 2026-07-15
Implemented `b(int)`/`b(byte)`/`getQB(byte)` for real, via a GL color-ID
render pass rather than a port of `Scene`'s scanline-edge hit test (which
is a large, intricate part of `Scene.java` - reusing the GPU's own
rasterizer/depth-test to answer "what's under the mouse" fits this
project's direction better than duplicating that CPU algorithm a second
time). Every visible world/scenery face is redrawn once more with a flat
color encoding its index into a per-frame `pickFaceRefs` list, restricted
to a tiny `GL_SCISSOR_TEST` box around the mouse (see `performPicking()`),
depth-tested against the real pass's already-written depth buffer (not
recomputed), then one pixel is read back and decoded. ID colors use 5
bits/channel, not 8, because this context's pixel format is 16-bit color
(a deliberate Voodoo2-era choice from Phase 0) - an 8-bit encoding would
get quantized on write and decode wrong on readback. `setMidpoints()`
(already implemented for the zoom fix above) supplied `m_qd`; this needed
`Scene.setMouseLoc()`'s real semantics confirmed too (`m_j = x - m_Zb`,
i.e. mudclient's mouse coordinates are top-left-origin, matching this
renderer's own convention - no rescaling needed, unlike `Scene`'s internal
centered coordinate space). Always reports 0 or 1 hits (never `Scene`'s
up to 100 - a GL depth-tested readback can only ever report whichever
single face actually won at that pixel), and world/scenery geometry only
- sprite billboards (players/NPCs/items) aren't pickable yet, scoped out
since this round targeted click-to-*move* specifically.

**Crash found via user testing, root-caused and fixed same session**:
`NullPointerException`/`facePickIndex` mismatch in `mudclient.drawUiTab0()`
shortly after moving. Root cause: the ID-color pass didn't clear the
scissored region first, so any pixel not covered by an ID triangle kept
whatever color the real pass had already drawn there - which could
coincidentally decode to a valid-*looking* but wrong id, handing mudclient
a real model paired with the wrong face index. Fixed at the source
(`setPickScissor` now clears the scissored region to black - the same
value the "no hit" sentinel already reserves - before the ID pass draws),
plus added defensive bounds-checking in the Java decode as a second layer,
since a crash this many call-frames away from its actual cause (native
render call → decode → `b()`/`getQB()` → mudclient, several frames later)
is expensive to re-diagnose if anything else slips through.

**User-reported after this round**: real click-to-move confirmed working,
but the client crashed shortly after moving, and performance is
"incredibly slow." The crash is the color-collision bug described above
(root-caused and fixed same session). Performance not fully investigated
yet, but one real, understood contributor was fixed immediately since its
cost was already precisely known (not speculative): picking's restore
step was redrawing the *entire visible scene's geometry* a second time
every frame just to undo the ID-color pass - as much GPU vertex work as
the real render pass itself, on top of the ID pass's own equally-full
resubmission. Replaced with `NativeGL.restorePickPixels()`: `setPickScissor()`
now saves the scissored region's original pixels (via `glReadPixels`)
before clearing them for the ID pass, and after reading the pick result,
those saved pixels are blitted back with a tiny `glDrawPixels` - cost
proportional to the (3x3) scissor region, not scene complexity. `glRasterPos`
(what `glDrawPixels` anchors on) is transformed by the current modelview/
projection like any vertex, so restorePickPixels temporarily swaps in a
pixel-space ortho first (`glOrtho(0, width, 0, height, -1, 1)`), mirroring
`drawUIOverlay()`'s existing push/pop pattern, so plain window coordinates
work. This cuts picking's per-frame geometry resubmission from 3x (real +
ID + restore) down to 2x (real + ID) - a real reduction, but not a full
performance investigation. Whether the remaining 2x is still the dominant
cost, or whether the original immediate-mode-GL/per-frame-allocation
concerns from Phase 3/4 matter more, is still an open question a real
profiling pass should answer.

### Real profiling pass + vertex arrays — 2026-07-15
Requested explicitly ("let's do a proper profiling pass") rather than
continuing to guess at performance. Added checkpoint-based instrumentation
(`System.currentTimeMillis()` around each phase of `endScene()`/
`performPicking()`/`presentUIOverlay()`, averaged and printed every 30
frames, see `reportProfile()`) and had the user walk around normally to
collect real numbers. Findings: `draw` (submitting real geometry) and
`pick.gl` (the ID-color redraw) were costing 300-400ms/frame each for
~93-99k vertices - overwhelmingly dominant, and the actual cause of
"incredibly slow" from the picking round, not the picking-restore
redundancy fixed back then. Root cause: `NativeGL.drawTriangles()` was
still immediate-mode (`glBegin`/`glColor4f`/`glTexCoord2f`/`glVertex3f` per
vertex) - each call crosses Wine's OpenGL-on-Metal translation layer
separately, so hundreds of thousands of per-vertex calls every frame were
each paying that cost independently. Converted to vertex arrays
(`glVertexPointer`/`glColorPointer`/`glTexCoordPointer` + one
`glDrawArrays` call per texture per frame) - still core OpenGL 1.1, still
Voodoo2/MiniGL-compatible, just batched. Confirmed via re-profiling: `draw`
and `pick.gl` both dropped to single-digit milliseconds (~2.5-8ms). User
confirmed: "Performance was MUCH better."

### Sprite transparency - the black-clothing saga — 2026-07-15
Same round of testing that confirmed the performance fix also surfaced a
transparency bug: `presentUIOverlay()` color-keys pure black (`0x000000`)
as "transparent, show the 3D scene through here", since `pixelData` (the
2D CPU compositing buffer sprites and UI share) has no real alpha channel.
This engine's clothing/hair palette legitimately includes black, so
characters wearing it rendered see-through. Explicitly prioritized over a
separately-noticed wall-occlusion gap ("fix the black-clothing issue
now"). Took four real iterations, each one tested by the user and each
falling short in a specific, informative way - worth recording all of
them, since the working design only makes sense in light of what didn't:

1. **Pre-fill each sprite's rect with a sentinel color immediately before
   drawing it, one sprite at a time.** Destructive: a later sprite's fill
   could erase an earlier sprite's (or a nametag's) already-drawn pixels
   if their rects overlapped - confirmed by the user seeing exactly that
   ("The box around the man sprite also covers other rendering, like my
   character name and other characters").
2. **Diff each rect's pixels before/after drawing, mark only changed
   pixels opaque.** Fixed the destructive erasure, but didn't fix the
   original bug at all: the 3D-viewport region of `pixelData` is never
   touched by anything except sprites (the real 3D terrain is GL-rendered
   separately, never composited into `pixelData`), so for a character
   standing alone, "before" and "after" are both black for any black
   clothing pixel - the diff sees no change and wrongly calls it a gap.
   Confirmed still see-through.
3. **Two full passes (fill-then-draw) using a magenta sentinel
   (`0xF800FF`, the same "no pixel here" color-key `Scene`'s own texture
   decode already uses - see `decodeTexel()`), checked globally in
   `presentUIOverlay()`.** Fixed destructive erasure for good, but the
   global, unscoped check collided with unrelated content: any UI sprite
   whose own art legitimately contains `0xF800FF` (confirmed case: a
   settings-panel icon) got wrongly hidden, and the reverse also
   surfaced - real sprite content sharing that value got wrongly treated
   as a gap.
4. **`spriteExpectedColor[]` (per-pixel, int, `-1` = untouched) - the
   design that held up.** Instead of a global color check, or even a
   plain touched/untouched boolean, this records the *exact* color sprite
   compositing left at each pixel this frame. `presentUIOverlay()` only
   applies the magenta-sentinel rule where `pixelData` still matches what
   was recorded; anywhere else (including that settings icon, and
   anywhere a later 2D draw call - a dialog, a panel - painted over a
   former sprite pixel) it falls back to the original plain black-key
   rule, exactly as if no sprite had ever been there. A boolean-only
   version was tried in between and wasn't enough on its own - see the
   welcome-dialog case below for why a recorded *color*, not just a flag,
   turned out to matter.

Two bugs found via continued user testing after this design landed,
both fixed in the same session:

- **Welcome-dialog black box.** The "Welcome to RSC" dialog draws its own
  solid black background (`drawBox(..., 0)`) *after* `endScene()` - if
  that background happened to land on a pixel a sprite's rect touched
  earlier the same frame, the plain boolean touched-flag couldn't tell
  "still a sprite gap" apart from "dialog legitimately painted black
  here", and kept treating it as sprite-owned - checking the sentinel
  rule instead of the plain black rule, making the dialog's own black
  background opaque instead of transparent. Fixed by switching the mask
  to `spriteExpectedColor[]`: since `pixelData` no longer matches what
  was recorded (a fresh black overwrite, not the sprite's own leftover
  value), the pixel correctly falls back to the plain rule.
- **Settings-panel magenta box, blended.** Even after the above,
  translucent 2D panels (`GraphicsController.drawBoxAlpha()`, used for the
  settings/social panel's background) *blend* their tint against whatever
  raw color already sits in `pixelData`, with no idea any sentinel
  convention exists. Blended against magenta specifically (gray `181` at
  `160/256` alpha), the arithmetic works out to roughly `(206,113,208)` -
  a color that's neither pure black nor pure magenta, so nothing
  recognizes it as transparent, leaving a permanently opaque mauve patch
  shaped exactly like the sprite's rect (confirmed by the user: "the
  magenta box is a consistent size around character sprites", real sprite
  content blending fine since blending gray with a normal skin tone just
  produces another normal-looking color). Fixed by converting any pixel
  still exactly the sentinel to plain black *before* any later 2D drawing
  can blend against it, right when sprite processing finishes each frame
  - `spriteExpectedColor` still records the true `0xF800FF` gap marker for
  `presentUIOverlay`'s own check, but the *visible* `pixelData` value
  becomes black, the one color every other 2D routine in this engine
  already understands and blends against sanely. First version of this
  fix folded the conversion into the same per-sprite occlusion pass and
  regressed immediately: two overlapping sprites' rects sharing a gap
  pixel would convert-then-misrecord each other's work depending on
  iteration order, producing a stray opaque black box in the overlap
  (confirmed by the user, isolated to exactly that scenario). Fixed by
  splitting it into its own fourth pass (`finalizeGapColor()`), run only
  after every sprite has finished recording its own `spriteExpectedColor`
  - by then whichever sprite actually "wins" a shared pixel (the one drawn
  last, matching normal painter's-order stacking) has already settled it
  to a stable final value.

### Wall occlusion (sprites vs. 3D geometry) — 2026-07-15
Requested next ("tackle wall occlusion next"): sprites are 2D pixels
composited over the whole GL-rendered frame with no depth comparison of
their own, so a character always drew in front of a wall behind them,
regardless of which was actually closer to the camera. Fixed by reading
back the real geometry's depth buffer for each sprite's screen rect
(`NativeGL.readDepthRect()`, new native function, modeled on the existing
picking scissor/readback code) right after `endScene()`'s real
`NativeGL.drawTriangles()` calls (moved `drawSpriteBillboards()` to run
*after* those calls rather than before, specifically so the depth buffer
actually holds this frame's real wall/scenery depths by the time sprites
read it), converting the non-linear window-space depth back to a linear
eye-space distance with the standard `glFrustum` near/far un-projection
formula, and comparing against the sprite's own depth (`RSModel.vertZRot`,
already in the same unit `putVertex()` feeds to GL). Any pixel where real
geometry is nearer gets reset to the fill sentinel, so it reads as
transparent through the exact same mechanism as an undrawn sprite gap - no
new transparency mechanism needed.

One refinement needed after user testing: an early version used a single
flat depth (the sprite's base/feet vertex) for the whole rect, which
chewed a visible notch out of characters' heads specifically near wall
corners. Root cause: this camera has real pitch, so a standing character's
head is meaningfully nearer to (or farther from) the camera than their
feet - not the same depth - and a nearby wall corner's own depth was
landing *between* the two, misjudging the head region specifically. Fixed
by interpolating the comparison depth linearly per row between the
sprite's top and base vertex depths, rather than one constant for the
whole billboard. Confirmed by the user afterward: "wall corners look
good."

**Follow-up, same session**: sprites weren't depth-tested against *each
other*, only against real 3D geometry - two overlapping character sprites
were drawn in whatever order they were registered that frame (matching
`Scene`'s own `m_T` processing order), not sorted by which was actually
closer to the camera, so a character standing behind the player still
rendered in front of them. Fixed with a simple painter's-algorithm sort:
`drawSpriteBillboards()` now sorts sprite indices by `baseDepths` (a plain
insertion sort - counts are small, the sprites visible on screen at once,
not a scene-wide total) and draws/occludes farthest-first, so nearer
sprites correctly overwrite farther ones in the draw pass, and the nearer
sprite's occlusion pass "wins" any pixel they share for the same reason
`finalizeGapColor()` already needed to run after all sprites, not per-
sprite. Confirmed fixed by the user afterward.

### Login-screen carousel — 2026-07-15
**Bug**: the login screen showed a corrupted-looking cropped minimap
fragment instead of the expected rotating background carousel. Root
cause: `mudclient.renderLoginScreenViewports()` positions the camera at
three fixed scenic viewpoints, calls `scene.endScene(...)` for each, then
snapshots the "rendered" frame via `GraphicsController.storeSpriteVert()`
into `spriteVerts[0..2]` (later cross-faded by `drawLogin()`) -
`storeSpriteVert()` reads directly from `pixelData`. That's correct for
the plain software `Scene`, whose `endScene()` rasterizes straight into
`pixelData` - but `GLSceneRenderer.endScene()` submits geometry to its own
native GL context instead and never touches `pixelData` at all, so the
snapshot just captured whatever stale content happened to already be
sitting in that shared buffer (e.g. leftover minimap pixels from a
previous in-game session).

Fixed by adding `captureFrameToPixelData()` to the `SceneRenderer`
interface: a no-op on `Scene` (nothing to pull back, it already writes
`pixelData` directly), and on `GLSceneRenderer` a new native
`NativeGL.readColorRect()` call (a plain `glReadPixels` against the back
buffer `endScene()`'s own `drawTriangles()` calls just filled - not a
redraw) that copies the just-rendered frame back into `pixelData`, row-
flipped the same way `occludeAgainstDepthBuffer()` already has to for
depth reads. `renderLoginScreenViewports()` now calls
`scene.captureFrameToPixelData()` right after each `endScene()`, before
the 2D chrome/border drawing and `storeSpriteVert()` snapshot that follow.
Confirmed fixed by the user.

### Dialog-background darkness — 2026-07-15, attempted and reverted
**Reported**: the "Welcome to RSC"/"Logging Out" dialogs (which draw a
solid black background via `drawBox(..., 0)`) now render as a full hole
straight through to the 3D scene, when they should look dark/mostly-opaque
like the original software renderer's plain (non-transparent) black.
Tried making `presentUIOverlay()`'s plain black-color-key rule mostly-
opaque instead of fully transparent, on the guess that the untouched-3D-
viewport case (which genuinely needs a true hole) was rare in practice.
That guess was backward and the attempt was reverted immediately: the
untouched-viewport region of `pixelData` is plain black almost
everywhere, all the time - not because the 3D scene itself is dark
(it's rendered straight to the GL framebuffer, never through `pixelData`
at all), but because *nothing* draws into that region of `pixelData`
except sprites. Darkening "plain black, not sprite-owned" therefore
darkened nearly the entire screen ("It made everything black"), not just
dialog backgrounds. **Still open**: a real fix needs to track "was this
pixel touched by 2D UI drawing this frame" for every relevant
`GraphicsController` draw call (`drawBox`, `drawBoxAlpha`, etc.), not just
sprites (which already have this via `spriteExpectedColor`) - out of scope
for a quick alpha-value tweak.

### Real fullscreen-exclusive mode — 2026-07-15
Implemented, gated behind `-Dorsc.gl.fullscreen=true` (off by default):
the original "hard constraint driving the design" question from Phase 0.
`NativeGL.createContext()` now takes a `fullscreen` flag; when true, it
first does a real `ChangeDisplaySettings(CDS_FULLSCREEN)` mode switch (to
`width x height @ 16bpp`, matching this context's existing 16-bit-color
pixel format choice) and creates a `WS_POPUP` + `WS_EX_TOPMOST` borderless
window covering it, instead of the normal titled/bordered
`WS_OVERLAPPEDWINDOW` - the standard Win32 pattern real Voodoo2 MiniGL
ICDs of this era needed, not just a borderless/maximized window. Falls
back to the normal windowed path automatically if the mode switch itself
fails, rather than failing context creation outright (an unsupported
resolution/bit-depth shouldn't be fatal). `destroyContext()` restores the
original display mode if this context switched it. As before, this can't
be meaningfully exercised under Wine/macOS - real verification still needs
dgVoodoo2 or real hardware (see the dev/test matrix above).

**Follow-up, same session**: `width`/`height` passed to `createContext()`
are `graphics.width2`/`height2` - this engine's own internal render
resolution (512x346, inherited from the original RSC applet's fixed
viewport size) - which was originally *also* the fullscreen display mode
directly, an unusual, non-period resolution for real Voodoo2 hardware to
switch to. Added a second, configurable resolution mode instead of
picking one: `createContext()` now takes `fullscreenWidth`/
`fullscreenHeight` (`-Dorsc.gl.fullscreen.width`/`.height` on the Java
side, both defaulting to 0). Mode 1 (either unset, or equal to
width/height): the display mode matches the engine's own native
resolution exactly, no letterboxing. Mode 2 (a larger resolution, e.g.
640x480, the classic Voodoo2-era standard): the display switches to
*that* mode instead, and the engine's own (smaller, different-aspect-
ratio) content is centered within it (letterboxed/pillarboxed via
`glViewport`, not stretched, and not changing the engine's own resolution
to match - which would touch UI layout/click-region math calibrated to
512x346 throughout `mudclient.java`) - matching how real Voodoo2-era games
below a monitor's native resolution actually looked. The centering offset
is tracked per-context and applied transparently inside every native call
that takes a game-space pixel coordinate (`pushInputEvent` for mouse
input, `setPickScissor`/`restorePickPixels`/`readPixelColor`/
`readDepthRect`/`readColorRect` for picking and depth/color readback) -
nothing on the Java side needs to know the offset exists, or which mode
is active.

### Sprite picking (click-to-interact) — 2026-07-15
Extended picking to sprite billboards (players/NPCs/items), previously a
documented gap (world/scenery geometry only). Confirmed the exact
existing protocol first rather than inventing a new one: mudclient's own
pick-result dispatch already expects a sprite hit to report
`getSpriteBillboardModel()` as the hit model, with the face index's
`facePickIndex[]` value holding mudclient's own `entityType * 10000 +
arrayIndex` packed id (mirroring `Scene.drawSprite()`'s exact behavior -
`drawSprite()`'s `pickIndex` argument was already being passed this value
by mudclient every frame, just previously discarded, unused, by
`GLSceneRenderer.drawSprite()`).

Implemented as a plain CPU rect + depth check (`performSpritePicking()`),
not a second GL color-ID pass: `drawSpriteBillboards()` already computes
an accurate screen rect and eye-space depth (interpolated top/base, same
fix wall occlusion needed) for every visible sprite each frame, so
reusing that is simpler than synthesizing real camera-facing billboard
quad geometry just to redraw it for an ID color. Picks the nearest sprite
whose rect contains the mouse, confirms it isn't occluded there with a
single-pixel `NativeGL.readDepthRect()` sample, and additionally confirms
the exact mouse pixel is real (non-transparent) sprite content, not
background/gap within the same rectangular bounding box - without that
last check, clicking empty ground next to a sprite (but still inside its
box) silently ate an ordinary walk-here click instead of moving the
player, caught via user testing. A sprite hit found this way takes
priority over the world-geometry GL pass's result, since it's already
been depth-checked against the same real geometry that pass tests.

Bug found and fixed during this work, unrelated to the picking logic
itself: `GLSceneRenderer.getSpriteBillboardModel()` was still a stub
returning `null` (harmless before anything set `pickHitModels[0]` to the
real sprite model, since nothing needed the real answer yet) - once
`performSpritePicking()` did, mudclient's dispatch check
(`scene.getSpriteBillboardModel() == pickedModel`) could never succeed,
so every sprite hit silently misrouted to the world-object menu branch.
Characters were undetectably unselectable until this was found; fixed by
returning the real `spriteBillboardModel` field. Confirmed working by the
user (including successful combat).

**Known gap, not yet resolved**: fixing sprite picking's "click empty
ground don't eat the click" bug, and separately a user report of their
own nametag being partially erased by an overlapping sprite's occlusion
pass, led to a `spriteOwnerIndex` per-pixel tracking array (in
`occludeAgainstDepthBuffer()`/`markSpriteOwner()`) recording which sprite
last drew real (non-sentinel) content to a given pixel, so one sprite's
occlusion decision can't blindly erase a different, overlapping sprite's
already-drawn content. This went through three iterations same-session,
each one user-tested and each falling short in a new way:
1. No ownership tracking at all: confirmed the original nametag-erasure
   bug (a goblin behind a doorway erased part of the player's nametag).
2. Ownership claimed over each sprite's *entire* rectangular bounds
   unconditionally: fixed the nametag erasure, but regressed wall
   occlusion for unrelated, merely-nearby sprites - two characters simply
   standing near each other on screen was common enough that a nearer
   one's blanket claim routinely stole pixels from a farther one's own
   independent body-vs-wall occlusion. Confirmed by the user: an NPC that
   should have been hidden behind a wall was fully visible instead.
3. Ownership claimed only over each sprite's narrow nametag strip (not
   its body rect): fixed the wall-occlusion regression, but reopened a
   variant of the *original* problem one level down - the player's own
   *body* pixels (not just the nametag) could still be erased by an
   overlapping sprite's occlusion pass, since only the nametag strip was
   protected. Confirmed by the user.
4. Current state: ownership is claimed over the sprite's whole rect
   (body + nametag strip) again, but `markSpriteOwner()` only actually
   claims a pixel where real (non-sentinel) content was drawn there, not
   the whole rectangular bounds indiscriminately - intended to combine
   attempt 2's full protection with attempt 3's narrow false-claim
   footprint. **Not yet confirmed working**: the user's last test after
   this version still showed the goblin fully visible through a wall it
   should have been occluded behind. Needs further investigation before
   considering this resolved - left as a known, open gap rather than
   guessing at a fifth iteration without new data.

### FPS counter — 2026-07-15
Added, opt-in via `-Dorsc.fps=true` (matching this project's `-Dorsc.*`
system-property convention rather than a plain command-line flag, so it
needs no `args[]` parsing in `OpenRSC.main()`). Implemented in
`ORSCApplet.draw()`, not `GLSceneRenderer` - first attempt put it inside
`presentUIOverlay()`, which only exists on the GL path at all (called
from `draw()` behind `if (glActive)`), so it would have silently done
nothing under the plain software `Scene` renderer. `draw()` is the one
place both render paths pass through every frame, right before whichever
one actually composites/blits `pixelData` (the GL texture upload, or the
Swing `ScaledWindow` blit) - drawing the counter there via
`GraphicsController.drawShadowText()` (the same text draw nametags use)
means one implementation covers both paths with no renderer-specific
code. Counts real displayed frames over a rolling ~1-second window,
rounding to the nearest whole FPS. Confirmed working under both
`-Dorsc.renderer=gl` and the plain software renderer.

**Unrelated crash hit during this testing, root-caused, not a regression**:
a `jsound.dll` native access violation on Java's own sound mixer thread
(`com.sun.media.sound.MixerThread.run()`), right as a notification sound
effect tried to play - a known fragility of Java 1.4.2's old audio engine
under Wine, on a background thread with nothing to do with rendering or
this session's other changes. Not investigated further; unrelated to the
FPS counter or the GL renderer.

### Input-handling gaps - mouse wheel + Alt — 2026-07-15
Closed two of the gaps `ORSCApplet.pollGLInput()` had documented since
Phase 5: mouse wheel and Alt weren't forwarded at all through the GL
window. Checked what the other documented gaps
(mouseClicked/mouseEntered/mouseExited, a real meta-key modifier) were
actually used for before touching them, rather than assuming all listed
gaps were equally worth closing: all three handlers only call
`updateControlShiftState()` (redundant with what press/release/move
already trigger every time), and nothing in this codebase reads a meta
modifier at all - forwarding those would add surface area for zero
behavior change, so they're deliberately still not sent.

- **Mouse wheel**: `MouseHandler.mouseWheelMoved()` drives camera zoom and
  chat-panel scrolling - completely unavailable through the GL window
  before this. Added `WM_MOUSEWHEEL` handling to `NativeGL.c`'s `WndProc`
  (a new `INPUT_MOUSE_WHEEL` event type) - unlike every other mouse
  message, `WM_MOUSEWHEEL`'s coordinates are screen-relative, not client-
  relative, so `WndProc` converts with `ScreenToClient` before queuing.
  `pollGLInput()` builds a real `MouseWheelEvent`, negating the notch
  count Win32 reports (positive = away from the user) to match AWT's own
  opposite sign convention for `getWheelRotation()` - so this behaves
  identically to a real AWT peer's wheel event on Windows, not just
  "some" scroll direction.
- **Alt**: `KeyHandler.keyReleased()` specifically checks
  `keyCode == KeyEvent.VK_ALT` to reset swipe-drag zoom tracking - never
  fired through the GL window, because Alt (and Alt+key combos, F10)
  generate `WM_SYSKEYDOWN`/`WM_SYSKEYUP`, not the plain `WM_KEYDOWN`/
  `WM_KEYUP` `WndProc` already handled. Added those two cases, routed
  through the same `INPUT_KEY_DOWN`/`UP` path as ordinary keys (Win32's
  `VK_MENU` and AWT's `KeyEvent.VK_ALT` are the same numeric value, so no
  translation is needed, consistent with every other key code this layer
  already passes through as-is). Consumed here rather than falling
  through to `DefWindowProc`, matching every other key message - a
  borderless game window shouldn't hand Alt off to Windows' own system-
  menu handling.

Confirmed both working by the user.

### Launch flag - `-Dorsc.renderer=gl` — 2026-07-15
Documented here because it was mis-launched at least once this session:
this single system property gates *two* things at once -
`mudclient.getScene()` picks `GLSceneRenderer` vs. the plain `Scene`
software renderer with it, and `OpenRSC.createAndShowGUI()` uses the same
property to decide whether to skip `scaledWindow.launchScaledWindow()` -
so omitting it doesn't just disable the GL path, it also leaves the old
AWT/Swing window visible, which looks like "the wrong client" rather than
a missing flag. `Client_Base/run-gl-wine.sh` wraps the full correct
command (kills any stale instance first, sets the flag, points at the
Wine-hosted Java 1.4.2_19 JVM) so this doesn't need to be retyped or
re-derived.

**Phase 6 — Ship as opt-in.** This target builds a separate jar; wire a
runtime flag or keep it as a fully separate launch path so java13/MRJ225/
java14 continue shipping the untouched software renderer unconditionally.

### Release packaging — 2026-07-15
Packaged and published a first Windows release for this target, mimicking
the structure of the existing `openrsc-win98.zip` asset on the
`java14-v1.0` GitHub release (downloaded and inspected it with `gh release
download` to confirm the exact layout expected).

Three launcher batch files were added to `Client_Base/`:
- `run-software.bat` — original CPU renderer, no flags beyond the existing
  `-Dsun.java2d.noddraw=true`
- `run-gl-windowed.bat` — `-Dorsc.renderer=gl`, normal window
- `run-gl-fullscreen.bat` — `-Dorsc.renderer=gl -Dorsc.gl.fullscreen=true`,
  native-resolution exclusive fullscreen (no letterboxing)

Plus `Client_Base/README.md`, documenting all `-Dorsc.*` flags (renderer
selection, fullscreen on/off, fullscreen width/height for letterboxed
640x480, FPS counter) and the current known-limitations list (sprite
occlusion ownership gap, see-through dialog backgrounds, missing menu icon
asset, unverified real-hardware fullscreen, partial input forwarding).

The release zip (`openrsc-win98-gl.zip`) was assembled to match the
reference `openrsc-win98.zip` layout exactly: outer `Cache/` (video/,
audio/, config.txt, port.txt, ip.txt), `Open_RSC_Client.jar`, plus this
target's own additions (`NativeGL.dll`, the three `.bat` files, `README.md`).
Deliberately excluded, matching the reference asset's own precedent:
- `Cache/Cache/` — a redundant nested duplicate confirmed unused by the
  running client (`Config.F_CACHE_DIR = "Cache"` reads the outer folder
  only)
- `MD5.SUM` — not present in the reference asset either
- `uid.dat` — self-regenerates via `SecureRandom` on first run if absent
  (see `mudclient.getUID()`), so shipping one would be pointless and
  possibly confusing (looks like a per-user identity file, isn't meant to
  be shared)

Uploaded as a new asset (`openrsc-win98-gl.zip`) on the existing
`java14-v1.0` release via `gh release upload`, and updated that release's
notes (`gh release edit --notes-file`) with a new Downloads row and a
"Windows 98 Setup (OpenGL / Voodoo2, experimental)" section pointing at the
three launchers and the bundled README for the full flag/limitations list.

**Gotcha avoided:** Windows `.bat` files must have CRLF line endings, not
LF, or legacy `command.com`/`cmd.exe` can mishandle them - checked with
`file <name>.bat` before packaging (all three already had correct CRLF
endings; nothing needed fixing this time, but it's not guaranteed by
default when a file is written from this Unix-based dev environment, so the
check is worth repeating for any future `.bat` file).

### Real-hardware failure: NativeGL.dll needs the CRT rewritten (zero-CRT build) — 2026-07-15
The user tested `run-gl-fullscreen.bat` on **real Windows 98 with a Voodoo 5**
(the actual target hardware this whole effort has been aimed at) and hit a
crash immediately after "Got server configs!" (the first point
`GLSceneRenderer.ensureContext()` - and therefore `NativeGL.<clinit>`'s
`System.loadLibrary` call - actually runs):

```
java.lang.UnsatisfiedLinkError: C:\...\NativeGL.dll : One of the library
files needed to run this application cannot be found
```

This is *not* a missing-file problem for `NativeGL.dll` itself (it was
found) - it's `NativeGL.dll`'s own dependencies that couldn't be found.
`objdump -p` on the DLL built by the original `build-nativegl.sh` showed
imports from `api-ms-win-crt-heap-l1-1-0.dll`, `api-ms-win-crt-runtime-
l1-1-0.dll`, `api-ms-win-crt-stdio-l1-1-0.dll`, `api-ms-win-crt-string-
l1-1-0.dll`, etc. - even for plain `malloc`/`memcpy`/`strlen`. These
"API sets" are a **Windows 7+ loader feature** that transparently redirects
these virtual DLL names to `ucrtbase.dll`; Windows 98's loader has no
concept of them at all, so `LoadLibrary` just fails outright. Root cause:
the installed cross-compiler (`i686-w64-mingw32-gcc` 15.2.0 / mingw-w64
14.0.0 via Homebrew) links its default CRT import library through these
API-set forwarders for a long list of common functions, *regardless* of
msvcrt-vs-ucrt selection - confirmed the default spec really does pick
`-lmsvcrt`, and the API-set imports showed up anyway. Copying `ucrtbase.dll`
(or the API-set stub files) onto the Win98 box wouldn't have helped either -
`ucrtbase.dll` itself needs Vista+ kernel32/ntdll exports that don't exist
on that kernel, just pushing the same error one level deeper.

**Fix: rewrite `NativeGL.c` to need no CRT at all**, rather than hunting
for/building an old pre-API-set mingw-w64 toolchain. The actual libc
surface used was tiny (one `malloc`/`free` pair, plus three `ZeroMemory()`
calls - which turned out to themselves be macros expanding to `memset()` in
this toolchain's headers, confirmed via `winnt.h`/`winternl.h`, so calling
them still pulls in libc despite looking like a "real" Win32 API).
Changes, all in `Client_Base/native-gl/NativeGL.c`:
- `malloc`/`free` (one call site, in `readColorRect`) → `HeapAlloc`/
  `HeapFree` against `GetProcessHeap()` - genuine kernel32 exports.
- Added a hand-rolled `zeroBytes()` (manual byte loop) replacing all three
  `ZeroMemory()` call sites.
- Added local `memcpy`/`memmove`/`memset` definitions (non-`static` - they
  must match the non-static `__cdecl` declarations already pulled in
  transitively by `windows.h`'s own `string.h`, or GCC errors on a linkage
  mismatch). These aren't called directly anywhere in the file - they exist
  purely because GCC's own codegen for large aggregate copies (specifically
  `destroyContext()`'s `g_contexts[i] = g_contexts[i + 1];`, copying a
  whole multi-kilobyte `GLContext` struct thanks to `pickSaveBuffer`/
  `inputQueue`) unconditionally lowers to a call to `memcpy`, regardless of
  `-fno-builtin`/`-ffreestanding` - those flags only stop GCC from
  *recognizing hand-written loops* as library-call patterns, not its own
  aggregate-copy lowering strategy. Confirmed necessary by an
  `ld: undefined reference to memcpy` failure without them.
- Added an explicit `DllMain` (`BOOL WINAPI DllMain(...) { return TRUE; }`)
  to serve as the *real* PE entry point (see build flags below) - mingw's
  default `DllMainCRTStartup` wrapper is what pulls in the api-ms-win-crt-
  runtime onexit-table functions (`_initialize_onexit_table` etc.) in the
  first place, even when nothing in this file's own code needs them.

`Client_Base/native-gl/build-nativegl.sh` link flags now:
`-ffreestanding -fno-builtin -fno-stack-protector
-fno-stack-clash-protection -nostdlib -Wl,--entry=_DllMain@12` plus explicit
`-lkernel32 -lopengl32 -lgdi32 -luser32 -lgcc` (the last one - a static
archive, not a DLL - kept for any compiler-generated intrinsics like 64-bit
divide helpers; safe to keep since it adds no import-table entries).

Verified via `objdump -p NativeGL.dll | grep "DLL Name"` that the rebuilt
DLL imports *only* from `GDI32.dll`, `KERNEL32.dll`, `OPENGL32.DLL`,
`USER32.dll` - all genuinely present since Windows 95/98 - with zero CRT
dependency of any kind. Re-tested under `run-gl-wine.sh`: the client now
sails straight through the exact point that crashed on real hardware
("Got server configs!" → login-screen viewport rendering, which is
`NativeGL`'s first actual use) and into normal gameplay with the profiler
running, no errors. Re-packaged and re-uploaded `openrsc-win98-gl.zip` to
the `java14-v1.0` GitHub release (`gh release upload --clobber`) with the
fixed DLL.

**Not yet done:** re-validated on the actual Windows 98 + Voodoo 5 machine -
the Wine re-test only proves the toolchain-level fix (no CRT imports at
all) and that nothing regressed functionally under Wine; real hardware is
still the only environment that caught the original bug, so it's the only
one that can fully confirm this fix.

### Real-hardware failure #2: blank white GL window on Voodoo 5 - NPOT UI-overlay texture — 2026-07-15
With the CRT crash fixed (above), the user re-tested `run-gl-windowed.bat`
and `run-gl-fullscreen.bat` on the real Windows 98 + Voodoo 5 box: no more
`UnsatisfiedLinkError`, the client loaded and ran (console showed live
`GLSceneRenderer PROFILE` lines every frame) - but the actual
"OpenRSC (GL, experimental)" window itself was solid blank white in both
modes.

The profiler output was the key clue: `rotate`/`fill`/`draw`/`sprite.total`/
`pick.total` all read exactly `0.0` on every frame (the 3D pass wasn't
running - login screen, before `renderLoginScreenViewports()`'s viewport
carousel kicks in), while `ui.convert`/`ui.upload`/`ui.draw` all showed
real, varying costs (9-30ms+). That ruled out the user's reasonable
alternate hypothesis that the JVM simply couldn't find/load the Voodoo 5's
ICD at all: if `wglCreateContext` had failed, `ensureContext()` would have
left `ctx == 0` and `presentUIOverlay()` would return before doing *any* GL
work, so the UI-overlay profiler numbers would never have appeared. Real
time being spent there meant a valid context existed and
`glGenTextures`/`glTexImage2D`/the overlay quad draw were genuinely
executing - just producing the wrong visual result, pointing at a texture
correctness bug rather than a missing driver.

Root cause: `presentUIOverlay()` (`GLSceneRenderer.java`) uploads the full
2D UI/game canvas as one texture at its exact pixel dimensions - `512x346`
native, or `640x480` in letterboxed fullscreen. Neither dimension is a
power of two. Real pre-2003 OpenGL ICDs (this engine's actual hardware
target) require power-of-two texture dimensions in both axes -
`ARB_texture_non_power_of_two` didn't exist yet - while Wine's OpenGL
translation (everything this was developed/tested against until now)
silently tolerates NPOT textures, so this never showed up until real
hardware. Confirmed game/sprite textures were never at risk - those are
always uploaded at a fixed `64x64`/`128x128` (`ensureTextureUploaded()`),
already power-of-two by convention - only this one full-canvas overlay path
was affected.

**Fix:** pad the uploaded texture up to the next power-of-two size in both
dimensions, and only sample the real content's fraction of it:
- `GLSceneRenderer.java`: added `nextPowerOfTwo(int)`; `presentUIOverlay()`
  now allocates the RGBA buffer at `potWidth x potHeight` (real content
  written into the top-left `width x height` region, row stride `potWidth`
  instead of `width` - needed a nested x/y loop instead of the old flat
  per-pixel loop, since source and destination stride now differ), uploads/
  updates the texture at the pot size, and passes
  `(width/potWidth, height/potHeight)` as a new `uMax`/`vMax` argument to
  `drawUIOverlay`.
- `NativeGL.java`/`NativeGL.c`: `drawUIOverlay` gained `uMax`/`vMax`
  parameters; the full-screen quad's texcoords changed from hardcoded
  `1.0f` (the far edge) to `uMax`/`vMax` on the matching axis - the `0.0f`
  edges are untouched, since the real content is written starting at the
  texture's (0,0) origin, only the far/bottom-right edge of the valid
  region moves.
- Padding bytes are left zeroed (default) and never sampled, since the quad
  never asks for texcoords beyond `uMax`/`vMax`.

Rebuilt both `NativeGL.dll` (`build-nativegl.sh`) and the jar
(`build-java14-gl.sh`), re-verified the DLL's import table is still exactly
`GDI32`/`KERNEL32`/`OPENGL32`/`USER32` (unaffected by this change), and
re-tested under `run-gl-wine.sh` - a real screenshot of the Wine window
confirmed the UI overlay (HUD icons, health/prayer counters, chat log,
nametags) renders correctly alongside the 3D scene, no regression. Shipped
the rebuilt jar + DLL in a refreshed `openrsc-win98-gl.zip`, re-uploaded to
the `java14-v1.0` release.

**Not yet done:** re-validated on the actual Windows 98 + Voodoo 5 machine -
same caveat as the CRT fix above; the Wine re-test can't reproduce the
NPOT enforcement that caught this in the first place (Wine never enforced
it), so real hardware is still the only environment that can confirm this
one.

### Real-hardware round 3: windowed GL confirmed correct, but two new gaps found — 2026-07-15
The user re-tested on the real Windows 98 + Voodoo 5 machine with both
fixes above in place. `run-gl-windowed.bat` now renders correctly - a real
screenshot confirmed the full scene (walls, characters, HUD icons, hits/
prayer counters, nametags, chat log) all display properly, matching what
Wine had shown throughout development. Both real-hardware bugs above are
confirmed fixed. Two new, real-hardware-only findings came out of this
same test:

**1. Severe GL performance regression: 1-2 FPS on Voodoo 5 + Pentium III
1.1GHz, vs. ~40 FPS in software mode.** Root cause:
`occludeAgainstDepthBuffer()` (`GLSceneRenderer.java`, sprite-vs-wall
occlusion) called `NativeGL.readDepthRect()` - a `glReadPixels` under the
hood - once per visible sprite, every single frame, each call reading only
that one sprite's own small rect. Real pre-2000s 3D accelerators generally
have no fast GPU-to-CPU readback path (their architecture optimized for
write bandwidth to the framebuffer/display, not reading back from it), so
every such call forces a full pipeline sync/stall - with N visible sprites,
N stalls every frame. Wine's OpenGL-on-Metal translation (the only
environment this was tested in throughout development) apparently absorbs
this pattern without difficulty, so it never surfaced until real hardware.
The user's own alternate hypothesis (JVM not finding the Voodoo 5's ICD at
all) was ruled out first: the earlier blank-white-screen bug's profiler
output showed real time being spent in actual GL draw calls, which
wouldn't happen if context creation had failed outright.

Fixed by batching the read: `drawSpriteBillboards()` now does one whole-
canvas `NativeGL.readDepthRect()` per frame into a new `sceneDepthBuffer`
field, and `occludeAgainstDepthBuffer()` indexes into that shared buffer
instead of calling `NativeGL.readDepthRect()` itself per sprite - same math,
same result, but one GPU sync per frame instead of one per sprite
regardless of how many characters are on screen. Rebuilt and re-tested
under `run-gl-wine.sh`: occlusion/nametag rendering still looks correct
(screenshot-confirmed), profiler still reports a real, nonzero
`occlusion=` cost (so the batched read is still happening), no regression.
**Not yet re-verified on real hardware** - Wine can't demonstrate the actual
performance win since it never exhibited the slow-readback problem in the
first place; only the Voodoo 5 can confirm this.

**2. `run-gl-fullscreen.bat` didn't actually go fullscreen - stayed
windowed.** Root cause: the batch file requested fullscreen at the game's
own native resolution (512x346) with no `-Dorsc.gl.fullscreen.width/height`
set - not a real, enumerated VGA display mode, so
`ChangeDisplaySettings(&dm, CDS_FULLSCREEN)` almost certainly failed on
real hardware, and `createContext()`'s own designed fallback (silently
revert to windowed rather than fail context creation) kicked in exactly as
built - just not the outcome the user wanted. Fixed by changing the batch
file's defaults to `-Dorsc.gl.fullscreen.width=640 -Dorsc.gl.fullscreen.height=480`
(the classic Voodoo2-era standard resolution, letterboxed) instead of the
native-resolution mode - a real, universally-supported display mode. Also
updated `README.md`'s launcher table/known-limitations section to match.
**Not yet re-verified on real hardware.**

Rebuilt the jar (`build-java14-gl.sh`) and re-shipped `openrsc-win98-gl.zip`
to the `java14-v1.0` release with the updated jar, `run-gl-fullscreen.bat`,
and `README.md` (no native `NativeGL.dll` changes this round).

Also added a second deploy target this round: `/Volumes/32GB/openrsc-win98-gl`,
a mounted drive the user physically carries to the real Windows 98 machine
(no network path to it) - now gets the same jar/DLL/batch files/README any
time this target is rebuilt, alongside the GitHub release zip.

### Real-hardware round 4: perf fix regressed the login screen entirely — 2026-07-15
The user re-tested with round 3's fixes in place and hit something worse:
**no GL mode reached the New User/Existing User start screen at all** - a
regression from the previously-working (screenshot-confirmed) state.

First lead, and a real but ultimately unrelated bug: the batch files'
`2>&1` log redirect (added in round 3) produced a file literally named
`&1` instead of merging stderr into the log. Root cause: Windows 98 uses
`COMMAND.COM`, not the NT-family `cmd.exe` - `2>&1`'s file-descriptor-
duplication syntax is a `cmd.exe`-only feature `COMMAND.COM` doesn't
understand at all, so it just took "&1" as a literal filename for the `2>`
redirect. Fixed across all three `.bat` launchers by using two independent
single-stream redirects instead (`> out.log 2> err.log`), which both
shells handle correctly. The user confirmed (by testing again after
removing the redirect entirely) that this logging bug was NOT the cause of
the missing start screen, just a real bug in its own right worth fixing
anyway - now recorded in memory alongside the CRLF gotcha, since it's the
same root cause (real Windows 98 shell behavior differing from every dev/
test environment this was built and tested against).

The `&1` log file that DID get written (captured via the broken redirect,
so real content despite the wrong filename) showed the client reaching
"Got server configs!" and then nothing further - no PROFILE lines, no
exception, no further output at all. That's exactly the point
`continueStartGame()` calls `renderLoginScreenViewports()`, whose first
`endScene()` call is the very first real use of the GL pipeline this
session (context creation, world geometry, and - as of round 3's perf fix
- the new batched whole-canvas depth read, all for the first time).

Root cause, found by re-reading `drawSpriteBillboards()`: round 3's
batched-depth-read fix ran **unconditionally** whenever `ctx != 0`,
regardless of whether there were any visible sprites to actually occlude.
The login-screen carousel's character previews are ordinary world/scenery
`RSModel`s (drawn via the main geometry pass), not sprite billboards -
`spriteDraws` is empty there - so this new code was doing a full,
completely pointless whole-canvas `glReadPixels(GL_DEPTH_COMPONENT)` on
literally the first `endScene()` call of the entire session, for zero
benefit. Plausible on real, limited hardware for this to be an outright
hang/crash trigger (a large synchronous readback with no Java-level
exception possible if it's a native-level stall), not just wasted time -
consistent with output stopping completely with no stack trace at all.

Fixed in `GLSceneRenderer.drawSpriteBillboards()`: compute `anyVisible`
(whether at least one sprite is actually visible this frame) before the
occlusion pass, and skip the depth readback (and the whole occlusion loop)
entirely when it's false, rather than trying to further narrow the read's
size. Rebuilt and re-tested under `run-gl-wine.sh`: profiler now correctly
shows `occlusion=0.0` during the login carousel (confirming the guard
works - the read genuinely isn't happening there anymore) and the client
still reaches gameplay normally afterward, no regression. Re-shipped the
jar (both to the `java14-v1.0` release zip and the `/Volumes/32GB` deploy
drive) and the corrected batch files. **Not yet re-verified on real
hardware** - same caveat as every fix in this saga: Wine can't reproduce
the actual failure mode that caught this, so only the Voodoo 5 machine can
confirm the login screen actually comes back.

### Real-hardware round 5: fullscreen tiling/instability - ruling out theories one at a time — 2026-07-15
Round 4's fixes got the login screen back. The user then retested
fullscreen mode itself on the real Voodoo 5 and hit the originally-reported
tiling artifact again (screen split into two smaller side-by-side copies
of the login UI), this time with actual video/photo evidence to work from.
Also got a second, independent real-hardware data point: **the exact same
codebase's fullscreen mode works correctly on a different, newer machine
running Windows XP** (confirmed via photo), at 8-10 FPS - strong evidence
this is specific to the Voodoo 5 (or its driver) rather than a universal
bug in the fullscreen implementation itself.

Methodically ruled out candidate causes one at a time, each requiring a
real-hardware round-trip to test:
1. **SLI (Voodoo 5's dual-VSA-100-chip Scan-Line Interleave architecture).**
   The user found a "Single Chip Only" option in the Voodoo control panel's
   anti-aliasing settings and tried it - no change. Ruled out.
2. **Resolution.** Tried both 640x480 (the default) and 1024x768 - same
   general class of artifact both times (though at 1024x768 the login UI
   was correctly *centered*, unlike 640x480's split-in-half look - see
   below). Not the primary variable.
3. **Non-origin GL viewport (centered/letterboxed content within a larger
   display mode).** Added a diagnostic system property,
   `-Dorsc.gl.fullscreen.zerooffset=true` (threaded through a new
   `zeroOffset` parameter on `NativeGL.createContext()`, forcing
   `offsetX`/`offsetY` to 0 instead of centering), and a matching
   `run-gl-fullscreen-test-zerooffset.bat` launcher. User tested it:
   **same tiling artifact, no change.** Ruled out - the GL viewport's
   origin was never the problem. (Kept the diagnostic flag/build in place
   in case a future driver combination behaves differently, but it's not
   part of the normal fullscreen path.)
4. **Forced 16-bit color depth.** `createContext()` was hardcoding
   `dm.dmBitsPerPel = 16` for the `ChangeDisplaySettings` mode switch,
   regardless of what the desktop was actually running - windowed mode
   never changes color depth at all, so this was a real, untested
   difference. The user confirmed their desktop was *already* 16-bit
   before testing, though, which weakens this as the actual root cause
   for this specific case - but querying and matching the desktop's own
   current value (`EnumDisplaySettings(NULL, ENUM_CURRENT_SETTINGS, ...)`
   instead of a hardcoded guess) is strictly more correct regardless, so
   the fix was kept.
5. **Undefined refresh rate - the current lead.** `dm.dmFields` never
   included `DM_DISPLAYFREQUENCY` at all, meaning `ChangeDisplaySettings`
   left the refresh rate entirely up to the driver's own default for that
   resolution/depth combo - never verified to match anything the monitor
   is known to handle well. The user's 1024x768 test showed the login UI
   *correctly centered* but "popping in and out" - a classic symptom of a
   CRT failing to sync to an unstable/unsupported refresh rate, not a
   coordinate-math bug. Fixed the same way as color depth: query the
   desktop's current `dmDisplayFrequency` via the same
   `EnumDisplaySettings` call and match it explicitly instead of leaving
   the field unset.

Both the color-depth and refresh-rate fixes went into the same
`createContext()` DEVMODE setup in `NativeGL.c` (one `EnumDisplaySettings`
query up front, reused for both fields). Rebuilt `NativeGL.dll`, confirmed
the import table is still exactly `GDI32`/`KERNEL32`/`OPENGL32`/`USER32`
(this round only touched the DEVMODE/mode-switch logic, not CRT usage),
re-tested windowed mode under `run-gl-wine.sh` (still works, no
regression - fullscreen/refresh-rate matching can't be meaningfully
exercised under Wine at all). Re-shipped `NativeGL.dll` to both the
`java14-v1.0` release zip and the `/Volumes/32GB` deploy drive.
**Not yet re-verified on real hardware** - the refresh-rate fix in
particular can only be judged by whether the "popping in and out" symptom
actually goes away on the Voodoo 5 itself.

### Performance: picking was resubmitting the whole scene's geometry every frame — 2026-07-16
While the user tested the refresh-rate fix on the Voodoo 5, they reported
the same codebase getting only 8-10 FPS on a separate, much newer Windows
XP-class laptop - unacceptable for hardware that recent, and a strong
signal the bottleneck is a real inefficiency in the rendering approach
itself, not just "old/weak GPU."

Root cause, confirmed by re-reading the profiler data already gathered
this session: `performPicking()` (`GLSceneRenderer.java`) ran its full,
expensive world-geometry GL color-ID pass **unconditionally every single
frame**, regardless of whether the mouse had moved at all since the last
frame - `pickVerts` in every profiler line exactly matched `triVerts`,
confirming the ENTIRE visible scene's geometry was being resubmitted a
second time, purely to answer "what's under this exact pixel," every
frame. GPUs must transform all of a draw call's vertices before the
scissor test can reject fragments in the rasterizer, so this was
unconditionally doubling the per-frame vertex-transform cost regardless of
scene complexity - independent of, and probably larger than, the sprite-
occlusion depth-read cost fixed earlier this session.

Fixed by throttling the expensive world-geometry pass in
`performPicking()`: added `lastPickedMouseX/Y`, `pickRefreshCounter`, and
`lastFrameHadSpriteHit` fields, and only actually rebuild/resubmit/read
back when the mouse has moved since the last real pick, a sprite-hit/
no-hit transition needs a fresh answer (a sprite disappearing from under a
stationary cursor can't safely reuse a stale sprite-hit result), or
`PICK_REFRESH_INTERVAL_FRAMES` (5) frames have passed since the last real
pick - otherwise `pickHitModels`/`pickHitFaceIndices`/`pickHitCount` are
simply left as the last real pick set them. Sprite picking itself
(`performSpritePicking()`) is unaffected and still runs every frame
unconditionally - it's a plain CPU rect+depth check reusing data already
computed that frame, not a second GL pass, so it was never the cost
problem. The periodic refresh bounds the one real correctness gap this
trades for the performance win: something moving into the mouse's target
area without the mouse itself moving (e.g. an NPC walking under a
stationary cursor) won't be reflected until the next refresh tick, at most
5 frames of staleness - not perceptible for hover-text/click-precision
purposes, not a twitch-reflex game.

Rebuilt and re-tested under `run-gl-wine.sh`: login, gameplay, and the
profiler all still work correctly, no regression. The measured `pick.total`
reduction under this specific Wine test was smaller than the throttle's
theoretical best case (skipping 4 of every 5 frames) - suspected cause is
Wine/macOS's window-event bridge generating small synthetic mouse-move
blips even with no real mouse interaction during an unattended automated
test, which a real physical mouse held still wouldn't produce. Real-
hardware testing (both the Voodoo 5 and, especially, the Windows XP laptop
that reported 8-10 FPS) is what will show the actual win, since a real
stationary mouse behaves very differently from this test environment.
Re-shipped the jar to both the `java14-v1.0` release zip and the
`/Volumes/32GB` deploy drive (no native `NativeGL.dll` changes this
round). **Not yet re-verified on real hardware.**

**Further optimization opportunities identified but not yet implemented**
(noted here rather than attempted blind, since each needs real-hardware
confirmation to be worth the risk):
- The world-geometry picking pass, when it *does* run, still emits every
  face in the whole scene rather than culling to a screen-space region
  near the mouse first - a bigger, more invasive change than the
  throttling above, but would reduce the cost of the "real" picks that
  still do happen every `PICK_REFRESH_INTERVAL_FRAMES` frames.

### Performance: skip the UI overlay reupload when nothing changed — 2026-07-16
`presentUIOverlay()` was rebuilding the RGBA conversion and re-uploading
the entire (POT-padded) UI overlay texture every single frame,
unconditionally, even on the very common case of a frame where the 2D UI
(chat, inventory, HUD) didn't change at all. Investigated the originally-
proposed fix (a dirty flag set inside each of `GraphicsController`'s ~20
low-level pixel-writing primitives - `drawBox`, `drawSprite`, `setPixel`,
several private `plot_*`/blit helpers) and found it feasible (a finite,
enumerable set, all within one file) but riskier than necessary - missing
even one call site would silently show stale UI with no obvious symptom.

Went with a simpler alternative instead: cache last frame's `pixelData`
content (`previousPixelData`, a real copy via `System.arraycopy` - not a
reference, since `pixelData` is mutated in place) and compare with
`java.util.Arrays.equals()` at the top of `presentUIOverlay()`, before any
conversion work. If identical, skip the RGBA conversion and texture
upload entirely and just redraw the already-uploaded texture (still
necessary every frame regardless - `beginFrame()` clears the color buffer
each frame, so nothing persists on screen without redrawing the quad).
`Arrays.equals()` bails out at the first differing pixel, so even frames
that *do* need the real work pay very little for the check itself. This
approach can't miss a mutation site the way a dirty flag could, since it's
checking the actual data rather than tracking who wrote to it - the
trade-off is one extra ~700KB cached buffer and comparing only
`pixelData`, not `spriteExpectedColor` (also rebuilt every frame - see the
method's own doc comment for why a coincidental mismatch there isn't a
realistic concern).

Rebuilt and tested under `run-gl-wine.sh` with `-Dorsc.fps=true`: `ui.convert`/
`ui.upload` dropped to near-zero (0.0-0.3ms) on most frames, down from a
~2ms baseline, confirming the skip triggers correctly. Screenshot-confirmed
the UI is still fully live (a spellbook panel opened correctly mid-test,
FPS counter itself updating) - not stuck on a stale frame. Re-shipped the
jar to both the `java14-v1.0` release zip and the `/Volumes/32GB` deploy
drive (no native `NativeGL.dll` changes this round). **Not yet re-verified
on real hardware** - same caveat as everything else in this saga.

Also fixed `run-gl-wine.sh` this round: it appended extra args (e.g.
`-Dorsc.fps=true`) *after* `-jar Open_RSC_Client.jar` on the java command
line, where Java hands them to the app's own `main(String[] args)` as
plain arguments instead of parsing them as JVM flags - silently ignored.
Moved `"$@"` to before `-jar` so ad-hoc flags actually take effect. Dev-only
script, not part of the shipped release.

**Real-hardware result for the picking-throttle + UI-overlay-cache round:**
the user tested on the Voodoo 5 in windowed mode - only a modest
improvement ("more consistently at 2 FPS now" vs. the original 1-2 FPS).
Confirms neither of those was the dominant bottleneck on that specific
hardware - `fill` and sprite occlusion (both untouched by that round) were
still the two largest remaining costs per the profiler, motivating the
culling work below.

### Performance: skip face-count/face-fill work for off-screen models entirely — 2026-07-16
`endScene()` deliberately forces `RSModel.rotate1024()`'s own frustum-cull
check to always pass (see the class doc comment - `MiscFunctions.frustumMin/
MaxX/Y`/`frustumNear/FarZ` set to always-true bounds), relying entirely on
GL's own hardware clipping instead of duplicating `Scene`'s frustum-corner
math. That's correct, but it means every model in `models` - including
ones entirely off-screen or behind the camera, likely a large fraction of
what RSC's classic view-distance grid loads around the player at any given
camera angle - was still fully face-counted and face-filled into vertex
arrays every frame, only to have GL discard the off-screen ones after
they'd already been fully processed on the CPU. This lined up with `fill`
being one of the two largest remaining per-frame costs measured this
session.

Added `isModelOffScreen()`: re-derives the exact same frustum planes
`NativeGL.setPerspectiveFrustum()` was set up with (`right = halfWidth *
Z_TOP / 2^vpSrc`, `top = halfHeight * Z_TOP / 2^vpSrc`, both scaling
linearly with depth) and tests `model.vertXRot/vertYRot/vertZRot` directly
- the *exact* camera-space coordinates `putVertex()` sends to GL, not the
sprite-billboard-specific `vertexParam6`/`vertexParam2` screen projection,
so there's no coordinate-space translation to get wrong. Deliberately
conservative in two ways, both correctness-preserving by construction
(worst case: an off-screen model that could have been culled isn't, same
cost as before - never the reverse):
- Any model with a vertex at or behind the near plane is never culled,
  even if every other vertex is comfortably out of frame - a triangle
  straddling the near plane needs real clipping, which screen-space bounds
  can't safely reason about for a vertex on the wrong side of it (the
  classic bug this kind of check has to avoid: something behind the camera
  can project to coordinates that *look* on-screen by coincidence).
- Only culls when *every* vertex is confirmed past a single shared frustum
  plane (all-right, all-left, all-above, or all-below) - not a full
  separating-axis test against the frustum's corners, which would catch
  more cases for real additional complexity.

`endScene()` now computes a `modelOffScreen[]` array once per frame (right
after each model's `rotate1024()` call, which the check depends on) and
both the face-count and face-fill passes skip a model entirely when it's
set - alongside `model.m_dc`, the same early-exit shape already there.
`performPicking()`'s own model/face loop (building world-geometry pick
targets) reuses the same array, since `models.size()` can't change between
`endScene()` computing it and `performPicking()` running later the same
frame - also skips off-screen models for pick-geometry emission, since
something off-screen obviously can't be clicked.

Rebuilt and tested under `run-gl-wine.sh` with `-Dorsc.fps=true`:
`triVerts` dropped from the ~93,000-126,000 range seen in every earlier
profiler log this session down to ~18,000-28,000 - roughly a 70-80%
reduction in submitted vertex count - and `fill` dropped from its earlier
~5.7-6ms baseline to well under 2ms in these samples. Screenshot-confirmed
rendering still looks fully correct (walls, characters, nametags, HUD all
present, no missing/popping geometry at screen edges). Re-shipped the jar
to both the `java14-v1.0` release zip and the `/Volumes/32GB` deploy drive
(no native `NativeGL.dll` changes this round). **Not yet re-verified on
real hardware**, and this one in particular is worth testing across
several different camera angles/rotations specifically, given the
near-plane-straddling edge case this method has to avoid getting wrong.

### First real-hardware profiler data - the bottleneck ranking is completely different from Wine — 2026-07-16
The user tested the off-screen culling round on the Voodoo 5 in windowed
mode: only ~2-3 FPS, barely moved from before - despite the previous round
cutting submitted vertex count by 70-80% under Wine. Captured the first
real `GLSceneRenderer PROFILE` output from actual hardware this entire
session (`gl-windowed-err.log` on the `/Volumes/32GB` drive), and it's a
genuinely different picture than every Wine session suggested:

| Cost | Real Voodoo 5 (gameplay) | Wine (same build) |
|---|---|---|
| `occlusion` | ~105-112ms | ~5-9ms |
| `draw` | ~40-55ms | ~1-3ms |
| `pick.total` (when it fires) | ~40-90ms | ~1-10ms |
| `ui.upload` (when UI changes) | ~30-50ms | ~1ms |
| `rotate` | ~22-27ms | ~0.5-0.7ms |

Summing the full per-frame cost across every bucket (`rotate`+`fill`+
`draw`+`sprite.total`+`pick.total`+`ui.convert`+`ui.upload`+`ui.draw`+the
untracked swap-buffer cost inside `TOTAL`) comes out to ~320-380ms/frame -
right in line with the reported 2-3 FPS, which at least confirms the
instrumentation itself is trustworthy despite Java 1.4 having no
`System.nanoTime()` (only millisecond-resolution `System.currentTimeMillis()`,
mitigated by the existing 30-frame averaging).

**The real finding: sprite occlusion is the dominant cost by a wide
margin (~1/3 of total frame time), not `fill` or picking** - the two
things actually optimized this session. And the *relative* proportions
are different on real hardware too, not just the absolute FPS - `rotate`
(pure CPU vertex math) costs roughly 40x more on the real Pentium III than
under Wine's translation on a modern Mac. Every future optimization needs
real-hardware validation; Wine numbers don't transfer even in relative
terms, only "does it still work correctly" is trustworthy from Wine.

Added a finer breakdown to isolate occlusion's real cost: `profileOcclusionReadMs`/
`profileOcclusionCpuMs`/`profileOcclusionFinalizeMs`, mirroring `pick.total`'s
existing build/gl/readback/restore split - separates the single batched
`NativeGL.readDepthRect()` call from the CPU-side `occludeAgainstDepthBuffer()`
per-sprite loop and the separate `finalizeGapColor()` pass. Printed as
`[occlusion=X [read=A cpu=B finalize=C]]` in the periodic profile line.
Under Wine, `read` alone accounts for nearly all of occlusion's cost
(`cpu`/`finalize` both negligible, well under 0.1ms) - if this pattern
holds on the Voodoo 5 too (not yet confirmed - this is exactly what the
next real-hardware test needs to check), the next fix should target
*reducing the readback itself* (e.g. a tighter bounding-box read instead
of the whole canvas, or reducing how often it happens) rather than the
CPU-side math, which doesn't appear to be the problem.

Also worth flagging as a real possibility: the earlier "batch N small
per-sprite depth reads into one whole-canvas read" fix optimized for
*call count*, on the theory that Wine/period-driver stalls are dominated
by per-call sync overhead. If this hardware's readback cost is actually
*bandwidth*-bound instead (transferring the whole canvas's depth data is
more total bytes than N small sprite-sized rects, even in fewer calls),
that fix could have traded one bottleneck for a different, possibly worse
one - the read/cpu/finalize breakdown is what will tell us which theory is
right once tested on the Voodoo 5.

Rebuilt and verified the breakdown reports correctly under `run-gl-wine.sh`
(zeroed out correctly during the login screen where no sprites are
visible, populates correctly once in gameplay). Re-shipped the jar to both
the `java14-v1.0` release zip and the `/Volumes/32GB` deploy drive (no
native `NativeGL.dll` changes). **Not yet re-verified on real hardware** -
this is purely additive instrumentation (new timers only, no behavior
change), so the real value is in what the *next* physical test's log
reveals, not in any performance change from this round itself.

### Real Pentium MMX + Voodoo2 crash: CMOV instruction, not a Voodoo bug — 2026-07-16
User-tested `windows-java13-gl` on genuine period hardware (233MHz Pentium
1 MMX, Windows 95, Voodoo2) via `run-gl-fullscreen.bat`. Crashed
immediately on first fullscreen context creation with
`EXCEPTION_ILLEGAL_INSTRUCTION` (`hs_err_pid*.log`: `NativeGL.createContext`,
faulting `PC=0x6F9C15E8`) - not a GPU/driver/timing issue at all, despite
the fullscreen investigation immediately above. Root-caused by
disassembling the actual shipped DLL (`objdump -d`) rather than guessing:
the faulting address was exactly a `cmovle` instruction. `i686-w64-mingw32-gcc`
(Homebrew's mingw-w64 toolchain) defaults to `-march=pentiumpro` (P6
family - Pentium Pro/II/III) when no `-march` is given, which permits GCC
to emit CMOV under `-O2` - an instruction a genuine Pentium MMX (P5/i586
family, predates P6) does not implement, so the CPU raised an illegal-
instruction fault the moment execution reached it. This had been silently
wrong since Phase 0 - Wine's own JIT/CPU emulation on Apple Silicon has no
such restriction, so every prior "verified under Wine" result in this
document was real but incomplete: it could never have caught a real-CPU
instruction-set mismatch like this one.

Fixed in `native-gl/build-nativegl.sh`: added `-march=pentium-mmx
-mtune=pentium-mmx` to the compile command (also implies `-mno-sse`/
`-mno-sse2`/etc, correct since this CPU class predates SSE, introduced
with Pentium III). Verified via `objdump -d` on the rebuilt DLL: zero
`cmov`/`sse`/`xmm` instructions anywhere in the disassembly (previously 4
`cmov` sites). Regression-tested under `~/.wine-java14` afterward -
unaffected, still reaches "Got server configs!" normally (Wine's
translation doesn't care about `-march` either way, so this was purely a
verification the flag change didn't break anything else, not a test of
the fix itself - only real period hardware can confirm that). `NativeGL.dll`
rebuilt and re-shipped to both `/Volumes/32GB` deploy drives
(`openrsc-win98-gl` and `java-14-gl`) and to `windows-java13-gl` (shared
binary, see that target's own note on this fix).

**Still open**: not yet re-tested on the real Pentium MMX + Voodoo2 box
with this fix in place - next physical test is what actually confirms it.

### Game-loop/render-pipeline performance pass — 2026-07-16
Requested explicitly after evaluating `mudclient.run()/run2()` and
`GLSceneRenderer`'s render pipeline for optimization opportunities on
period hardware (a real Pentium MMX + Voodoo2 test machine, see the CMOV
fix above). Three changes, all grounded in the profiling data already
captured in earlier sessions (see "Real profiling pass + vertex arrays"
above) rather than guessed at:

1. **Sprite-occlusion depth readback bounded to a sprite bounding box, not
   the whole canvas.** `drawSpriteBillboards()` previously called
   `NativeGL.readDepthRect(ctx, 0, 0, canvasWidth, canvasHeight, ...)`
   unconditionally once per frame (already batched from one-per-sprite in
   an earlier round) - this was the single largest recorded cost band in
   the existing profiling (5-8ms). Now computes the union bounding box of
   this frame's actually-visible sprite rects first, and reads only that
   sub-rect. `occludeAgainstDepthBuffer()` was updated to index into the
   resulting buffer relative to the bound's own x/y/width
   (`sceneDepthBoundX/Y/Width/Height`, new fields) instead of absolute
   canvas coordinates - safe because a sprite's own (already-clamped) rect
   is always fully contained in the bound, since it was one of the rects
   unioned to compute it. `NativeGL.readDepthRect()` itself needed no
   native changes - it already accepted an arbitrary sub-rect, it just
   was never called with anything but the full canvas.
2. **`GetPrimitiveArrayCritical` instead of `GetFloatArrayElements`** in
   `NativeGL.c`'s `drawTriangles()` (called once per texture bucket, every
   frame) and `readDepthRect()` (once per frame). Older HotSpot Client
   VMs (Java 1.3/1.4, what this project ships for) typically copy the
   array on `GetFloatArrayElements` rather than pinning it - a full
   memcpy of the vertex/depth buffer on top of the real GL call, every
   time. `GetPrimitiveArrayCritical` can return a direct pointer instead
   when the GC allows it. Verified safe to use here: nothing between
   Get/Release in either function calls back into the JVM or blocks -
   only GL calls. `uploadTexture`/`updateTexture` (texture upload, not
   per-frame-hot in the same way) and `readColorRect` (login-only, see
   its own comment) were deliberately left on the old API - out of scope,
   lower frequency, not worth the risk/reward here.
3. **Pooled the large per-frame scratch buffers instead of `new`-ing them
   every frame.** `endScene()`'s `flatBuf`/`texBufs` (tens of thousands of
   floats, a whole scene's vertex data), `performPicking()`'s `pickBuf`
   (same size class), and `drawSpriteBillboards()`'s nine parallel
   per-sprite arrays were all freshly allocated every single frame -
   real GC churn on a single-core period CPU with an older, less-tuned
   generational GC. Now backed by instance fields (`flatBufPool`,
   `texBufPool`, etc.) that grow when a frame needs more capacity than
   currently allocated, but are never shrunk/reallocated just because a
   frame needs less - safe because every consumer already receives the
   real element count as a separate parameter (`flatVertexCount`,
   `texVertexCounts[t]`, `pickVertexCount`, sprite `count`) rather than
   relying on `array.length`, so stale trailing capacity from a larger
   previous frame is simply never read. One correctness-relevant catch
   found while doing this: the draw loop's old `texBufs[t] != null` guard
   would now stay true forever once a texture slot is first used (the
   pooled array never goes back to null) - changed to check
   `texVertexCounts[t] > 0` instead, the actual condition that always
   governed it. `visible[]` in `drawSpriteBillboards()` needed an explicit
   per-frame clear (`Arrays.fill(visible, 0, count, false)`) since,
   unlike the other pooled arrays there, a stale `true` left over from a
   larger previous frame's array would actually be read.

Verified: `./build-java14-gl.sh compile` succeeds (0 errors).
`native-gl/build-nativegl.sh` rebuilt cleanly, `objdump` confirmed still
zero CMOV/SSE instructions (the pentium-mmx fix above is independent of
and unaffected by this round). Regression-tested under
`~/.wine-java14` - reached "Got server configs!", rendered a real scene
afterward (`triVerts` non-zero, `rotate`/`fill`/`draw` all reporting
real per-frame costs), no exceptions. **Caveat on interpreting Wine's own
numbers**: Wine's OpenGL-on-Metal translation is call/sync-stall
dominated, not bandwidth-dominated the way real pre-2000s silicon is (see
the profiling history above) - so item 1 in particular (shrinking the
*amount of data* read back) may show little or no visible improvement
under Wine even though it should matter more on real hardware, where the
whole reason readback is expensive at all is the sync stall scaling with
data volume. **Not yet re-verified on the real Pentium MMX + Voodoo2 box**
- that's the test that actually matters for these three changes.
`NativeGL.dll` rebuilt and re-shipped to both `/Volumes/32GB` deploy
drives; source changes ported to `windows-java13-gl` in the same session
(identical code, see that target's own PLAN.md entry).

### Real Pentium MMX + Voodoo2 fullscreen test: still windowed, and catastrophically slow — 2026-07-16
User ran `run-gl-fullscreen.bat` (and a second attempt with
`-Dorsc.gl.fullscreen.refresh=60`) on the same real Pentium MMX + Voodoo2
+ Windows 95 box the CMOV fix above was verified against. Both attempts
visibly stayed windowed rather than switching to real exclusive-mode
fullscreen. The first attempt's log showed the game actually running
(reached "Got server configs!", logged in, rendered real scenes) but at
catastrophic per-frame costs - `TOTAL` averaging 1394-1547ms/frame
(~0.6-0.7 FPS), with `rotate` (pure CPU vertex math, unrelated to which
GPU is active) alone costing 119ms and `draw` spiking to 1244ms in one
frame. Notably: **`nativegl-fullscreen.log` (the diagnostic added in the
refresh-rate-override round above) was not created at all**, on either
attempt - not "created showing a failure," genuinely absent, meaning
`ChangeDisplaySettings` may never have been reached, not just failed.

Working theory, not yet confirmed: this box likely has a genuine 3dfx
Voodoo2 pass-through setup (a secondary card that only engages during
real fullscreen-exclusive 3D rendering, sitting behind a primary 2D
adapter - the user's own guess was "S3 Savage" for the primary) - see
Phase 0's original "Hard constraint driving the design" note that
Voodoo2's MiniGL ICD historically *only* activated in genuine
fullscreen-exclusive mode. If our fullscreen path isn't actually
engaging, whatever OpenGL implementation the windowed fallback uses could
plausibly be Microsoft's own built-in software rasterizer (always present
as `opengl32.dll`'s fallback) rather than the Voodoo2's real MiniGL ICD
at all - which would explain both the visual "still windowed" symptom and
performance an order of magnitude worse than even the previously-recorded
"1-2 FPS on a real Voodoo 5" baseline, since pure CPU vertex math
(`rotate`) taking 100+ms doesn't depend on which GPU is in use and points
at something more fundamental than a slow-but-real 3D path.

**Diagnostic added, not yet a fix**: `NativeGL.c` gained `logGlInfo()`,
called unconditionally right after every successful context creation
(fullscreen or windowed) - writes `nativegl-glinfo.log` with
`GL_VENDOR`/`GL_RENDERER`/`GL_VERSION` straight from `glGetString()`,
plus whether fullscreen was requested and whether it actually activated.
This settles the "which OpenGL implementation is actually current"
question directly rather than inferring it from performance numbers or
Windows 95 registry/control-panel archaeology - real 3dfx MiniGL reports
itself distinctly from Microsoft's software fallback. Also incidentally
tests whether file-writing to wherever the jar runs from works at all,
since `logFullscreenDiag()`'s file was confirmed absent on this same
test run - a second, unrelated explanation worth ruling out. Verified
under `~/.wine-java14`: builds clean (`objdump` still shows zero CMOV/SSE
instructions), regression-tested, and `nativegl-glinfo.log` was written
correctly (`GL_VENDOR="ATI Technologies Inc."` etc. - Wine's own
translation of the host Mac's GPU, exactly as expected, confirming the
logging mechanism itself works end-to-end). **Next step needs the real
hardware**: re-run `run-gl-fullscreen.bat` and report what
`nativegl-glinfo.log` (and whether `nativegl-fullscreen.log` appears at
all this time) actually say.

### Explicit local OpenGL32.dll loading, tried before the system one — 2026-07-16
Requested explicitly, motivated by the fullscreen investigation above:
community MiniGL replacements for Voodoo2-era hardware (WickedGL, MesaFx,
Creative's own bundled variant) are all, architecturally, drop-in
replacements for the same Win32 OpenGL ICD our code already talks to via
plain `gl*()`/`wgl*()` calls (this file has always stuck to core OpenGL
1.1 fixed-function, no vendor extensions, specifically so it could run
against any of these) - and several are specifically known for better
windowed-mode 3D support than stock 3dfx MiniGL, which historically only
ever activated in genuine fullscreen-exclusive mode. Being able to drop
one next to the jar and have it actually get used, deterministically,
regardless of Windows version, is directly useful for the "why does
fullscreen silently stay windowed and get catastrophic performance"
investigation above - not just a hypothetical compatibility question.

The blocker: every `gl*()`/`wgl*()` symbol used to be a normal static
import (`-lopengl32`), resolved by the OS loader *before* any of this
file's own code runs at all, using whatever the default DLL search order
picks - and that default isn't reliably "check the current directory
first" across Windows versions. A bare `LoadLibraryA("opengl32.dll")`
call has the identical problem: SafeDllSearchMode (default since XP SP1)
checks System32 *before* the current directory - the opposite of what's
wanted here, though moot on the actual Windows 95 target, which predates
that mechanism entirely and would have honored current-directory-first
search anyway. Chose the version that's correct on every Windows version
rather than relying on 9x-specific behavior.

Converted every `gl*()`/`wgl*()` call (39 functions - grepped the whole
file to get the exact set, not guessed) from a static import to a
function pointer, resolved at runtime by a new `ensureOpenGLLoaded()`
(called once, idempotently, at the top of `createContext()` - the one
function guaranteed to run before anything else in this file touches
GL): tries an exact relative path to `.\OpenGL32.dll` first (the current
working directory - `Client_Base/`, where the jar/`NativeGL.dll`/a
user-supplied `OpenGL32.dll` all sit side by side) - an explicit path
bypasses the OS's own search-order logic entirely for that attempt, so
it can't be pre-empted by a System32 copy on any Windows version - and
only falls back to the bare `"opengl32.dll"` name (normal system search)
if that exact file doesn't exist. Each `gl*()`/`wgl*()` identifier is
`#define`'d to its pointer variable at the end of the new section, so
every existing call site elsewhere in the file kept working completely
unmodified - no call sites needed touching by hand. `-lopengl32` removed
from `build-nativegl.sh`'s link line entirely, since nothing in the
object file references the statically-imported symbols anymore.

**Bug found and fixed while writing this, same session**: used `gl*/wgl*`
as shorthand in several of the new doc comments (meaning "gl-star,
wgl-star" as an informal wildcard) without noticing `*/` is literally the
C block-comment terminator - each occurrence prematurely closed its
comment, corrupting everything after it until the next real `*/` was
reached and cascading into dozens of unrelated-looking parse errors
throughout the rest of the file (missing struct members, stray tokens).
Caught immediately by the compiler on the very next build attempt, not
subtle - fixed by rewriting the shorthand as `gl*()/wgl*()` throughout
(trailing parens break up the `*/` adjacency).

Verified: `./build-java14-gl.sh compile` succeeds (0 errors).
`build-nativegl.sh` rebuilt cleanly; `objdump -p` confirmed `opengl32.dll`
no longer appears in the DLL's import table at all (only GDI32/KERNEL32/
USER32 remain), and `objdump -d` confirmed still zero CMOV/SSE
instructions (unaffected by this round). Regression-tested under
`~/.wine-java14` - reached "Got server configs!", confirming the
*fallback* path (no local `OpenGL32.dll` present under Wine, so it
correctly falls through to the system one) still works exactly as
before. **The actual feature - a local `OpenGL32.dll` genuinely being
preferred - has not been tested at all yet**, on Wine or real hardware;
that needs an actual WickedGL/MesaFx/etc. DLL renamed to `OpenGL32.dll`
and dropped next to the jar, which wasn't done this round. Source ported
to `windows-java13-gl` in the same session (identical native code, no
Java-side changes needed - see that target's own PLAN.md entry).
`NativeGL.dll` re-shipped to both `/Volumes/32GB` deploy drives.

### Java 1.3 port (`targets/windows-java13-gl`) — 2026-07-16
This target's entire GL renderer (`SceneRenderer`/`GLSceneRenderer`/
`NativeGL.java`/`NativeGL.c`, and every `mudclient`/`Scene`/`World`/
`GraphicsController`/`ORSCApplet`/`OpenRSC` change this PLAN.md documents)
was ported onto `targets/windows-java13` (the existing Java 1.3 backport) -
see `targets/windows-java13-gl/PLAN.md` for the port mechanics (a clean
3-way merge, zero conflicts) and the two real bugs found and fixed via
that port's testing, both of which apply equally here and are fixed in
this target too (see below). `NativeGL.dll` is shared byte-for-byte
between both targets - JNI's native ABI doesn't depend on the Java
bytecode version.

### Manual fullscreen refresh-rate override + settle delay — 2026-07-16
Follow-up to "Real fullscreen-exclusive mode" above, after real Voodoo 5
testing (off-session) showed the existing desktop-Hz-matching logic still
wasn't enough: fullscreen at 640x480@16bpp showed classic CRT sync-loss
artifacts (rolling/torn, diagonally-tearing image) - a monitor failing to
lock the horizontal/vertical timing the card is outputting, not a
Glide/OpenGL rendering bug. Diagnosed as a timing-negotiation problem
specifically (not resolution, not color depth, not a competing Java
fullscreen API - confirmed nothing in this codebase calls
`GraphicsDevice.setFullScreenWindow()`/`DisplayMode`, only this native
layer ever touches `ChangeDisplaySettings`).

Three changes to `NativeGL.createContext()`'s fullscreen path, all in
`native-gl/NativeGL.c`:
- **`fullscreenRefreshHz` parameter** (`-Dorsc.gl.fullscreen.refresh`, see
  `NativeGL.java`/`GLSceneRenderer.ensureContext()`) - an explicit manual
  override for the display mode's refresh rate, since the VSA-100-era
  driver can apparently negotiate/report a rate matching the desktop that
  the CRT still can't actually lock to. 0 (default) keeps the previous
  desktop-matching behavior; the resolution/depth/frequency fields are
  still set and applied via one atomic `ChangeDisplaySettings` call either
  way (not staged/sequential), which was already the case.
- **`Sleep(500)` settle delay** after a successful mode switch, before any
  window/context creation - gives the monitor/driver time to actually
  relock to the new timing rather than issuing the first `SwapBuffers`
  immediately back-to-back with the mode switch.
- **Diagnostic logging** (`logFullscreenDiag()`, new): writes
  `nativegl-fullscreen.log` next to the jar recording the requested vs.
  actually-applied `DEVMODE` (width/height/bpp/Hz, via a post-switch
  `EnumDisplaySettings` readback) - `ChangeDisplaySettings` can silently
  coerce an unsupported field to something else rather than failing, so
  logging only the request wasn't enough to diagnose a future report.
  Implemented with plain `CreateFileA`/`WriteFile`/`wsprintfA` (user32/
  kernel32 exports, not CRT) to match this file's existing no-libc
  constraint (see its file-level comment).

Not yet re-verified on real hardware (no Voodoo 5 in this session's loop) -
next physical test should report what `nativegl-fullscreen.log` shows and
whether the sync-loss artifact is gone with an explicit `-Dorsc.gl.fullscreen.refresh`
value matching the monitor's known-good rate.

### Stale hidden-window resize corrupting the game buffer — 2026-07-16
Found via real login/gameplay testing on `windows-java13-gl` (see that
target's PLAN.md for the original repro) - applies identically here, same
code, fixed in both targets in the same session. `OpenRSC.createAndShowGUI()`
skips `scaledWindow.launchScaledWindow()` for the GL renderer (see "Swing
window hidden for the GL path" above) - but that's also the *only* call
that ever resizes `ScaledWindow` to its correct, scalar-based dimensions.
Left permanently stuck at whatever tiny default size Swing's initial
`pack()` assigned it (confirmed via temporary diagnostics: 56x17, before
any real content/scalar is set), `ScaledWindow.resizeApplet()`/
`validateAppletSize()` could still fire later (`validateAppletSize()` is
called from a `PacketHandler` server-config handler, so this reliably
happens once per real login) and propagate that stale tiny size into
`mudclient.resizeWidth/Height`, which `mudclient.reposition()` then
applies via `GraphicsController.resize()` - shrinking `pixelData`/`width2`
out from under the running game. Next frame, `World.generateLandscapeModel()`'s
fixed `copyPixelDataToSurface(MINIMAP, 0, 0, 285, 285)` call (which assumes
the real 512x346 buffer) threw `ArrayIndexOutOfBoundsException`, and every
frame thereafter rendered only the GL clear color with no scene content
(the crash was caught/logged, not fatal, but nothing useful rendered
again).

Fixed by gating both `ScaledWindow.resizeApplet()`/`validateAppletSize()`
and `ORSCApplet.componentResized()` (a second, independent path that
writes the same fields directly) to no-op when `-Dorsc.renderer=gl` is
active - the hidden Swing chrome's layout is meaningless noise in GL mode,
where the real game viewport size is owned by the GL window
(`NativeGL`'s own top-level Win32 window) instead. Root-caused via
temporary diagnostic logging (printed on every `GraphicsController.resize()`
call, with a stack trace, plus the buffer dimensions at the moment of the
crash) rather than guessed at - removed once confirmed. Verified fixed:
logged in twice on `windows-java13-gl` after the fix, both times stable
through gameplay with no crash or blank-scene regression.

## Files (`gl-spike/`)
- `NativeGL.java`   — JNI native method declarations
- `NativeGL.c`      — Win32 + WGL implementation (creates its own window,
                       no AWT/JAWT integration — that's Phase 5's problem)
- `GLTest.java`      — standalone test: opens a window, renders a spinning-free
                       flat triangle for ~15s, closes
- `build-spike.sh`   — cross-compiles `NativeGL.dll` (32-bit, via
                       `i686-w64-mingw32-gcc`) and compiles the Java side
                       with the Wine-hosted `javac.exe`
- `run-spike.sh`     — runs `GLTest` under `~/.wine-java14`'s real Java
                       1.4.2 JVM with `-Djava.library.path=.`

### Phase 0 status: DONE (Wine leg only) — 2026-07-15
Built and ran successfully: `gl-spike/build-spike.sh` cross-compiles
`NativeGL.dll` with `i686-w64-mingw32-gcc` against the real Java 1.4.2_19
JNI headers, and compiles `NativeGL.java`/`GLTest.java` with that JDK's own
`javac.exe` under Wine. `run-spike.sh` then ran `GLTest` on the real
Java 1.4.2_19 JVM (`~/.wine-java14`): window created, 833 frames rendered
over ~15s, clean teardown, no `UnsatisfiedLinkError`, no crash. The `err`/
`fixme`/`mvk-info` lines in the output are Wine's own internal noise
(present on any Wine-hosted Win32 GL app) and Wine's Vulkan-via-MoltenVK
backend info dump — unrelated to NativeGL and not a problem.

This proves the JNI/WGL plumbing itself is sound end-to-end on the exact
JVM we ship for. It does **not** prove anything about MiniGL's
fullscreen-exclusive restriction or real Voodoo2 driver quirks (see dev/test
matrix above, tiers 2-3) — that's the next validation step before Phase 5
integration decisions are finalized.

### Gotchas anticipated going in (update this section as Phase 0 proceeds)
- MinGW exports `__stdcall` (`JNICALL`) symbols with `@N` name decoration by
  default; the JVM's `GetProcAddress` lookup expects the plain
  `Java_NativeGL_methodName` symbol. Link with `-Wl,--kill-at` to strip it.
- Win32 message pumping (`PeekMessage`/`DispatchMessage`) is thread-affine —
  must be called from the same thread that created the window. Same lesson
  gl2d learned on Irix with GLX contexts being current per-thread; keep all
  native calls for a given window on one Java thread.
- Re-assert `wglMakeCurrent` at the start of any GL-touching call rather
  than assuming it's still current, same as gl2d's `ensure_current()`
  pattern for GLX.
