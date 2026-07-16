# OpenRSC Client - Java 1.3 / Windows / OpenGL (experimental)

This is the experimental OpenGL-accelerated build of the OpenRSC client,
targeting Java 1.3.1 on Windows (and Voodoo2-era MiniGL/OpenGL ICDs in
particular - see `../../windows-java14-gl/PLAN.md` for the full design
background; this target is a straight port of that work onto the
`windows-java13` source tree, no rendering-logic changes). It builds and
runs alongside the original software-rendered client; nothing here changes
how that one works.

`NativeGL.dll` is the exact same binary as `windows-java14-gl`'s - JNI's
native ABI doesn't depend on the Java bytecode version, so no native
rebuild was needed for this port.

## Requirements

- Java 1.3.1 (the real JRE/JDK, not a later version pretending to be one).
- `NativeGL.dll` (already included next to `Open_RSC_Client.jar`) for the
  GL modes - a 32-bit native library, so a 32-bit Java 1.3.1 is required
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

Same experimental-build caveats as `windows-java14-gl` - see that target's
`README.md` and `../windows-java14-gl/PLAN.md` for the full list (sprite
occlusion near overlapping characters, some dialog backgrounds rendering
see-through, a couple of missing-art placeholder icons, GL performance on
real period hardware, fullscreen resolution requirements, and reduced
input-event fidelity vs. the normal windowed client). None of these are
Java-1.3-specific; this port hasn't introduced any new ones, but also
hasn't independently re-verified each of them under a 1.3 JVM yet.

None of the above affects the software renderer (`run-software.bat`),
which is unchanged from the original client.
