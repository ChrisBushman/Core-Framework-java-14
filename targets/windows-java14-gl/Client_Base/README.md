# OpenRSC Client - Java 1.4 / Windows / OpenGL (experimental)

This is the experimental OpenGL-accelerated build of the OpenRSC client,
targeting Java 1.4.2 on Windows (and Voodoo2-era MiniGL/OpenGL ICDs in
particular - see `../PLAN.md` for the full design background). It builds
and runs alongside the original software-rendered client; nothing here
changes how that one works.

## Requirements

- Java 1.4.2 (the real JRE/JDK, not a later version pretending to be one).
- `NativeGL.dll` (already included next to `Open_RSC_Client.jar`) for the
  GL modes - a 32-bit native library, so a 32-bit Java 1.4.2 is required
  for those. The software mode doesn't need it at all.
- A GL-capable graphics driver for the GL modes. This has only been tested
  under Wine's own OpenGL translation so far - real Voodoo2/MiniGL
  hardware behavior (especially fullscreen-exclusive mode) hasn't been
  verified yet.

## Quick start

Three launchers are included:

| Batch file               | What it runs                                                   |
|---------------------------|-----------------------------------------------------------------|
| `run-software.bat`         | The original software (CPU) renderer - no 3D card needed at all |
| `run-gl-windowed.bat`      | OpenGL renderer, in a normal window                              |
| `run-gl-fullscreen.bat`    | OpenGL renderer, real exclusive-mode fullscreen at 640x480 (letterboxed - the classic Voodoo2-era standard resolution) |

Each is a plain `java ... -jar Open_RSC_Client.jar` call with a specific
set of flags (see below) - copy one and adjust the flags if you want a
combination that isn't one of the three presets, e.g. fullscreen at the
game's own native resolution with no letterboxing at all (drop
`-Dorsc.gl.fullscreen.width`/`-Dorsc.gl.fullscreen.height`). Confirmed on
real Windows 98 + Voodoo 5 hardware that requesting the native 512x346
resolution directly as a display mode gets silently rejected (falls back
to windowed) since it isn't a real, enumerated VGA mode - 640x480 is a
standard mode any period-correct display/driver supports.

## Feature flags

All flags are standard JVM system properties, passed as `-D<name>=<value>`
before `-jar`. None of them are required - the client runs the same as
always (software renderer, windowed) with no flags at all.

| Flag | Values | Effect |
|------|--------|--------|
| `-Dorsc.renderer` | `gl` | Switches to the OpenGL renderer. Omit entirely (or set to anything else) for the original software renderer. |
| `-Dorsc.gl.fullscreen` | `true` | Real exclusive-mode fullscreen (a genuine display-mode switch, not just a maximized window) instead of a normal window. Only meaningful with `-Dorsc.renderer=gl`. If the display-mode switch itself fails for any reason, this falls back to windowed automatically. |
| `-Dorsc.gl.fullscreen.width` / `-Dorsc.gl.fullscreen.height` | e.g. `640` / `480` | Picks a specific fullscreen display resolution instead of the game's own native 512x346. The game content is centered (letterboxed/pillarboxed) within it, not stretched. Both must be set together; only takes effect alongside `-Dorsc.gl.fullscreen=true`. Leave unset for fullscreen at the game's native resolution with no letterboxing. 640x480 is the classic Voodoo2-era choice, if you want a period-accurate look. |
| `-Dorsc.fps` | `true` | Shows an "FPS: N" counter in the top-left corner, updated roughly once a second. Works with either renderer. |
| `-Dsun.java2d.noddraw` | `true` | Already included in every launcher above. Disables Java2D's DirectDraw acceleration, which is unreliable under Wine/old drivers - forces plain GDI rendering instead. Only affects the software renderer's own screen blit, not the GL renderer. |

Example - fullscreen at 640x480 with the FPS counter on:

```
java -mx256m -Dsun.java2d.noddraw=true -Dorsc.renderer=gl -Dorsc.gl.fullscreen=true -Dorsc.gl.fullscreen.width=640 -Dorsc.gl.fullscreen.height=480 -Dorsc.fps=true -jar Open_RSC_Client.jar
```

## Known limitations (GL renderer)

This is still an experimental build. Current known gaps, in rough order of
how likely you are to run into them:

- **Sprite occlusion near overlapping characters.** In some cases where two
  character sprites' screen positions overlap, wall occlusion for one of
  them can be wrong (visible through a wall that should hide it). Being
  investigated - see `../PLAN.md`'s "Sprite picking" section for the
  current state.
- **Some dialog backgrounds (e.g. "Logging Out") render fully see-through**
  instead of the dark, mostly-opaque look they have in the original
  client - they still show up, just without a proper dark backdrop behind
  the text.
- **A couple of menu icons (e.g. in account/social settings) show a
  pink/black checkerboard placeholder** instead of the intended icon - this
  is a missing art asset in this build's sprite archive, not a rendering
  bug, and affects the software renderer identically.
- **GL rendering performance on real period hardware can be far slower than
  the software renderer.** Confirmed on a real Windows 98 + Voodoo 5 machine:
  1-2 FPS in GL mode vs. ~40 FPS in software mode. Traced to the sprite-vs-
  wall occlusion pass reading back the GPU depth buffer once per visible
  character every frame - real pre-2000s 3D accelerators generally have no
  fast GPU-to-CPU readback path, so each read forces a full pipeline stall.
  Fixed by batching all sprites' depth reads into a single whole-screen read
  per frame instead of one per sprite (see `../PLAN.md`) - not yet
  re-verified on real hardware.
- **Fullscreen mode's display-mode switch needs a standard resolution.**
  Confirmed on real hardware that requesting the game's own native 512x346
  resolution directly fails silently (falls back to windowed) since it
  isn't a real, enumerated VGA mode - `run-gl-fullscreen.bat` now defaults
  to the standard 640x480 instead (see above), which should be
  universally supported, but this specific fallback hasn't been
  re-verified on real hardware yet either.
- **Some input isn't forwarded to the GL window**: mouse wheel and Alt now
  work; keyboard/mouse modifier fidelity is otherwise slightly reduced
  compared to the normal windowed client (no separate `mouseClicked`/
  `mouseEntered`/`mouseExited` events, though nothing in this client
  actually depends on those beyond what's already covered).

None of these affect the software renderer (`run-software.bat`), which is
unchanged from the original client.
