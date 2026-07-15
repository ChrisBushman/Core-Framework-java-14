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
