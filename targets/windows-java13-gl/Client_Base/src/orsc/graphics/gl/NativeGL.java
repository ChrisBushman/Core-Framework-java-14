package orsc.graphics.gl;

/**
 * JNI bridge to the Win32 + WGL native layer, promoted from the Phase 0
 * spike (../../../../../../gl-spike). Deliberately narrow: still opens its
 * own top-level window rather than integrating with AWT/Swing (see
 * ../../../../../PLAN.md Phase 5).
 *
 * beginFrame()/endFrame() are split (rather than the spike's single
 * renderFrame()) so GLSceneRenderer can submit real geometry between them
 * once Phase 3 lands.
 */
public class NativeGL {

	static {
		System.loadLibrary("NativeGL");
	}

	/**
	 * Creates a native window with a WGL context current on it. Returns a
	 * context handle, or 0 on failure. If `fullscreen` is true, first does
	 * a real `ChangeDisplaySettings(CDS_FULLSCREEN)` mode switch and
	 * creates a borderless topmost popup window covering it, instead of
	 * the normal titled/bordered window - the exclusive-mode display
	 * switch real Voodoo2 MiniGL ICDs historically required (see PLAN.md's
	 * "Hard constraint driving the design"), not just a borderless/
	 * maximized window. Falls back to the normal windowed path
	 * automatically if the mode switch itself fails (see the native
	 * implementation) rather than failing context creation outright.
	 * destroyContext() restores the original display mode if this context
	 * switched it.
	 *
	 * fullscreenWidth/fullscreenHeight pick which of two fullscreen
	 * resolution modes to use (ignored when `fullscreen` is false):
	 * - Mode 1 - either both <= 0, or equal to width/height: the display
	 *   mode matches width x height exactly (this engine's own internal
	 *   render resolution, inherited from the original RSC applet's
	 *   viewport) - no letterboxing/pillarboxing at all.
	 * - Mode 2 - a larger resolution, e.g. 640x480 (the classic Voodoo2-era
	 *   standard): the display switches to *that* mode instead, and
	 *   width x height is centered within it (letterboxed/pillarboxed, not
	 *   stretched) - matching how real Voodoo2-era games running below a
	 *   monitor's native resolution actually looked, rather than changing
	 *   this engine's own resolution to match directly (which would touch
	 *   UI layout/click-region math calibrated to the original size
	 *   throughout mudclient.java).
	 * All game-space pixel coordinates passed to other native calls (mouse
	 * input, picking, depth/color readback) are transparently adjusted for
	 * this centering internally - nothing else on the Java side needs to
	 * know the offset exists.
	 *
	 * fullscreenRefreshHz (-Dorsc.gl.fullscreen.refresh, see
	 * GLSceneRenderer.ensureContext()) is an explicit manual override for
	 * the fullscreen display mode's refresh rate. 0 (the default) matches
	 * whatever the desktop's own current refresh rate is instead - the
	 * previous, still-default behavior. Added after matching the desktop's
	 * rate alone still showed CRT sync-loss artifacts (rolling/torn image)
	 * in fullscreen on a real Voodoo 5: the VSA-100-era driver can
	 * negotiate/report a rate the monitor can't actually lock to, so being
	 * able to force a known-good one is the escape hatch. Ignored when
	 * `fullscreen` is false.
	 *
	 * zeroOffset is a diagnostic-only escape hatch (-Dorsc.gl.fullscreen.zerooffset,
	 * see GLSceneRenderer.ensureContext()): forces the GL viewport to start
	 * at (0,0) instead of centering it, even in mode 2. Added to isolate
	 * whether a real MiniGL ICD (confirmed: a Voodoo 5) mishandles a
	 * non-origin viewport - every real letterboxed-fullscreen attempt
	 * showed the screen tiled into two smaller side-by-side copies, while
	 * windowed mode (whose viewport is always at (0,0), since content size
	 * equals window size there) rendered correctly. Content renders
	 * uncentered in the top-left corner when this is on - not the intended
	 * final look, purely to test the one variable.
	 */
	public static native long createContext(int width, int height, String title, boolean fullscreen,
			int fullscreenWidth, int fullscreenHeight, int fullscreenRefreshHz, boolean zeroOffset);

	/** Resizes the GL viewport to match a resized window. */
	public static native void resizeContext(long ctx, int width, int height);

	/** Destroys the GL context and its window. */
	public static native void destroyContext(long ctx);

	/**
	 * Pumps pending Win32 messages for the window. Must be called from the
	 * same thread that called createContext(). Returns false once the
	 * window has been closed.
	 */
	public static native boolean pumpMessages(long ctx);

	/** Makes the context current and clears the color and depth buffers. */
	public static native void beginFrame(long ctx, float r, float g, float b);

	/** Flushes GL and swaps buffers, presenting the frame. */
	public static native void endFrame(long ctx);

	/**
	 * Replaces the flat ortho projection createContext() sets up by default
	 * with a real perspective frustum (glFrustum), and enables depth
	 * testing - Phase 3's replacement for Scene's CPU painter's-algorithm
	 * polygon sort.
	 */
	public static native void setPerspectiveFrustum(long ctx, float left, float right, float bottom, float top,
			float near, float far);

	/**
	 * Submits `vertexCount` vertices (vertexCount/3 triangles) in one call,
	 * all using the same texture (or none, if texId is 0 - flat per-vertex
	 * color only). `vertexData` is packed as 8 floats per vertex: x, y, z,
	 * r, g, b (0..1 per channel), u, v. u/v are ignored when texId is 0.
	 * Batched into a single native call per texture per frame rather than
	 * one call per triangle, since a game frame can have thousands of faces
	 * and JNI call overhead per triangle would dominate.
	 */
	public static native void drawTriangles(long ctx, int texId, float[] vertexData, int vertexCount);

	/**
	 * Uploads a decoded RGBA texture (4 bytes/pixel, row-major, top-to-
	 * bottom) and returns a GL texture id, or 0 on failure.
	 */
	public static native int uploadTexture(long ctx, int width, int height, byte[] rgba);

	/** Frees a texture created by uploadTexture(). */
	public static native void deleteTexture(long ctx, int texId);

	/**
	 * Overwrites an existing texture's pixels in place (glTexSubImage2D,
	 * no reallocation) - for textures that change every frame, like the
	 * software-rendered 2D UI layer Phase 4 composites over the 3D scene.
	 * width/height must match the texture's size from when it was created.
	 */
	public static native void updateTexture(long ctx, int texId, int width, int height, byte[] rgba);

	/**
	 * Draws `texId` as a full-screen alpha-blended quad, temporarily
	 * switching to an identity/ortho projection and disabling depth test
	 * and back-face culling for this one draw call, then restoring the
	 * perspective projection and GL state used for 3D geometry. For
	 * compositing the 2D UI layer over the already-drawn 3D scene - see
	 * GLSceneRenderer.presentUIOverlay().
	 *
	 * uMax/vMax: the fraction of the texture (0..1) that actually holds
	 * real content - real, pre-2003 OpenGL ICDs require power-of-two
	 * texture dimensions (unlike Wine's much more permissive translation,
	 * which silently accepted arbitrary sizes), so GLSceneRenderer uploads
	 * this texture padded up to the next power-of-two size and passes the
	 * real content's fraction of it here, rather than always sampling the
	 * full 0..1 range. Confirmed necessary by real-hardware testing on a
	 * Voodoo 5: without this, the GL window rendered solid blank white.
	 */
	public static native void drawUIOverlay(long ctx, int texId, float uMax, float vMax);

	/** pollInputEvent() return value meaning no event was queued. */
	public static final int INPUT_NONE = 0;
	/** Mouse moved; outData = {x, y, 0}. */
	public static final int INPUT_MOUSE_MOVE = 1;
	/** Mouse button pressed; outData = {x, y, button} (1 = left, 3 = right). */
	public static final int INPUT_MOUSE_DOWN = 2;
	/** Mouse button released; outData = {x, y, button}. */
	public static final int INPUT_MOUSE_UP = 3;
	/** Key pressed; outData = {0, 0, win32VirtualKeyCode}. */
	public static final int INPUT_KEY_DOWN = 4;
	/** Key released; outData = {0, 0, win32VirtualKeyCode}. */
	public static final int INPUT_KEY_UP = 5;
	/** Character typed (post keyboard-layout translation); outData = {0, 0, charCode}. */
	public static final int INPUT_CHAR = 6;
	/** Mouse wheel scrolled; outData = {x, y, notches} (positive = away from user, matching Win32's WM_MOUSEWHEEL sign). */
	public static final int INPUT_MOUSE_WHEEL = 7;

	/**
	 * Pops one queued input event captured by the native window's WndProc
	 * into outData (caller-provided int[3]), returning one of the INPUT_*
	 * constants above, or INPUT_NONE once the queue is empty. Call in a
	 * loop each frame to drain all pending events. Mouse/key coordinates
	 * and virtual-key codes are raw Win32 values - see
	 * ORSCApplet.pollGLInput() for how these map onto this engine's
	 * existing AWT-based input handling.
	 */
	public static native int pollInputEvent(long ctx, int[] outData);

	/**
	 * Enables GL_SCISSOR_TEST with a `size x size` box centered on (x, y)
	 * (top-left-origin, matching this engine's mouse coordinates - flipped
	 * to GL's bottom-left origin internally). For picking
	 * (GLSceneRenderer's color-ID render pass): restricts a redraw to a
	 * tiny area around the mouse so the GPU trivially rejects almost every
	 * triangle before rasterizing, keeping the extra draw calls picking
	 * needs cheap regardless of scene complexity. Also saves that region's
	 * current pixels internally (see restorePickPixels()) and clears it to
	 * black before returning, so the ID-color pass draws onto a clean,
	 * collision-free background.
	 */
	public static native void setPickScissor(long ctx, int x, int y, int size, int screenHeight);

	/**
	 * Reads back the single pixel at (x, y) (top-left-origin, flipped
	 * internally) as a packed 0xRRGGBB int. Used right after drawing
	 * color-ID geometry within a setPickScissor() region, to recover which
	 * ID (and therefore which face) is topmost at the mouse position.
	 */
	public static native int readPixelColor(long ctx, int x, int y, int screenHeight);

	/**
	 * Blits back the pixels setPickScissor() saved before the ID-color
	 * pass overwrote them - a tiny glDrawPixels covering only the
	 * scissored region, not a redraw of any scene geometry, so restoring
	 * the correct visual after picking costs about the same regardless of
	 * how complex the scene is. x/y/size/screenHeight must match the
	 * setPickScissor() call being undone; screenWidth is needed here (and
	 * not there) to set up the pixel-space ortho projection glRasterPos
	 * requires - see the native implementation.
	 */
	public static native void restorePickPixels(long ctx, int x, int y, int size, int screenWidth, int screenHeight);

	/** Disables the scissor region set by setPickScissor(). */
	public static native void clearPickScissor(long ctx);

	/**
	 * Reads back the depth buffer for a `width` x `height` rect at (x, y)
	 * (top-left-origin, flipped internally, matching setPickScissor) into
	 * `outDepths` (caller-allocated, size >= width*height), as raw
	 * non-linear window-space depth values in [0, 1]. Row 0 of outDepths is
	 * the *bottom* image row of the rect, not the top - glReadPixels itself
	 * returns rows bottom-to-top; see GLSceneRenderer's caller for the
	 * index flip. Used for sprite-vs-wall occlusion: comparing a sprite
	 * billboard's own depth against the real geometry pass's already-
	 * written depth buffer at each pixel the sprite covers, so a wall in
	 * front of a character can hide it instead of the sprite always
	 * drawing on top regardless of what's actually closer.
	 */
	public static native void readDepthRect(long ctx, int x, int y, int width, int height, int screenHeight,
			float[] outDepths);

	/**
	 * Reads back the color buffer for a `width` x `height` rect at (x, y)
	 * (top-left-origin, flipped internally, matching readDepthRect) into
	 * `outColors` (caller-allocated, size >= width*height), as packed
	 * 0xRRGGBB ints. Row 0 of outColors is the *bottom* image row of the
	 * rect, not the top - same as readDepthRect, see its doc comment.
	 * Used to pull a just-rendered GL frame back into the CPU pixelData
	 * buffer: GLSceneRenderer.endScene() submits geometry straight to this
	 * native context and never writes pixelData itself (unlike the
	 * software Scene renderer), so anything that needs the rendered frame
	 * as pixel data afterward - e.g. the login-screen carousel's
	 * GraphicsController.storeSpriteVert() snapshot - needs this to get
	 * real content instead of stale leftover pixelData.
	 */
	public static native void readColorRect(long ctx, int x, int y, int width, int height, int screenHeight,
			int[] outColors);
}
