package orsc.graphics.three;

import orsc.MiscFunctions;
import orsc.graphics.gl.NativeGL;
import orsc.graphics.two.GraphicsController;
import orsc.util.FastMath;

import java.util.ArrayList;
import java.util.List;

/**
 * GPU-backed SceneRenderer, built on the Win32/WGL NativeGL bridge (see
 * orsc.graphics.gl.NativeGL and Client_Base/native-gl/). endScene()
 * submits real model geometry: every added model is camera-transformed
 * with RSModel.rotate1024() - the same method Scene uses, reused as-is
 * rather than reimplemented - and its faces are fan-triangulated and
 * submitted to GL with GL_DEPTH_TEST doing the visibility ordering that
 * Scene's CPU painter's-algorithm sort used to do. Faces whose texture
 * (faceTextureFront/Back) is a real resource id are texture-mapped; faces
 * whose texture is a negative packed color (the other thing
 * faceTextureFront/Back/resourceToColor can hold in this engine - most
 * scenery/furniture faces are this, not a bitmap) stay flat-shaded, which
 * is correct for them, not a shortfall. See ../../../PLAN.md.
 *
 * Back-face culling and lighting, both added on top of the above:
 * - GL_CULL_FACE is enabled (see NativeGL.setPerspectiveFrustum). Faces
 *   with a real faceTextureBack (not Scene.TRANSPARENT) are genuinely
 *   double-sided in this engine, so they're emitted twice: once in their
 *   normal winding with the front texture/color, once with reversed
 *   winding using the back texture/color. Whichever copy faces the camera
 *   is the one culling keeps - no per-face state toggling needed, and it
 *   stays compatible with batching triangles by texture across the whole
 *   frame. The winding convention (glFrontFace) was chosen by reasoning
 *   about the coordinate transform in putVertex() rather than read
 *   directly out of Scene, so it may need the one-line flip noted there.
 * - Lighting reuses RSModel's own computed diffuse values
 *   (faceDiffuseLight[]/vertDiffuseLight[]/vertLightOther[]/diffuseParam1)
 *   rather than reimplementing the underlying normal/light-direction math,
 *   because the raw inputs to that math (faceNormX/Y/Z, diffuseDirX/Y/Z,
 *   diffuseMag) are declared `private` on RSModel, not package-private -
 *   genuinely inaccessible from here, not just unused. setFrustum()'s
 *   6-arg overload (Scene's "relight models from index" call) now calls
 *   model.setDiffuseLight(...) with the exact same argument forwarding
 *   Scene uses. Scene itself turns those values into a table index
 *   picking one of a handful of pre-darkened texture copies
 *   (see the `& 0xF8F8FF`-adjacent code read during the magenta-texture
 *   investigation); GLSceneRenderer instead treats them as a continuous
 *   brightness multiplier for GL's per-vertex color (see
 *   lightScalarToBrightness()) - smoother than the original's discrete
 *   banding, which fits GL's Gouraud interpolation better than replicating
 *   the exact table-lookup would. The normalization constant is an
 *   estimate (Scene's own scale factors depend on those same private
 *   fields), not a derived value - a visual-tuning candidate like the
 *   winding convention above.
 * - presentUIOverlay() (Phase 4, called from ORSCApplet.draw(), not part
 *   of the SceneRenderer interface - see its own doc comment) composites
 *   GraphicsController's software-rendered 2D UI layer over whatever this
 *   class drew to the frame, using a black-color-key for transparency and
 *   real alpha blending (not the alpha-test cutout used for 3D textures -
 *   there's no per-triangle sort-order problem for a single full-screen
 *   quad drawn last). endScene() no longer swaps buffers itself; the
 *   frame is only presented once presentUIOverlay() runs, since mudclient
 *   draws 2D UI *after* calling endScene() and both need to land in the
 *   same frame.
 * - drawSprite()/drawSpriteBillboards() render players, NPCs, projectiles,
 *   and items - all 2D sprite billboards in this engine, not 3D models
 *   (traced through drawPlayer()/drawNPC(), which draw pixels straight into
 *   pixelData via getSurface().drawSpriteClipping() - the 2D layer, not
 *   this class). Before this, everything routed through Scene.drawSprite()
 *   was completely invisible: that method was a stub returning -1 and
 *   doing nothing. See drawSpriteBillboards()'s doc comment for the
 *   projection math, reused from Scene's real source rather than
 *   reimplemented independently.
 * - setMidpoints() is also no longer a stub. Scene.setMidpoints() (called
 *   by mudclient once during scene setup) sets rot1024_vp_src from
 *   mudclient's own m_qd field, which defaults to 9 - not the 8 this class
 *   wrongly hardcoded from Phase 3 onward by copying Scene's *constructor*
 *   default without noticing setMidpoints() overwrites it. That value
 *   directly sets this renderer's field of view (see ensureContext()), so
 *   the mismatch wasn't cosmetic - it explained "the whole game looks more
 *   zoomed out than the original," not just a sprite-specific symptom.
 *
 * - Picking (b(int)/b(byte)/getQB(byte), click-to-move/interact) is real
 *   now too - see performPicking()'s doc comment. World/scenery geometry
 *   goes through a GL color-ID render pass, not a port of Scene's
 *   scanline-edge hit test (which is a large, intricate part of Scene.java
 *   - reusing the GPU's own rasterizer/depth-test to answer "what's under
 *   the mouse" fits this project's whole direction better than duplicating
 *   that CPU algorithm a second time). Sprite billboards (players/NPCs/
 *   items) are also pickable, added in a later round - see
 *   performSpritePicking()'s doc comment for why that one's a plain CPU
 *   rect+depth check instead of a second GL pass.
 *
 * One thing not yet done, unrelated to the above:
 * - This engine has no stored per-vertex UV data anywhere (checked: not
 *   in RSModel, not in Polygon) - texture mapping must happen procedurally
 *   during Scene's scanline fill, which GLSceneRenderer doesn't reuse.
 *   cornerUV() below assigns each face's own corners a unit-square UV in
 *   insertion order instead. That's not extracted ground truth, it's an
 *   approximation - but it's exact for the common case this matters most
 *   for (axis-aligned ground/floor tile quads, inserted corner-by-corner
 *   in a consistent winding).
 */
public final class GLSceneRenderer implements SceneRenderer {

	// Scene's rot1024_zTop is always 5 (a constant in Scene's own source).
	// rot1024_vp_src is NOT always 8 despite that being Scene's constructor
	// default - Scene.setMidpoints() (mudclient calls it with mudclient's
	// own m_qd field, which defaults to 9, not 8) overwrites it before any
	// real rendering happens. Using the wrong constant here was silently
	// wrong from Phase 3 onward: it directly sets this renderer's field of
	// view (see ensureContext()), so a mismatched value here doesn't just
	// affect sprites, it explains "everything looks more zoomed out than
	// the original" for the whole 3D scene. 8 is kept only as the fallback
	// before setMidpoints() has run once.
	private int vpSrc = 8;
	private static final int Z_TOP = 5;
	private static final float Z_FAR = 10000f;

	// Scene's m_Zb/m_Nb: added to centered projected coordinates (0 = screen
	// center, matching vertexParam6/vertexParam2's own origin) to convert
	// them into top-left-origin screen pixel coordinates - needed only for
	// sprite billboards (drawSpriteBillboards()), since the 3D mesh path
	// hands vertXRot/Y/Z straight to GL's own projection and never computes
	// 2D screen pixels manually. Set by setMidpoints(), same call that fixes
	// vpSrc above.
	private int screenMidX;
	private int screenMidY;

	// Vertex layout for NativeGL.drawTriangles: x, y, z, r, g, b, u, v.
	private static final int FLOATS_PER_VERTEX = 8;

	// Brightness normalization for faceDiffuseLight/vertDiffuseLight - see
	// the class doc comment. Empirically chosen, not derived; adjust these
	// three if lighting looks too flat/too extreme.
	private static final float LIGHT_NORM = 256f;
	private static final float LIGHT_MIN = 0.2f;
	private static final float LIGHT_MAX = 1.4f;


	private final GraphicsController graphics;
	private final List models = new ArrayList();
	private long ctx = 0;
	private boolean frustumSet = false;

	// Per-model "is this whole model off-screen this frame" flags, computed
	// once in endScene() right after rotate1024() and reused by both the
	// face-count/face-fill passes there and performPicking()'s own model/
	// face loop - see isModelOffScreen()'s doc comment for what this saves
	// and why it's safe.
	private boolean[] modelOffScreen = new boolean[0];

	// Per-frame vertex-submission scratch buffers for endScene(), pooled
	// and reused across frames (grown when too small, never reallocated
	// just because this frame needs less than last frame) rather than
	// freshly `new`'d every frame like modelOffScreen above still is.
	// Added after profiling on real hardware highlighted GC churn from
	// these - tens of thousands of floats reallocated roughly twice a
	// frame (once for the real draw, again whenever picking refreshes) on
	// a single-core period CPU with an older, less-tuned generational GC.
	// Safe to over-allocate: every consumer is passed the real element
	// count separately (flatVertexCount/texVertexCounts[t]/vertexCount)
	// rather than relying on array.length, so unused trailing capacity
	// from a larger previous frame is simply never read.
	private float[] flatBufPool;
	private float[][] texBufPool;
	private int[] texVertexCountsPool;
	private int[] texOffsetsPool;
	private float[] pickBufPool;

	// Same pooling reasoning as above, for drawSpriteBillboards()'s
	// per-sprite scratch arrays - smaller (sized to visible sprite count,
	// not vertex count) but reallocated up to twice a frame just the same.
	private int[] spriteScreenXsPool;
	private int[] spriteTopYsPool;
	private int[] spriteWidthsPool;
	private int[] spriteHeightsPool;
	private int[] spriteOverlayMovementsPool;
	private int[] spriteTopPixelSkewsPool;
	private int[] spriteBaseDepthsPool;
	private int[] spriteTopDepthsPool;
	private boolean[] spriteVisiblePool;
	private int[] spriteOrderPool;

	// Set at construction as a reasonable starting value, then overwritten
	// authoritatively by setMidpoints() - see that method.
	private int halfWidth;
	private int halfHeight;

	// Sprite billboards (players, NPCs, projectiles, ground items...) - see
	// drawSprite()/drawSpriteBillboards(). Mirrors Scene's m_T: a dedicated
	// RSModel holding one 2-vertex "pole" per sprite (base to base+height),
	// reused via resetFaceVertHead() every frame rather than reallocated,
	// so the SAME rotate1024()/vertexParam6/vertexParam2 machinery the 3D
	// mesh path already relies on projects these too - not reimplemented.
	private RSModel spriteBillboardModel;
	private final List spriteDraws = new ArrayList();

	// Per-pixel "what color did sprite compositing leave this pixel as,
	// this frame" record - -1 where no sprite rect touched it this frame.
	// See drawSpriteBillboards()'s doc comment for why this stores the
	// actual expected color rather than just a touched/not-touched flag:
	// mudclient draws its own 2D UI (dialogs, panels...) into this same
	// pixelData buffer *after* endScene() returns, and can legitimately
	// paint plain black over a pixel a sprite's rect happened to occupy
	// earlier the same frame - recording the color lets presentUIOverlay()
	// notice pixelData no longer matches what sprite compositing left there
	// and fall back to the plain rule instead of wrongly keeping that pixel
	// opaque. Lazily sized to graphics.width2 * graphics.height2 and fully
	// reset (-1) every frame by ensureSpriteExpectedColor() before any
	// sprite touches its own rect, so there's never stale state from a
	// sprite that moved or disappeared.
	private int[] spriteExpectedColor;

	// Per-pixel "which sprite (index into spriteDraws for this frame)
	// actually drew real content here" record - -1 where no sprite has
	// drawn anything of its own to this pixel (the overwhelming majority
	// of the screen, and of any given sprite's own rectangular bounds -
	// real silhouettes are irregular, not rectangles). See
	// occludeAgainstDepthBuffer()'s and markSpriteOwner()'s doc comments
	// for why this exists and how it's populated: without it, one sprite's
	// occlusion pass could blindly overwrite a *different* sprite's
	// already-drawn pixels (body or nametag) just because its own bounding
	// rect happens to reach that screen position - confirmed by the user
	// seeing their own nametag partially erased whenever another sprite (a
	// goblin standing past a doorway) was positioned so its rect overlapped
	// where the nametag renders.
	//
	// Two earlier, less precise versions of this didn't hold up: claiming
	// nothing at all (no protection) let exactly that nametag-erasure bug
	// through; claiming a sprite's *entire* rectangular bounds
	// unconditionally (regardless of whether real content was actually
	// there) fixed that but caused the opposite regression - a nearer
	// sprite's blanket claim over its own mostly-empty bounding box could
	// "steal" pixels from a farther, unrelated sprite's own body-vs-wall
	// occlusion, since two characters simply standing near each other on
	// screen is common and has nothing to do with nametags. Gating the
	// claim on "did this sprite draw real content here" (markSpriteOwner())
	// means gap/background overlap between two rects - the common case -
	// claims nothing and can't interfere with anyone else.
	//
	// Claimed in drawSpriteBillboards()'s draw pass (the same far-to-near
	// sorted order draws happen in), so a later (nearer) sprite's claim
	// correctly overrides an earlier (farther) one's for any nametag-strip
	// pixels they share. Reset (-1) every frame by
	// ensureSpriteOwnerIndex(), same lifecycle as spriteExpectedColor.
	private int[] spriteOwnerIndex;

	// Depth-buffer snapshot for the current frame's occlusion pass - fetched
	// once per frame (see drawSpriteBillboards()) rather than once per
	// sprite. An earlier version had occludeAgainstDepthBuffer() call
	// NativeGL.readDepthRect() itself, once per visible sprite - fine under
	// Wine's OpenGL translation, but confirmed on real hardware (a Voodoo 5)
	// to be the actual cause of a 1-2 FPS crawl: real pre-2000s 3D
	// accelerators generally have no fast GPU->CPU readback path (their
	// architecture optimized for write bandwidth to the framebuffer, not
	// reading back from it), so every glReadPixels call forces a full
	// pipeline sync/stall - with N visible sprites, N stalls every frame.
	// Batching to one read per frame cuts that to a single stall regardless
	// of how many sprites are on screen.
	//
	// Sized/positioned to the union bounding box of this frame's visible
	// sprite rects (sceneDepthBoundX/Y/Width/Height), not the whole canvas -
	// a later refinement after profiling showed this single read was the
	// largest per-frame cost band even after the above batching, and
	// sprites typically cover a small fraction of the screen. Cost of a
	// readback on this era of hardware scales with how much data crosses
	// the stall, not just call count, so shrinking the rect shrinks the
	// cost proportionally. occludeAgainstDepthBuffer() indexes into this
	// buffer relative to sceneDepthBoundX/Y, not absolute canvas
	// coordinates. Reused/grown across frames rather than reallocated new
	// every frame, same reasoning as endScene()'s flatBuf/texBufs pooling.
	private float[] sceneDepthBuffer;
	private int sceneDepthBoundX;
	private int sceneDepthBoundY;
	private int sceneDepthBoundWidth;
	private int sceneDepthBoundHeight;

	// How far above a sprite's own rect (topY) mudclient.drawPlayer() draws
	// the floating nametag/clan tag (confirmed: name at topY-14, clan tag
	// at topY-5) - with margin for font height/descenders. Only affects
	// spriteOwnerIndex's protection zone (see its doc comment), not the
	// sprite's own fill/draw/occlusion rect - nametags still aren't
	// depth-tested against walls themselves, only protected from being
	// erased by an unrelated sprite's occlusion pass.
	private static final int NAMETAG_PROTECT_ABOVE = 20;

	private static final class SpriteDraw {
		int entityId;
		int baseVertexIndex;
		int topVertexIndex;
		int widthRaw;
		int heightRaw;

		// Pick-related fields - see drawSprite()'s doc comment for
		// faceIndex (the pick index itself is consumed immediately into
		// spriteBillboardModel.facePickIndex[], not kept here too), and
		// drawSpriteBillboards()'s first pass for where the rest get filled
		// in (mirrors the local screenXs[]/topYs[]/etc. arrays that method
		// already computes per sprite, kept on the SpriteDraw itself too so
		// performPicking() - which runs after drawSpriteBillboards() - can
		// look them up by sprite without needing its own parallel arrays).
		int faceIndex;
		int screenX;
		int topY;
		int width;
		int height;
		int baseDepth;
		int topDepth;
		boolean visible;
	}

	// Picking (click-to-move / click-to-interact) - see performPicking()'s
	// doc comment for the full approach (a GL color-ID pass, not a port of
	// Scene's scanline-based hit test). World-geometry faces only for now;
	// sprite billboards (players/NPCs/items) are a documented gap.
	private int mouseX;
	private int mouseY;
	private static final int PICK_SCISSOR_SIZE = 3;
	private final List pickFaceRefs = new ArrayList();
	private final RSModel[] pickHitModels = new RSModel[1];
	private final int[] pickHitFaceIndices = new int[1];
	private int pickHitCount = 0;

	// Throttling for the expensive world-geometry GL picking pass - see
	// performPicking()'s doc comment for why this exists and the trade-off
	// it makes. Sentinels (MIN_VALUE) force the very first call to always
	// do a real pick rather than trusting an uninitialized "unchanged"
	// comparison.
	private int lastPickedMouseX = Integer.MIN_VALUE;
	private int lastPickedMouseY = Integer.MIN_VALUE;
	private int pickRefreshCounter = 0;
	private boolean lastFrameHadSpriteHit = false;
	private static final int PICK_REFRESH_INTERVAL_FRAMES = 5;

	// Temporary profiling instrumentation - see reportProfile()'s doc
	// comment. Remove once the profiling pass it was added for is done.
	private static final boolean PROFILE = true;
	private static final int PROFILE_REPORT_EVERY = 30;
	private int profileFrameCount = 0;
	private long profileRotateMs;
	private long profileFillMs;
	private long profileDrawMs;
	private long profileSpriteMs;
	private long profileSpriteOcclusionMs;
	// Finer breakdown of profileSpriteOcclusionMs, mirroring the pick.total
	// build/gl/readback/restore split below - added after real-hardware
	// profiling (a Voodoo 5) showed occlusion was the single largest
	// per-frame cost (~1/3 of total frame time) with no way to tell
	// whether the single batched NativeGL.readDepthRect() call itself or
	// the CPU-side per-sprite pixel loops were the actual dominant piece.
	private long profileOcclusionReadMs;
	private long profileOcclusionCpuMs;
	private long profileOcclusionFinalizeMs;
	private long profilePickTotalMs;
	private long profilePickBuildMs;
	private long profilePickGLMs;
	private long profilePickReadbackMs;
	private long profilePickRestoreMs;
	private long profileUiConvertMs;
	private long profileUiUploadMs;
	private long profileUiDrawMs;
	private long profileTotalMs;
	private int profileTriVerts;
	private int profilePickVerts;

	private static final class PickFaceRef {
		RSModel model;
		int faceIndex;
	}

	// Tracks whether this logical frame's beginFrame()/clear already
	// happened (in endScene()), so presentUIOverlay() knows whether it
	// needs to do its own - see presentUIOverlay()'s doc comment.
	private boolean beginFrameDone = false;

	// The 2D UI overlay texture (Phase 4), re-uploaded/updated every frame
	// since the UI changes constantly - see presentUIOverlay().
	private int uiTextureId = 0;
	private int uiTextureWidth = -1;
	private int uiTextureHeight = -1;

	// Last frame's pixelData content (a real copy, not just the same array
	// reference - pixelData is mutated in place, so a reference alone would
	// always compare "unchanged") - see presentUIOverlay()'s doc comment
	// for why this exists: skips the RGBA conversion + texture upload
	// entirely on frames where the 2D UI genuinely didn't change at all,
	// which profiling showed was costing real time every single frame
	// regardless.
	private int[] previousPixelData;

	/** Smallest power of two >= v (v > 0) - see presentUIOverlay()'s doc comment. */
	private static int nextPowerOfTwo(int v) {
		int p = 1;
		while (p < v) {
			p <<= 1;
		}
		return p;
	}

	// Per-resource-id texture data, mirroring Scene's loadTexture layout
	// (indexed-color bitmap + its palette). palettes/indexedPixels/
	// highResFlags are populated as loadTexture() is called; glTextureIds
	// is filled in lazily, one GL upload per resource id the first time it
	// is actually drawn (see ensureTextureUploaded()).
	private int[][] palettes;
	private byte[][] indexedPixels;
	private int[] highResFlags;
	private int[] glTextureIds;

	private int fogLandscapeDistance;
	private int fogEntityDistance;
	private int fogZFalloff;
	private int fogSmoothingStartDistance;

	// rotate1024()'s camera-space transform inputs, computed by setCamera()
	// the same way Scene.setCamera() does.
	private int rot1024_off_x;
	private int rot1024_off_y;
	private int rot1024_off_z;
	private int cameraProjX;
	private int cameraProjY;
	private int cameraProjZ;

	public GLSceneRenderer(GraphicsController graphics, int worldSize, int maxPolygonCount, int maxSprites) {
		this.graphics = graphics;
		this.halfWidth = graphics.width2 / 2;
		this.halfHeight = graphics.height2 / 2;
		// Matches Scene's own `this.m_T = new RSModel(var4 * 2, var4);` in
		// vertex/face counts (two vertices per sprite), but NOT in
		// constructor choice: Scene's public 2-arg constructor leaves
		// dontComputeDiffuse/m_c both false, which - discovered via a real
		// crash, not anticipated - makes RSModel.computeNormals() run on
		// every rotate1024() call and unconditionally index a face's 3rd
		// vertex (var3[2]) assuming a triangle. Sprite "poles" only have 2
		// vertices (base, top), so that crashed with an
		// ArrayIndexOutOfBoundsException the first frame any sprite was
		// drawn. This engine's own package-private 7-arg constructor sets
		// m_c/dontComputeDiffuse directly, which together make
		// computeNormals() skip its body entirely - exactly right, since
		// billboards need vertXRot/Y/Z and vertexParam6/2 from rotate1024's
		// rotation step, never normals/lighting. The other three flags
		// (m_v, m_db, m_b) are passed as `false` to match what the 2-arg
		// public constructor already left them at by default - no other
		// behavior changes.
		this.spriteBillboardModel = new RSModel(maxSprites * 2, maxSprites, false, true, true, false, false);
	}

	/**
	 * Exposes the native window handle so ORSCApplet can poll and forward
	 * its input events (Phase 5) - see ORSCApplet.pollGLInput(). 0 if the
	 * window hasn't been created yet (before the first endScene() or
	 * presentUIOverlay() call).
	 */
	public long getNativeContext() {
		return ctx;
	}

	private void ensureContext() {
		if (ctx == 0) {
			// Lazily created on first endScene() call rather than in the
			// constructor, so it runs on whichever thread actually renders
			// frames - same lesson gl2d learned with GLX contexts.
			//
			// -Dorsc.gl.fullscreen=true gates the real CDS_FULLSCREEN
			// exclusive-mode path real Voodoo2 MiniGL ICDs historically
			// required (see NativeGL.createContext()'s doc comment and
			// PLAN.md's "Hard constraint driving the design") - off by
			// default since it can't be meaningfully exercised under
			// Wine/macOS, only dgVoodoo2 or real hardware.
			//
			// -Dorsc.gl.fullscreen.width/.height pick the fullscreen
			// resolution ("mode 2" - see createContext()'s doc comment) -
			// unset (or 0) means "mode 1", the engine's own native
			// width2 x height2 with no letterboxing. Both must be set
			// together; a classic Voodoo2-era choice is 640x480.
			boolean fullscreen = "true".equals(System.getProperty("orsc.gl.fullscreen"));
			int fullscreenWidth = Integer.getInteger("orsc.gl.fullscreen.width", 0).intValue();
			int fullscreenHeight = Integer.getInteger("orsc.gl.fullscreen.height", 0).intValue();
			// -Dorsc.gl.fullscreen.refresh - manual override for the
			// fullscreen display mode's refresh rate (0 = match the
			// desktop's current rate). See NativeGL.createContext()'s doc
			// comment for why this exists (real Voodoo 5 sync-loss).
			int fullscreenRefreshHz = Integer.getInteger("orsc.gl.fullscreen.refresh", 0).intValue();
			// -Dorsc.gl.fullscreen.zerooffset - diagnostic-only, see
			// NativeGL.createContext()'s doc comment for what this isolates.
			boolean zeroOffset = "true".equals(System.getProperty("orsc.gl.fullscreen.zerooffset"));
			ctx = NativeGL.createContext(graphics.width2, graphics.height2, "OpenRSC (GL, experimental)", fullscreen,
					fullscreenWidth, fullscreenHeight, fullscreenRefreshHz, zeroOffset);
		}
		if (ctx != 0 && !frustumSet) {
			// glFrustum bounds chosen so that, at z = Z_TOP, the frustum
			// edge lands exactly where Scene's own screen-space projection
			// (vertexParam6/vertexParam2 = coord * 256 / z, i.e. scale
			// 1/2^vpSrc) would place it: right = zNear * halfWidth / 2^vpSrc.
			float scale = (float) Z_TOP / (float) (1 << vpSrc);
			float right = halfWidth * scale;
			float top = halfHeight * scale;
			NativeGL.setPerspectiveFrustum(ctx, -right, right, -top, top, Z_TOP, Z_FAR);
			frustumSet = true;
		}
	}

	public void addModel(RSModel mod) {
		if (mod != null && !models.contains(mod)) {
			models.add(mod);
		}
	}

	public void removeModel(RSModel model) {
		models.remove(model);
	}

	public void removeAllGameObjects(boolean arg) {
		models.clear();
	}

	/**
	 * True if `model` is entirely outside the view frustum this frame,
	 * i.e. skipping its face-count/face-fill work in endScene() (and its
	 * pick-geometry emission in performPicking()) can't lose any visible
	 * geometry. Re-derives the same frustum planes NativeGL.setPerspectiveFrustum()
	 * was set up with (see ensureContext()'s doc comment: right = halfWidth
	 * * Z_TOP / 2^vpSrc, top = halfHeight * Z_TOP / 2^vpSrc, both scaling
	 * linearly with depth for a symmetric perspective frustum) and tests
	 * model.vertXRot/vertYRot/vertZRot directly - the exact camera-space
	 * coordinates putVertex() sends to GL, not the sprite-billboard-specific
	 * vertexParam6/vertexParam2 screen projection, so there's no coordinate-
	 * space translation to get wrong here.
	 *
	 * Added after profiling showed the face-count+face-fill passes
	 * (endScene()'s "fill", one of the two largest per-frame costs measured
	 * this session) processing every model every frame regardless of
	 * whether it's actually visible - a direct consequence of this class
	 * deliberately forcing RSModel.rotate1024()'s own frustum-cull check to
	 * always pass (see the class doc comment) and relying on GL's hardware
	 * clipping instead. That's still correct and still happens - this
	 * doesn't change what GL clips - it just avoids the CPU cost of
	 * counting/emitting triangles for models GL was always going to
	 * discard entirely anyway.
	 *
	 * Deliberately conservative in two ways, both correctness-preserving by
	 * construction (worst case: a model that could have been culled isn't,
	 * same cost as before this method existed - never the other way
	 * around):
	 * - A model with any vertex at or behind the near plane (vertZRot <=
	 *   Z_TOP) is never culled here, even if every *other* vertex is
	 *   comfortably out of frame - a triangle straddling the near plane
	 *   needs real clipping, which screen-space bounds on the far side of
	 *   that vertex can't safely reason about (the classic bug this class
	 *   of check has to avoid: something behind the camera can project to
	 *   coordinates that *look* off-screen or on-screen by coincidence).
	 * - Culls only when *every* vertex is confirmed past a single frustum
	 *   plane (all-right, all-left, all-above, or all-below) - not a
	 *   general separating-axis test against the frustum's corners, which
	 *   would catch more cases but is real additional complexity for
	 *   marginal extra benefit over what this already saves.
	 */
	private boolean isModelOffScreen(RSModel model) {
		int count = model.vertHead;
		if (count == 0) {
			return true;
		}
		int[] xr = model.vertXRot;
		int[] yr = model.vertYRot;
		int[] zr = model.vertZRot;

		boolean allBehindNear = true;
		boolean anyAtOrBehindNear = false;
		boolean allBeyondFar = true;
		for (int i = 0; i < count; ++i) {
			int z = zr[i];
			if (z > Z_TOP) {
				allBehindNear = false;
			} else {
				anyAtOrBehindNear = true;
			}
			if (z < Z_FAR) {
				allBeyondFar = false;
			}
		}
		if (allBehindNear || allBeyondFar) {
			return true;
		}
		if (anyAtOrBehindNear) {
			// Straddles the near plane - can't safely screen-space cull
			// (see this method's doc comment) - always submit.
			return false;
		}

		boolean allOutsideRight = true;
		boolean allOutsideLeft = true;
		boolean allOutsideTop = true;
		boolean allOutsideBottom = true;
		for (int i = 0; i < count; ++i) {
			int x = xr[i] << vpSrc;
			int y = yr[i] << vpSrc;
			int z = zr[i];
			int xBound = halfWidth * z;
			int yBound = halfHeight * z;
			if (x <= xBound) {
				allOutsideRight = false;
			}
			if (x >= -xBound) {
				allOutsideLeft = false;
			}
			if (y <= yBound) {
				allOutsideTop = false;
			}
			if (y >= -yBound) {
				allOutsideBottom = false;
			}
		}
		return allOutsideRight || allOutsideLeft || allOutsideTop || allOutsideBottom;
	}

	/** Grow-only pool helper - see flatBufPool's doc comment for the pattern. */
	private static int[] growIntPool(int[] pool, int needed) {
		return (pool == null || pool.length < needed) ? new int[needed] : pool;
	}

	/** Grow-only pool helper - see flatBufPool's doc comment for the pattern. */
	private static boolean[] growBooleanPool(boolean[] pool, int needed) {
		return (pool == null || pool.length < needed) ? new boolean[needed] : pool;
	}

	public void endScene(int arg) {
		long profT0 = PROFILE ? System.currentTimeMillis() : 0;
		ensureContext();
		if (ctx == 0) {
			return;
		}
		if (!NativeGL.pumpMessages(ctx)) {
			return;
		}
		NativeGL.beginFrame(ctx, 0.05f, 0.05f, 0.08f);

		// Force RSModel.rotate1024()'s built-in frustum-cull check to
		// always pass - see the class doc comment for why.
		MiscFunctions.frustumMinX = Integer.MAX_VALUE;
		MiscFunctions.frustumMinY = Integer.MAX_VALUE;
		MiscFunctions.frustumNearZ = Integer.MAX_VALUE;
		MiscFunctions.frustumMaxX = Integer.MIN_VALUE;
		MiscFunctions.frustumMaxY = Integer.MIN_VALUE;
		MiscFunctions.frustumFarZ = Integer.MIN_VALUE;

		int modelCount = models.size();
		if (modelOffScreen.length != modelCount) {
			modelOffScreen = new boolean[modelCount];
		}
		for (int m = 0; m < modelCount; ++m) {
			RSModel model = (RSModel) models.get(m);
			model.rotate1024(rot1024_off_y, vpSrc, rot1024_off_x, (byte) -122, rot1024_off_z,
					cameraProjY, cameraProjZ, cameraProjX, Z_TOP);
			// Needs this frame's rotate1024() output (vertXRot/vertYRot/
			// vertZRot) - see isModelOffScreen()'s doc comment.
			modelOffScreen[m] = isModelOffScreen(model);
		}
		long profT1 = PROFILE ? System.currentTimeMillis() : 0;

		int textureSlots = (palettes != null) ? palettes.length : 0;
		if (texVertexCountsPool == null || texVertexCountsPool.length != textureSlots) {
			texVertexCountsPool = new int[textureSlots];
			texOffsetsPool = new int[textureSlots];
			texBufPool = new float[textureSlots][];
		} else {
			java.util.Arrays.fill(texVertexCountsPool, 0);
		}
		int[] texVertexCounts = texVertexCountsPool;
		int flatVertexCount = 0;

		// Pass 1: count vertices per bucket (flat, or per texture id) so
		// pass 2 can fill pre-sized arrays with no dynamic growth. Front
		// and back textures are counted independently - see the class doc
		// comment on double-sided faces.
		for (int m = 0; m < modelCount; ++m) {
			RSModel model = (RSModel) models.get(m);
			if (!model.m_dc || modelOffScreen[m]) {
				continue;
			}
			for (int f = 0; f < model.faceHead; ++f) {
				int n = model.faceIndexCount[f];
				if (n < 3) {
					continue;
				}
				int tris = (n - 2) * 3;
				int front = model.faceTextureFront[f];
				int back = model.faceTextureBack[f];
				if (front != Scene.TRANSPARENT) {
					int bkt = textureBucketOf(front, textureSlots);
					if (bkt >= 0) {
						texVertexCounts[bkt] += tris;
					} else {
						flatVertexCount += tris;
					}
				}
				if (back != Scene.TRANSPARENT) {
					int bkt = textureBucketOf(back, textureSlots);
					if (bkt >= 0) {
						texVertexCounts[bkt] += tris;
					} else {
						flatVertexCount += tris;
					}
				}
			}
		}

		float[] flatBuf = null;
		if (flatVertexCount > 0) {
			int needed = flatVertexCount * FLOATS_PER_VERTEX;
			if (flatBufPool == null || flatBufPool.length < needed) {
				flatBufPool = new float[needed];
			}
			flatBuf = flatBufPool;
		}
		float[][] texBufs = texBufPool;
		for (int t = 0; t < textureSlots; ++t) {
			if (texVertexCounts[t] > 0) {
				int needed = texVertexCounts[t] * FLOATS_PER_VERTEX;
				if (texBufs[t] == null || texBufs[t].length < needed) {
					texBufs[t] = new float[needed];
				}
			}
		}

		int flatOffset = 0;
		int[] texOffsets = texOffsetsPool;
		java.util.Arrays.fill(texOffsets, 0);

		// Pass 2: fill.
		for (int m = 0; m < modelCount; ++m) {
			RSModel model = (RSModel) models.get(m);
			if (!model.m_dc || modelOffScreen[m]) {
				continue;
			}
			for (int f = 0; f < model.faceHead; ++f) {
				int n = model.faceIndexCount[f];
				if (n < 3) {
					continue;
				}
				int front = model.faceTextureFront[f];
				int back = model.faceTextureBack[f];
				if (front != Scene.TRANSPARENT) {
					flatOffset = emitFaceSide(model, f, n, front, false, textureSlots,
							flatBuf, flatOffset, texBufs, texOffsets);
				}
				if (back != Scene.TRANSPARENT) {
					flatOffset = emitFaceSide(model, f, n, back, true, textureSlots,
							flatBuf, flatOffset, texBufs, texOffsets);
				}
			}
		}

		long profT2 = PROFILE ? System.currentTimeMillis() : 0;

		if (flatBuf != null) {
			NativeGL.drawTriangles(ctx, 0, flatBuf, flatVertexCount);
		}
		for (int t = 0; t < textureSlots; ++t) {
			// texVertexCounts[t] > 0, not texBufs[t] != null - texBufs[t]
			// is a pooled buffer now (see texBufPool's doc comment) and
			// stays non-null from whichever earlier frame first needed it,
			// even on a frame where this texture isn't used at all.
			if (texVertexCounts[t] > 0 && glTextureIds[t] != 0) {
				NativeGL.drawTriangles(ctx, glTextureIds[t], texBufs[t], texVertexCounts[t]);
			}
		}
		long profT3 = PROFILE ? System.currentTimeMillis() : 0;

		// Sprite billboards (players/NPCs/projectiles/items) are a separate
		// RSModel from the world/scenery models above, so they need their
		// own rotate1024() call - same camera, same method, just a
		// different model instance. Drawn via graphics.drawEntity(), which
		// blits directly into pixelData (the 2D layer presentUIOverlay()
		// composites afterward), not via GL triangles. Deliberately placed
		// *after* the real geometry's NativeGL.drawTriangles() calls above,
		// not before (an earlier version of this method ran it before any
		// geometry was drawn this frame, back when sprites weren't depth-
		// tested against walls at all): drawSpriteBillboards() now reads
		// back the GL depth buffer to occlude sprite pixels behind real
		// geometry (see its doc comment), which only has this frame's real
		// wall/scenery depths in it once the draws above have actually run.
		spriteBillboardModel.rotate1024(rot1024_off_y, vpSrc, rot1024_off_x, (byte) -122, rot1024_off_z,
				cameraProjY, cameraProjZ, cameraProjX, Z_TOP);
		drawSpriteBillboards();
		long profT3b = PROFILE ? System.currentTimeMillis() : 0;

		performPicking();
		long profT4 = PROFILE ? System.currentTimeMillis() : 0;

		if (PROFILE) {
			profileRotateMs += profT1 - profT0;
			profileFillMs += profT2 - profT1;
			profileDrawMs += profT3 - profT2;
			profileSpriteMs += profT3b - profT3;
			profilePickTotalMs += profT4 - profT3b;
			profileTriVerts = flatVertexCount;
			for (int t = 0; t < textureSlots; ++t) {
				profileTriVerts += texVertexCounts[t];
			}
		}

		// No endFrame()/swap here - the frame isn't done yet. mudclient
		// draws the 2D UI (chat, inventory, minimap...) into the same
		// GraphicsController.pixelData buffer *after* calling endScene(),
		// so presentUIOverlay() (called from ORSCApplet.draw(), the point
		// where that UI drawing is actually finished for the frame) is
		// what composites it over this 3D scene and swaps.
		beginFrameDone = true;
	}

	/**
	 * Click-to-move/interact picking: identifies which face of which model
	 * is under the mouse. This is a GL color-ID pass, not a port of
	 * Scene's own mechanism (its scanline-edge-interpolation hit test is a
	 * large, intricate part of Scene.java - reimplementing it exactly
	 * would mean duplicating most of that scanline machinery a second
	 * time for no rendering benefit, and this project's whole direction is
	 * moving work like this onto the GPU rather than re-deriving CPU-era
	 * algorithms). Instead: every visible face is redrawn once more with a
	 * flat, unlit color encoding its index into pickFaceRefs, restricted
	 * by a tiny GL_SCISSOR_TEST box around the mouse so the GPU rejects
	 * nearly everything before rasterizing - then a single pixel is read
	 * back and decoded. Depth-tested against the real pass's already-
	 * written depth buffer (not cleared first), so occlusion falls out
	 * correctly for free rather than needing to be reimplemented.
	 *
	 * ID colors use only 5 bits per channel (not 8), because this
	 * context's pixel format is 16-bit color (see createContext, a
	 * deliberate Voodoo2-era choice from Phase 0) - an 8-bit encoding
	 * would get quantized on write and decode incorrectly on readback.
	 * 5 bits/channel gives 32767 distinct non-zero ids (0 reserved for
	 * "no hit"), far more than a scene this size will ever have visible
	 * faces.
	 *
	 * After reading back, the scissored region's original pixels (saved by
	 * setPickScissor() before it cleared them for the ID pass) are blitted
	 * back via NativeGL.restorePickPixels() - a tiny glDrawPixels, not a
	 * second redraw of the scene's geometry. An earlier version of this
	 * method restored by redrawing all real geometry a second time, which
	 * cost as much GPU vertex work as the render pass itself, every frame;
	 * this doesn't, regardless of how complex the scene is.
	 *
	 * Result is always 0 or 1 hits (see b(int)'s doc comment). Sprite
	 * billboards (players/NPCs/items) are checked first, via
	 * performSpritePicking() - always run, every frame, regardless of the
	 * throttling below: it's a plain CPU rect+depth test reusing data
	 * drawSpriteBillboards() already computed this frame, not a second GL
	 * ID-color pass, since sprites already have a reliable screen rect and
	 * eye-space depth without needing one (see that method's doc comment
	 * for why a GL pass isn't worth it here). A found sprite hit takes
	 * priority over the world-geometry pass below - it's already been
	 * depth-checked against the same real geometry that pass tests, so a
	 * sprite that wins there is genuinely the topmost thing under the
	 * cursor, exactly matching the intuition "click the thing you can see".
	 *
	 * The world-geometry pass below (not sprite picking, see above) is
	 * throttled: it only actually rebuilds/resubmits/reads back when the
	 * mouse has moved since the last time it ran, a sprite-hit/no-hit
	 * transition needs a fresh answer (see lastFrameHadSpriteHit's use
	 * below), or PICK_REFRESH_INTERVAL_FRAMES frames have passed since the
	 * last real pick - otherwise it just leaves pickHitModels/
	 * pickHitFaceIndices/pickHitCount exactly as the last real pick left
	 * them. Added after confirming via profiling that this pass was
	 * resubmitting the *entire visible scene's* vertex count a second time,
	 * every single frame, purely to answer "what's under this exact pixel"
	 * - regardless of whether the mouse had moved at all since the last
	 * frame. On real, GPU-limited hardware (confirmed on a Windows XP
	 * laptop struggling to hit double-digit FPS) that's doubling the GPU's
	 * per-frame vertex-transform cost for no benefit on the (common) frames
	 * where nothing about "what's under the cursor" could plausibly have
	 * changed. The periodic refresh bounds the one real correctness gap
	 * this introduces: something else (an NPC walking into frame) moving
	 * under an otherwise-stationary cursor won't be reflected in hover/
	 * click targeting until the next refresh tick, at most
	 * PICK_REFRESH_INTERVAL_FRAMES frames of staleness - imperceptible for
	 * a hover-text/click-precision use case, not a twitch-reflex game.
	 */
	private void performPicking() {
		long profP0 = PROFILE ? System.currentTimeMillis() : 0;

		boolean spriteHit = performSpritePicking();
		if (spriteHit) {
			// performSpritePicking() already fully populated pickHitModels/
			// pickHitFaceIndices/pickHitCount itself - the expensive
			// world-geometry pass below isn't needed at all this frame.
			lastFrameHadSpriteHit = true;
			if (PROFILE) {
				profilePickBuildMs += System.currentTimeMillis() - profP0;
			}
			return;
		}

		boolean mouseMoved = mouseX != lastPickedMouseX || mouseY != lastPickedMouseY;
		boolean spriteHitChanged = lastFrameHadSpriteHit;
		++pickRefreshCounter;
		if (!mouseMoved && !spriteHitChanged && pickRefreshCounter < PICK_REFRESH_INTERVAL_FRAMES) {
			// Nothing that could plausibly change "what's under the cursor"
			// has happened - reuse whatever the last real pick left in
			// pickHitModels/pickHitFaceIndices/pickHitCount rather than
			// resubmitting the whole scene's geometry again for the same
			// answer. See this method's doc comment for the full rationale.
			if (PROFILE) {
				profilePickBuildMs += System.currentTimeMillis() - profP0;
			}
			return;
		}
		lastPickedMouseX = mouseX;
		lastPickedMouseY = mouseY;
		lastFrameHadSpriteHit = false;
		pickRefreshCounter = 0;

		pickFaceRefs.clear();
		pickHitCount = 0;

		// modelOffScreen[] was already computed this frame in endScene() (the
		// caller, right after rotate1024()) - models.size() can't have
		// changed since, so the indices still line up. Same off-screen
		// skip as endScene()'s own face-count/face-fill passes - see
		// isModelOffScreen()'s doc comment.
		int modelCount = models.size();
		int pickVertexCount = 0;
		for (int m = 0; m < modelCount; ++m) {
			RSModel model = (RSModel) models.get(m);
			if (!model.m_dc || modelOffScreen[m]) {
				continue;
			}
			for (int f = 0; f < model.faceHead; ++f) {
				int n = model.faceIndexCount[f];
				if (n < 3) {
					continue;
				}
				int tris = (n - 2) * 3;
				if (model.faceTextureFront[f] != Scene.TRANSPARENT) {
					pickVertexCount += tris;
				}
				if (model.faceTextureBack[f] != Scene.TRANSPARENT) {
					pickVertexCount += tris;
				}
			}
		}

		if (pickVertexCount == 0) {
			if (PROFILE) {
				profilePickBuildMs += System.currentTimeMillis() - profP0;
			}
			return;
		}

		int pickNeeded = pickVertexCount * FLOATS_PER_VERTEX;
		if (pickBufPool == null || pickBufPool.length < pickNeeded) {
			pickBufPool = new float[pickNeeded];
		}
		float[] pickBuf = pickBufPool;
		int offset = 0;
		for (int m = 0; m < modelCount; ++m) {
			RSModel model = (RSModel) models.get(m);
			if (!model.m_dc || modelOffScreen[m]) {
				continue;
			}
			for (int f = 0; f < model.faceHead; ++f) {
				int n = model.faceIndexCount[f];
				if (n < 3) {
					continue;
				}
				if (model.faceTextureFront[f] != Scene.TRANSPARENT) {
					offset = emitPickFace(model, f, n, false, pickBuf, offset);
				}
				if (model.faceTextureBack[f] != Scene.TRANSPARENT) {
					offset = emitPickFace(model, f, n, true, pickBuf, offset);
				}
			}
		}

		long profP1 = PROFILE ? System.currentTimeMillis() : 0;

		NativeGL.setPickScissor(ctx, mouseX, mouseY, PICK_SCISSOR_SIZE, graphics.height2);
		NativeGL.drawTriangles(ctx, 0, pickBuf, pickVertexCount);
		long profP2 = PROFILE ? System.currentTimeMillis() : 0;

		int color = NativeGL.readPixelColor(ctx, mouseX, mouseY, graphics.height2);
		int r5 = Math.round(((color >> 16) & 0xFF) * 31f / 255f);
		int g5 = Math.round(((color >> 8) & 0xFF) * 31f / 255f);
		int b5 = Math.round((color & 0xFF) * 31f / 255f);
		int id = (r5 | (g5 << 5) | (b5 << 10)) - 1;
		// Defensive validation, not just the id-range check: mudclient
		// indexes ref.model.facePickIndex[ref.faceIndex] unconditionally
		// (see mudclient.drawUiTab0()), so any bad decode here becomes a
		// crash there, several call frames away from this method - cheap
		// to rule out here, expensive to debug there. The color-key clear
		// in setPickScissor's native side should already prevent
		// mismatched decodes, but this is the layer that actually decides
		// what mudclient sees, so it gets its own check regardless. (No
		// need to check spriteHit here - this whole method already
		// returned early above when it was true.)
		if (id >= 0 && id < pickFaceRefs.size()) {
			PickFaceRef ref = (PickFaceRef) pickFaceRefs.get(id);
			if (ref.model.facePickIndex != null && ref.faceIndex < ref.model.facePickIndex.length) {
				pickHitModels[0] = ref.model;
				pickHitFaceIndices[0] = ref.faceIndex;
				pickHitCount = 1;
			}
		}

		// Restore the real colors within the scissor rect before the frame
		// is presented - a tiny pixel blit of what setPickScissor() saved,
		// not a redraw of any scene geometry (that redraw-based restore
		// cost as much GPU vertex work as the real render pass itself,
		// every single frame - this doesn't, regardless of scene size).
		long profP3 = PROFILE ? System.currentTimeMillis() : 0;
		NativeGL.restorePickPixels(ctx, mouseX, mouseY, PICK_SCISSOR_SIZE, graphics.width2, graphics.height2);
		NativeGL.clearPickScissor(ctx);

		if (PROFILE) {
			profilePickBuildMs += profP1 - profP0;
			profilePickGLMs += profP2 - profP1;
			profilePickReadbackMs += profP3 - profP2;
			profilePickRestoreMs += System.currentTimeMillis() - profP3;
			profilePickVerts = pickVertexCount;
		}
	}

	/**
	 * Sprite hit-test for performPicking() - see its doc comment for why
	 * this is a plain CPU check rather than a second GL ID-color pass:
	 * drawSpriteBillboards() already computed an accurate screen rect and
	 * eye-space depth (top/base, interpolated per row - the same fix wall
	 * occlusion needed, see occludeAgainstDepthBuffer()'s doc comment) for
	 * every visible sprite this same frame, and stored them on each
	 * SpriteDraw - reusing that is simpler and just as accurate as
	 * synthesizing real billboard quad geometry (camera-facing, needing the
	 * camera's own right/up vectors) just to redraw it a second time for an
	 * ID color.
	 *
	 * Picks the *nearest* sprite whose rect contains the mouse (not just
	 * the first one registered this frame), then confirms it isn't hidden
	 * behind real geometry at that exact pixel with one depth-buffer sample
	 * (NativeGL.readDepthRect(), a 1x1 read - the same un-projection math
	 * occludeAgainstDepthBuffer() already uses, just for a single point
	 * instead of a whole rect). Returns false (no sprite hit) if either no
	 * sprite's rect contains the mouse, or the nearest one that does is
	 * occluded there - in which case performPicking() falls back to the
	 * world-geometry pass exactly as if sprites didn't exist.
	 */
	private boolean performSpritePicking() {
		int count = spriteDraws.size();
		SpriteDraw nearest = null;
		int nearestDepth = Integer.MAX_VALUE;
		for (int i = 0; i < count; ++i) {
			SpriteDraw d = (SpriteDraw) spriteDraws.get(i);
			if (!d.visible) {
				continue;
			}
			if (mouseX < d.screenX || mouseX >= d.screenX + d.width
					|| mouseY < d.topY || mouseY >= d.topY + d.height) {
				continue;
			}
			if (nearest == null || d.baseDepth < nearestDepth) {
				nearest = d;
				nearestDepth = d.baseDepth;
			}
		}
		if (nearest == null || ctx == 0) {
			return false;
		}
		if (isSpriteOccludedAt(mouseX, mouseY, nearest)) {
			return false;
		}
		if (isTransparentAt(mouseX, mouseY)) {
			// The mouse is within the nearest candidate's rectangular
			// bounding box, but that box also covers plenty of pixels no
			// sprite actually drew anything to - a character's silhouette
			// is irregular, not a rectangle, and the rest of the box is
			// background/gap (transparent, see presentUIOverlay()'s doc
			// comment). Bounding-box containment alone isn't "this is the
			// sprite" - confirmed by the user: clicking on empty ground
			// right next to a sprite (but still inside its box) silently
			// ate what should have been an ordinary walk-here click.
			return false;
		}
		pickHitModels[0] = spriteBillboardModel;
		pickHitFaceIndices[0] = nearest.faceIndex;
		pickHitCount = 1;
		return true;
	}

	/**
	 * Mirrors presentUIOverlay()'s own transparency decision for a single
	 * pixel - see its doc comment for the spriteOwned/mismatch logic this
	 * repeats. Used by performSpritePicking() to tell a sprite's real,
	 * opaque pixels apart from the transparent background/gap area within
	 * its own (rectangular, but the sprite's actual silhouette isn't) rect.
	 */
	private boolean isTransparentAt(int x, int y) {
		int[] pixelData = graphics.pixelData;
		if (pixelData == null) {
			return true;
		}
		int idx = y * graphics.width2 + x;
		if (idx < 0 || idx >= pixelData.length) {
			return true;
		}
		int rgb = pixelData[idx] & 0xFFFFFF;
		boolean haveExpected = spriteExpectedColor != null && idx < spriteExpectedColor.length;
		boolean spriteOwned = haveExpected && spriteExpectedColor[idx] == rgb;
		return spriteOwned ? (rgb == 0xF800FF) : (rgb == 0);
	}

	/** Single-pixel version of occludeAgainstDepthBuffer()'s depth comparison - see performSpritePicking(). */
	private boolean isSpriteOccludedAt(int x, int y, SpriteDraw d) {
		float[] depth = new float[1];
		NativeGL.readDepthRect(ctx, x, y, 1, 1, graphics.height2, depth);
		float ndcZ = 2f * depth[0] - 1f;
		float near = Z_TOP;
		float far = Z_FAR;
		float eyeDistance = (2f * near * far) / (far + near - ndcZ * (far - near));

		float rowSpan = (d.height > 1) ? (float) (d.height - 1) : 1f;
		float rowFrac = (y - d.topY) / rowSpan;
		float spriteDepth = d.topDepth + rowFrac * (d.baseDepth - d.topDepth);

		return eyeDistance < spriteDepth;
	}

	private int emitPickFace(RSModel model, int f, int n, boolean reversed, float[] out, int offset) {
		int id = pickFaceRefs.size();
		PickFaceRef ref = new PickFaceRef();
		ref.model = model;
		ref.faceIndex = f;
		pickFaceRefs.add(ref);

		int packed = id + 1;
		float idR = (packed & 0x1F) / 31f;
		float idG = ((packed >> 5) & 0x1F) / 31f;
		float idB = ((packed >> 10) & 0x1F) / 31f;

		int[] indices = model.faceIndices[f];
		for (int i = 1; i < n - 1; ++i) {
			int a0 = 0;
			int a1 = i;
			int a2 = i + 1;
			if (reversed) {
				int t = a1;
				a1 = a2;
				a2 = t;
			}
			offset = putVertex(model, indices[a0], idR, idG, idB, 0f, 0f, out, offset);
			offset = putVertex(model, indices[a1], idR, idG, idB, 0f, 0f, out, offset);
			offset = putVertex(model, indices[a2], idR, idG, idB, 0f, 0f, out, offset);
		}
		return offset;
	}

	/**
	 * Projects every sprite registered this frame via drawSprite() and hands
	 * it to graphics.drawEntity() - the exact call Scene itself makes (see
	 * Scene.java's m_T-processing branch in endScene(), read line-by-line
	 * to get this right rather than guessed at) - which dispatches to
	 * mudclient's existing drawPlayer()/drawNPC()/drawItemAt() and blits the
	 * actual sprite pixels into pixelData. None of that downstream drawing
	 * code changes; this method only supplies the same projected
	 * (screenX, screenY, screenWidth, screenHeight, overlayMovement,
	 * topPixelSkew) Scene would have computed from its own camera.
	 *
	 * Not reproduced here (out of scope for "make characters visible" -
	 * see PLAN.md): the combat X-offset (setCombatXOffset()) only affects
	 * Scene's picking hit-test region, not this draw call, per Scene's own
	 * source - and the local-player picking exclusion
	 * (setFaceSpriteLocalPlayer()) has no rendering effect at all, only a
	 * picking one. Neither matters until picking is implemented.
	 */
	/**
	 * See presentUIOverlay()'s doc comment: pixelData has no real alpha
	 * channel, so it color-keys pure black as "transparent, show the 3D
	 * scene through here" - but this engine's clothing palette includes
	 * actual black, which drawEntity() (via drawPlayer()/drawNPC()) can
	 * legitimately paint.
	 *
	 * Three earlier versions of this method didn't hold up:
	 * - Pre-filling each sprite's bounding rect with a sentinel color
	 *   immediately before drawing it (one sprite at a time) was
	 *   destructive: a later sprite's fill could erase an earlier sprite's
	 *   (or a nametag's) already-drawn pixels if their rects overlapped -
	 *   confirmed by the user seeing exactly that.
	 * - Diffing each rect's pixels before/after drawEntity() (marking only
	 *   pixels that actually changed as opaque, regardless of color)
	 *   avoided that destructiveness, but turned out not to fix the
	 *   original bug at all: the 3D viewport area of pixelData is never
	 *   touched by anything except sprites (the real 3D terrain is GL-
	 *   rendered separately, never composited into pixelData), so for a
	 *   character standing alone, "before" and "after" are both black for
	 *   any black clothing pixel - the diff sees no change and wrongly
	 *   calls it a gap. Confirmed still see-through by the user.
	 * - Splitting fill-then-draw into two full passes (every sprite's rect
	 *   gets a magenta sentinel color, 0xF800FF, *first*; every sprite's
	 *   real content gets drawn *second*) fixed the destructive-erasure
	 *   problem, but presentUIOverlay() still decided transparency from
	 *   that sentinel *by color value alone, checked against the whole
	 *   screen* - and 0xF800FF isn't actually unique to this fill: it's the
	 *   same magenta color-key Scene's own texture decode
	 *   (GLSceneRenderer.decodeTexel()) uses for "no pixel here" elsewhere
	 *   in this engine, and at least one UI sprite (a settings-panel person
	 *   icon, confirmed via screenshot) legitimately contains that raw
	 *   value in its own source pixels. GraphicsController's 2D sprite blit
	 *   routines (a()/plot_scale_black_mask) only ever skip pure black
	 *   (0x000000) when copying a sprite's pixels - they have no
	 *   0xF800FF-skipping logic at all - so that icon's magenta pixels land
	 *   in pixelData as real, intentional content. presentUIOverlay()'s
	 *   blanket, unscoped `rgb == 0xF800FF` check couldn't tell that apart
	 *   from our own sentinel and wrongly hid it (reported as a separate
	 *   "see-through UI icon" bug, but really the same root flaw from the
	 *   opposite direction).
	 *
	 * This version keeps the two-pass fill-then-draw order (still the fix
	 * for destructive erasure) but stops leaking the sentinel check outside
	 * the exact pixels this method itself filled: spriteExpectedColor
	 * records, per pixel, exactly what color sprite compositing left there
	 * this frame - completely independent of any *other* content that might
	 * coincidentally share that value, so it can never collide with
	 * unrelated content anywhere else on screen. presentUIOverlay() only
	 * treats 0xF800FF as a transparency signal where pixelData still
	 * matches that recorded color; everywhere else (including that settings
	 * icon) falls back to the original plain black-color-key rule,
	 * unaffected by sprite billboards at all.
	 *
	 * A third pass (occludeAgainstDepthBuffer()) runs after the real content
	 * is drawn: sprites are 2D pixels composited over the whole GL-rendered
	 * frame with no depth comparison of their own, so without this a
	 * character always drew in front of a wall behind them, regardless of
	 * which was actually closer to the camera - a separate, previously
	 * documented gap from the black-clothing bug above. It re-marks any
	 * pixel where real geometry's depth (read back from the GL depth buffer
	 * endScene()'s NativeGL.drawTriangles() calls already wrote this frame -
	 * see this method's new call site in endScene(), moved to run after
	 * those calls rather than before) is nearer than the sprite's own depth
	 * back to the same sentinel this method's fill pass already used, so it
	 * reads as transparent (show the real, nearer geometry through it) in
	 * presentUIOverlay() exactly like an untouched fill pixel does - no new
	 * transparency mechanism needed, just one more way to arrive at the
	 * existing one. This same pass also records, for every pixel in every
	 * sprite's rect, the final color sprite compositing left it as (see
	 * spriteExpectedColor's own doc comment for why a recorded color, not
	 * just a boolean, matters here) - confirmed necessary after the welcome-
	 * screen dialog box's own black background, drawn after this method
	 * returns, showed up as an opaque black square instead of properly
	 * transparent wherever a sprite's rect happened to have covered that
	 * same spot earlier in the frame.
	 */
	private void drawSpriteBillboards() {
		ensureSpriteExpectedColor();

		int count = spriteDraws.size();
		// Pooled/reused across frames rather than freshly `new`'d every
		// frame - see flatBufPool's doc comment for the same reasoning,
		// applied here to the (smaller, but still per-frame) sprite
		// scratch arrays. visible[] is explicitly cleared below since,
		// unlike the others, a stale `true` left over from a larger
		// previous frame would be read (every loop below gates on it).
		int[] screenXs = spriteScreenXsPool = growIntPool(spriteScreenXsPool, count);
		int[] topYs = spriteTopYsPool = growIntPool(spriteTopYsPool, count);
		int[] widths = spriteWidthsPool = growIntPool(spriteWidthsPool, count);
		int[] heights = spriteHeightsPool = growIntPool(spriteHeightsPool, count);
		int[] overlayMovements = spriteOverlayMovementsPool = growIntPool(spriteOverlayMovementsPool, count);
		int[] topPixelSkews = spriteTopPixelSkewsPool = growIntPool(spriteTopPixelSkewsPool, count);
		int[] baseDepths = spriteBaseDepthsPool = growIntPool(spriteBaseDepthsPool, count);
		int[] topDepths = spriteTopDepthsPool = growIntPool(spriteTopDepthsPool, count);
		boolean[] visible = spriteVisiblePool = growBooleanPool(spriteVisiblePool, count);
		java.util.Arrays.fill(visible, 0, count, false);

		for (int i = 0; i < count; ++i) {
			SpriteDraw d = (SpriteDraw) spriteDraws.get(i);
			int baseDepth = spriteBillboardModel.vertZRot[d.baseVertexIndex];
			if (baseDepth <= Z_TOP || baseDepth >= fogEntityDistance) {
				continue;
			}
			int baseScreenX = spriteBillboardModel.vertexParam6[d.baseVertexIndex];
			int baseScreenY = spriteBillboardModel.vertexParam2[d.baseVertexIndex];
			int topScreenX = spriteBillboardModel.vertexParam6[d.topVertexIndex];

			int projectedWidth = (d.widthRaw << vpSrc) / baseDepth;
			int projectedHeight = (d.heightRaw << vpSrc) / baseDepth;

			screenXs[i] = baseScreenX - projectedWidth / 2 + screenMidX;
			topYs[i] = screenMidY - (projectedHeight - baseScreenY);
			widths[i] = projectedWidth;
			heights[i] = projectedHeight;
			overlayMovements[i] = (256 << vpSrc) / baseDepth;
			topPixelSkews[i] = topScreenX - baseScreenX;
			baseDepths[i] = baseDepth;
			topDepths[i] = spriteBillboardModel.vertZRot[d.topVertexIndex];
			visible[i] = true;

			// Kept on the SpriteDraw itself too (duplicating the locals
			// above) so performPicking() - which runs later, after this
			// whole method returns - can look this frame's screen rect and
			// depth up per sprite without its own parallel arrays. See
			// performPicking()'s doc comment for the sprite hit-test this
			// feeds.
			d.screenX = screenXs[i];
			d.topY = topYs[i];
			d.width = widths[i];
			d.height = heights[i];
			d.baseDepth = baseDepth;
			d.topDepth = topDepths[i];
			d.visible = true;

			fillSentinelRect(screenXs[i], topYs[i], widths[i], heights[i]);
		}

		// Draw and occlude in far-to-nearest order (painter's algorithm)
		// rather than plain registration order, so overlapping sprites are
		// depth-sorted against *each other*, not just against real 3D
		// geometry (occludeAgainstDepthBuffer()'s job) - without this, a
		// character standing behind another still drew on top of them,
		// since sprites are registered and iterated in whatever order
		// mudclient happened to process them this frame (matching Scene's
		// own m_T order), not sorted by distance from the camera - confirmed
		// by the user seeing exactly that. A simple insertion sort is plenty
		// here: count is the number of sprites visible on screen this
		// frame, not a scene-wide total. Pooled like the arrays above -
		// every entry in [0, count) is unconditionally overwritten next,
		// so no explicit clear is needed first.
		int[] spriteOrder = spriteOrderPool = growIntPool(spriteOrderPool, count);
		for (int i = 0; i < count; ++i) {
			spriteOrder[i] = i;
		}
		for (int i = 1; i < count; ++i) {
			int cur = spriteOrder[i];
			int curDepth = baseDepths[cur];
			int j = i - 1;
			while (j >= 0 && baseDepths[spriteOrder[j]] < curDepth) {
				spriteOrder[j + 1] = spriteOrder[j];
				--j;
			}
			spriteOrder[j + 1] = cur;
		}

		ensureSpriteOwnerIndex();
		for (int k = 0; k < count; ++k) {
			int i = spriteOrder[k];
			if (!visible[i]) {
				continue;
			}
			SpriteDraw d = (SpriteDraw) spriteDraws.get(i);
			graphics.drawEntity(d.entityId, screenXs[i], topYs[i], widths[i], heights[i],
					overlayMovements[i], topPixelSkews[i]);
			// Claim ownership of this sprite's own rect *plus* the nametag/
			// clan tag strip above it (see occludeAgainstDepthBuffer()'s and
			// markSpriteOwner()'s doc comments) - two earlier versions of
			// this claim didn't hold up: claiming nothing at all let one
			// sprite's occlusion pass erase a different sprite's already-
			// drawn nametag wherever the rects merely overlapped on screen;
			// claiming the *entire* rect unconditionally (regardless of
			// whether real content was actually there) then caused the
			// opposite regression - a nearer sprite's blanket claim over its
			// own mostly-empty bounding box could "steal" pixels from a
			// farther, unrelated sprite's own body-vs-wall occlusion, since
			// two characters simply standing near each other on screen is
			// common and has nothing to do with nametags. markSpriteOwner()
			// only actually claims a pixel where this sprite drew real
			// (non-sentinel) content, so gap/background overlap between two
			// rects - the common case - claims nothing and doesn't interfere.
			markSpriteOwner(screenXs[i], topYs[i] - NAMETAG_PROTECT_ABOVE, widths[i],
					heights[i] + NAMETAG_PROTECT_ABOVE, i);
		}

		boolean anyVisible = false;
		for (int i = 0; i < count; ++i) {
			if (visible[i]) {
				anyVisible = true;
				break;
			}
		}

		long occT0 = PROFILE ? System.currentTimeMillis() : 0;
		// Skip the depth readback entirely when there's nothing to occlude -
		// e.g. the login-screen carousel (mudclient.renderLoginScreenViewports()),
		// which only has world/scenery RSModels, no sprite billboards at all
		// (spriteDraws is empty there). Before this guard, a whole-canvas
		// glReadPixels(GL_DEPTH_COMPONENT) ran unconditionally on literally
		// the very first endScene() call of the whole session, for zero
		// benefit - confirmed on real hardware (Windows 98 + Voodoo 5) to
		// coincide with the client never even reaching the New User/Existing
		// User start screen, a worse regression than the slow-but-working
		// per-sprite reads this batching replaced. Skipping the read
		// entirely when unneeded is strictly safer than trying to keep
		// narrowing its size.
		if (ctx != 0 && anyVisible) {
			// One depth read per frame, not one per sprite - see
			// sceneDepthBuffer's own doc comment for why. Bounded to the
			// union of this frame's visible sprite rects rather than the
			// whole canvas - sprites typically cover a small fraction of
			// the screen, and readback cost on this era of hardware scales
			// with how much data crosses the stall.
			int canvasWidth = graphics.width2;
			int canvasHeight = graphics.height2;
			int boundX0 = canvasWidth;
			int boundY0 = canvasHeight;
			int boundX1 = 0;
			int boundY1 = 0;
			for (int k = 0; k < count; ++k) {
				int i = spriteOrder[k];
				if (!visible[i]) {
					continue;
				}
				int rx0 = Math.max(0, screenXs[i]);
				int ry0 = Math.max(0, topYs[i]);
				int rx1 = Math.min(canvasWidth, screenXs[i] + widths[i]);
				int ry1 = Math.min(canvasHeight, topYs[i] + heights[i]);
				if (rx1 <= rx0 || ry1 <= ry0) {
					continue;
				}
				if (rx0 < boundX0) boundX0 = rx0;
				if (ry0 < boundY0) boundY0 = ry0;
				if (rx1 > boundX1) boundX1 = rx1;
				if (ry1 > boundY1) boundY1 = ry1;
			}

			if (boundX1 > boundX0 && boundY1 > boundY0) {
				int boundWidth = boundX1 - boundX0;
				int boundHeight = boundY1 - boundY0;
				int boundPixels = boundWidth * boundHeight;
				if (sceneDepthBuffer == null || sceneDepthBuffer.length < boundPixels) {
					sceneDepthBuffer = new float[boundPixels];
				}
				sceneDepthBoundX = boundX0;
				sceneDepthBoundY = boundY0;
				sceneDepthBoundWidth = boundWidth;
				sceneDepthBoundHeight = boundHeight;

				long readT0 = PROFILE ? System.currentTimeMillis() : 0;
				NativeGL.readDepthRect(ctx, boundX0, boundY0, boundWidth, boundHeight, canvasHeight, sceneDepthBuffer);
				long readT1 = PROFILE ? System.currentTimeMillis() : 0;
				for (int k = 0; k < count; ++k) {
					int i = spriteOrder[k];
					if (!visible[i]) {
						continue;
					}
					occludeAgainstDepthBuffer(screenXs[i], topYs[i], widths[i], heights[i], topDepths[i], baseDepths[i], i);
				}
				if (PROFILE) {
					profileOcclusionReadMs += readT1 - readT0;
					profileOcclusionCpuMs += System.currentTimeMillis() - readT1;
				}
			}
		}

		// Fourth pass, over every sprite's rect again, after every sprite's
		// spriteExpectedColor is finally settled: only now convert confirmed
		// gaps (still exactly the sentinel) to plain black - see
		// finalizeGapColor()'s doc comment for why this can't be folded into
		// occludeAgainstDepthBuffer()'s own per-sprite loop above (two
		// overlapping sprites' rects sharing a gap pixel would otherwise
		// convert-then-misrecord each other's work, in whichever order they
		// happen to run - confirmed by the user seeing exactly that:
		// overlapping sprites produced a stray opaque black box specifically
		// in the overlap).
		long finalizeT0 = PROFILE ? System.currentTimeMillis() : 0;
		for (int i = 0; i < count; ++i) {
			if (!visible[i]) {
				continue;
			}
			finalizeGapColor(screenXs[i], topYs[i], widths[i], heights[i]);
		}
		if (PROFILE) {
			profileOcclusionFinalizeMs += System.currentTimeMillis() - finalizeT0;
			profileSpriteOcclusionMs += System.currentTimeMillis() - occT0;
		}
	}

	/**
	 * See drawSpriteBillboards()'s doc comment for why this is a separate,
	 * later pass rather than folded into occludeAgainstDepthBuffer(): by the
	 * time this runs, every sprite that touches a given pixel has already
	 * finished recording its own spriteExpectedColor there, so whichever
	 * sprite actually "wins" that pixel (the one drawn last, matching normal
	 * painter's-order stacking) has already settled it to either a real
	 * color or the gap sentinel - stable, not still being fought over mid-
	 * pass by two sprites' rects that happen to overlap.
	 */
	private void finalizeGapColor(int x, int y, int width, int height) {
		int[] pixels = graphics.pixelData;
		if (pixels == null) {
			return;
		}
		int canvasWidth = graphics.width2;
		int canvasHeight = graphics.height2;
		int x0 = Math.max(0, x);
		int y0 = Math.max(0, y);
		int x1 = Math.min(canvasWidth, x + width);
		int y1 = Math.min(canvasHeight, y + height);
		for (int yy = y0; yy < y1; ++yy) {
			int rowBase = yy * canvasWidth;
			for (int xx = x0; xx < x1; ++xx) {
				int idx = rowBase + xx;
				if (spriteExpectedColor[idx] == 0xF800FF) {
					pixels[idx] = 0;
				}
			}
		}
	}

	/**
	 * See drawSpriteBillboards()'s doc comment. Compares this sprite's
	 * (clamped) rect against the real geometry's depth buffer - sampled from
	 * sceneDepthBuffer (see its own doc comment for why this reads a shared,
	 * once-per-frame snapshot rather than calling NativeGL.readDepthRect()
	 * itself here) - and, for any pixel where that depth is nearer to the
	 * camera than the sprite's own depth at that row, resets pixelData back
	 * to the fill sentinel there - unless spriteOwnerIndex shows a
	 * *different* sprite's
	 * nametag specifically claims that pixel (see its own doc comment for
	 * why this check is scoped to nametag strips only, not a general
	 * "ownership" of every sprite's whole rect - a broader version of this
	 * check regressed wall occlusion for unrelated, merely-nearby sprites).
	 * Without any check at all, this sprite's own occlusion decision (based
	 * purely on its own depth vs. the wall, having nothing to do with
	 * whatever else might be drawn at that screen position) could blindly
	 * erase a *different* sprite's nametag, which mudclient.drawPlayer()
	 * draws above its own rect, in a zone this sprite's rect can easily
	 * overlap without the two sprites' bodies overlapping at all. Confirmed
	 * exactly that: a goblin positioned past a doorway erased part of the
	 * player's own nametag, because the goblin's rect happened to reach the
	 * screen position the nametag rendered at, and the goblin's own
	 * occlusion pass didn't know that pixel wasn't its own to erase.
	 *
	 * Also records, for every pixel in the rect regardless of the occlusion
	 * outcome, the final color into spriteExpectedColor - folded into this
	 * same loop rather than a separate pass, since this method already
	 * visits every pixel in the rect once.
	 *
	 * The sprite's own depth is interpolated per row between topDepth and
	 * baseDepth (the top/base vertex's own vertZRot, i.e. the head/feet of
	 * the billboard "pole" - see the class's spriteBillboardModel field),
	 * not a single flat value: an early version used baseDepth for the
	 * whole rect, which looked right for most of a sprite but chewed a
	 * visible notch out of characters' heads near wall corners specifically
	 * - confirmed via screenshot. Root cause: this camera has real pitch, so
	 * a standing character's head is meaningfully nearer to (or farther
	 * from) the camera than their feet, not the same depth - exactly the
	 * gap a single flat comparison can't account for, and exactly where a
	 * nearby wall corner's own depth is likely to fall *between* the two,
	 * misjudging the head region specifically. Interpolating linearly
	 * across the rect (screen Y, not world space - an approximation, but
	 * consistent with this method's other screen-space shortcuts) fixes
	 * that without needing a per-pixel true 3D depth for a flat billboard.
	 *
	 * Depth values come back non-linear (window-space, in [0, 1]) - the
	 * standard un-projection for a symmetric/asymmetric perspective frustum
	 * (glFrustum, this class's only projection) converts one back to a
	 * linear eye-space distance using just near/far (Z_TOP/Z_FAR), the same
	 * two constants setPerspectiveFrustum() was already called with -
	 * independent of left/right/top/bottom. topDepth/baseDepth (from
	 * RSModel.vertZRot, the same field putVertex() negates to get this
	 * class's GL Z) are already in that same linear distance-from-camera
	 * unit, so they compare directly with no extra conversion needed on
	 * that side.
	 */
	private void occludeAgainstDepthBuffer(int x, int y, int width, int height, int topDepth, int baseDepth,
			int spriteIndex) {
		int[] pixels = graphics.pixelData;
		if (pixels == null || sceneDepthBuffer == null) {
			return;
		}
		int canvasWidth = graphics.width2;
		int canvasHeight = graphics.height2;
		int x0 = Math.max(0, x);
		int y0 = Math.max(0, y);
		int x1 = Math.min(canvasWidth, x + width);
		int y1 = Math.min(canvasHeight, y + height);
		if (x1 - x0 <= 0 || y1 - y0 <= 0) {
			return;
		}

		float near = Z_TOP;
		float far = Z_FAR;
		// height - 1 can be 0 for a 1px-tall (clamped-degenerate) rect;
		// guard against dividing by zero rather than special-casing it away.
		float rowSpan = (height > 1) ? (float) (height - 1) : 1f;
		for (int yy = y0; yy < y1; ++yy) {
			float rowFrac = (yy - y) / rowSpan;
			float spriteDepth = topDepth + rowFrac * (baseDepth - topDepth);

			// sceneDepthBuffer comes back GL-style, bottom row first (see its
			// doc comment and where it's fetched) - flip when mapping to
			// pixelData's top-down rows, same as this method always did
			// per-rect before the read was batched to once per frame.
			// Indexed relative to sceneDepthBoundX/Y/Width (the read's
			// bounding sub-rect), not absolute canvas coordinates - this
			// sprite's own (clamped) rect is always fully contained within
			// that bound, since it was one of the rects unioned to compute
			// it (see drawSpriteBillboards()).
			int localY = yy - sceneDepthBoundY;
			int depthRow = (sceneDepthBoundHeight - 1 - localY) * sceneDepthBoundWidth - sceneDepthBoundX;
			int rowBase = yy * canvasWidth;
			for (int xx = x0; xx < x1; ++xx) {
				float d = sceneDepthBuffer[depthRow + xx];
				float ndcZ = 2f * d - 1f;
				float eyeDistance = (2f * near * far) / (far + near - ndcZ * (far - near));
				int idx = rowBase + xx;
				// -1 (nobody's nametag zone) or this sprite's own claim both
				// allow the write through - only a *different* sprite's
				// nametag claim vetoes it. See markSpriteOwner()'s call site
				// for why this is deliberately not a plain equality check.
				boolean claimedByOther = spriteOwnerIndex[idx] >= 0 && spriteOwnerIndex[idx] != spriteIndex;
				if (eyeDistance < spriteDepth && !claimedByOther) {
					pixels[idx] = 0xF800FF;
				}
				spriteExpectedColor[idx] = pixels[idx] & 0xFFFFFF;
			}
		}
	}

	/**
	 * (Re)sizes spriteExpectedColor to match the current canvas and resets
	 * it (-1, "no sprite touched this pixel") for this frame. Always reset
	 * unconditionally (not just on resize) so a sprite that moved or
	 * disappeared since last frame can't leave a stale record at its old
	 * position.
	 */
	private void ensureSpriteExpectedColor() {
		int size = graphics.width2 * graphics.height2;
		if (spriteExpectedColor == null || spriteExpectedColor.length != size) {
			spriteExpectedColor = new int[size];
		}
		java.util.Arrays.fill(spriteExpectedColor, -1);
	}

	/** Same lifecycle as ensureSpriteExpectedColor() - see spriteOwnerIndex's own doc comment. */
	private void ensureSpriteOwnerIndex() {
		int size = graphics.width2 * graphics.height2;
		if (spriteOwnerIndex == null || spriteOwnerIndex.length != size) {
			spriteOwnerIndex = new int[size];
		}
		java.util.Arrays.fill(spriteOwnerIndex, -1);
	}

	/**
	 * See spriteOwnerIndex's doc comment - marks a rect as belonging to
	 * sprite `spriteIndex` for this frame, but only the pixels within it
	 * that are currently real (non-sentinel) content, not the whole
	 * rectangular bounds indiscriminately. This is what makes the claim
	 * safe to take over a sprite's *entire* rect (body plus nametag strip)
	 * instead of needing to guess which sub-region matters: a gap/
	 * background pixel that merely falls within this sprite's rectangular
	 * bounds - the common case whenever two sprites' rects overlap at all -
	 * claims nothing, so it can't interfere with whatever unrelated sprite
	 * actually owns that pixel (or with real 3D geometry occlusion there).
	 * Only pixels this sprite genuinely drew something onto are protected.
	 */
	private void markSpriteOwner(int x, int y, int width, int height, int spriteIndex) {
		int[] pixels = graphics.pixelData;
		if (pixels == null) {
			return;
		}
		int canvasWidth = graphics.width2;
		int canvasHeight = graphics.height2;
		int x0 = Math.max(0, x);
		int y0 = Math.max(0, y);
		int x1 = Math.min(canvasWidth, x + width);
		int y1 = Math.min(canvasHeight, y + height);
		for (int yy = y0; yy < y1; ++yy) {
			int rowBase = yy * canvasWidth;
			for (int xx = x0; xx < x1; ++xx) {
				int idx = rowBase + xx;
				if ((pixels[idx] & 0xFFFFFF) != 0xF800FF) {
					spriteOwnerIndex[idx] = spriteIndex;
				}
			}
		}
	}

	/**
	 * See drawSpriteBillboards()'s doc comment for why this exists. Doesn't
	 * touch spriteExpectedColor itself - occludeAgainstDepthBuffer() records
	 * the final per-pixel color once, after both this fill and the real
	 * drawEntity() content are in, rather than this method guessing at what
	 * will still be true by the time that happens.
	 */
	private void fillSentinelRect(int x, int y, int width, int height) {
		int[] pixels = graphics.pixelData;
		if (pixels == null) {
			return;
		}
		int canvasWidth = graphics.width2;
		int canvasHeight = graphics.height2;
		int x0 = Math.max(0, x);
		int y0 = Math.max(0, y);
		int x1 = Math.min(canvasWidth, x + width);
		int y1 = Math.min(canvasHeight, y + height);
		for (int yy = y0; yy < y1; ++yy) {
			int rowBase = yy * canvasWidth;
			for (int xx = x0; xx < x1; ++xx) {
				pixels[rowBase + xx] = 0xF800FF;
			}
		}
	}

	/**
	 * Composites the software-rendered 2D UI layer (GraphicsController's
	 * pixelData - chat, inventory, minimap, login/character-creation
	 * screens, all of it) over the 3D scene endScene() already drew this
	 * frame, then presents the finished frame. Called from
	 * ORSCApplet.draw() - not part of the SceneRenderer interface, since
	 * Scene has no equivalent need (it composites everything into
	 * pixelData directly and never needed a separate "present" step).
	 *
	 * pixelData has no real alpha channel (DirectColorModel with no alpha
	 * mask - see ORSCApplet.initGraphics()), so pure black (0x000000) is
	 * treated as a color-key transparent hole - a heuristic, not extracted
	 * ground truth, and genuinely wrong wherever real content is legitimately
	 * black (confirmed: character clothing includes black, which was
	 * showing as see-through). drawSpriteBillboards() works around this for
	 * sprites specifically by pre-filling each sprite's bounding rect with a
	 * magenta sentinel (0xF800FF, the same "no pixel here" color-key Scene's
	 * own texture decode uses - see decodeTexel()) *before* drawing any
	 * sprite's real content, then recording the final per-pixel color that
	 * process left behind into spriteExpectedColor (see that method's doc
	 * comment for the approaches that didn't hold up, including an earlier
	 * version of this method that checked the magenta value globally and
	 * collided with unrelated UI sprite content that legitimately contains
	 * it, and a boolean-only touched/not-touched version that came after -
	 * see spriteExpectedColor's own doc comment for why that wasn't enough
	 * either, confirmed by the welcome-screen dialog box case).
	 *
	 * So the rule here is scoped, not a blanket color check, and self-
	 * invalidating rather than a one-shot flag: a pixel only gets sprite
	 * billboard treatment (transparent iff still exactly the magenta
	 * sentinel) if pixelData *right now* still matches what
	 * spriteExpectedColor recorded - if anything drew over it since (a
	 * dialog, a UI panel, another sprite), the mismatch falls back to the
	 * plain original black-color-key rule instead, exactly as if no sprite
	 * had ever touched that pixel.
	 *
	 * The plain rule is full transparency (alpha 0), same as ever - a
	 * tempting-looking "fix" for the next issue below turned out to be
	 * wrong, worth recording so it isn't retried: two genuinely different
	 * things both produce rgb == 0 here with no way to tell them apart by
	 * color value alone - the 3D-viewport region of pixelData before any
	 * sprite has ever drawn on it (wants a true hole, so the already-
	 * rendered 3D scene shows through), and UI chrome that deliberately
	 * draws solid black as its own background, e.g. the "Logging Out"/
	 * "Welcome to RSC" dialogs (`drawBox(..., 0)` - wants to look dark/
	 * opaque, the way it always has in the original software renderer,
	 * where black is just an ordinary opaque color with no transparency
	 * concept at all). Making plain black mostly-opaque instead of fully
	 * transparent, guessing the untouched-viewport case was rare in
	 * practice, was tried and reverted immediately: that guess was backward
	 * - the 3D-viewport region of pixelData is plain black almost
	 * everywhere, all the time (nothing is drawn there except sprites,
	 * *not* because the 3D scene itself is dark - that's rendered straight
	 * to the GL framebuffer, never through pixelData at all), so darkening
	 * "plain black, not sprite-owned" darkened nearly the entire screen,
	 * confirmed by the user ("It made everything black"). Properly fixing
	 * the dialog-background case needs to track "was this pixel touched by
	 * 2D UI drawing this frame" for every `GraphicsController` draw call,
	 * not just sprites - a real, separate follow-up, not a one-line alpha
	 * tweak.
	 *
	 * Handles being called on a frame where endScene() never ran (e.g. a
	 * pure-2D screen) by lazily doing its own beginFrame() in that case,
	 * so those screens still show something in the GL window rather than
	 * whatever was left over from the last 3D frame.
	 *
	 * Skips the RGBA conversion + texture upload entirely when pixelData is
	 * byte-for-byte identical to last frame's (see previousPixelData's own
	 * doc comment) - added after profiling showed this rebuilding and
	 * re-uploading the *entire* canvas as a texture every single frame,
	 * unconditionally, even on the very common case of a frame where
	 * nothing about the 2D UI changed at all (standing still, no chat/
	 * inventory/HUD update). java.util.Arrays.equals() bails out at the
	 * first differing pixel, so even frames that *do* need the real work
	 * pay very little extra for the check itself.
	 *
	 * This compares pixelData only, not spriteExpectedColor (also rebuilt
	 * every frame, by drawSpriteBillboards()) - in principle two different
	 * spriteExpectedColor states could theoretically produce a different
	 * alpha decision for byte-identical pixelData content, but that would
	 * require a *different* sprite/camera configuration to coincidentally
	 * paint the exact same final colors into the exact same pixels, which
	 * isn't a realistic case to defend against - a genuinely static frame
	 * (nothing moved) rebuilds spriteExpectedColor to the same values
	 * anyway, since it's a deterministic function of this frame's sprite
	 * state.
	 */
	public void presentUIOverlay(int[] pixelData, int width, int height) {
		long profU0 = PROFILE ? System.currentTimeMillis() : 0;
		ensureContext();
		if (ctx == 0) {
			return;
		}
		if (!NativeGL.pumpMessages(ctx)) {
			return;
		}
		if (!beginFrameDone) {
			NativeGL.beginFrame(ctx, 0.05f, 0.05f, 0.08f);
		}
		beginFrameDone = false;

		if (pixelData != null && width > 0 && height > 0) {
			int potWidth = nextPowerOfTwo(width);
			int potHeight = nextPowerOfTwo(height);

			boolean textureReady = uiTextureId != 0 && uiTextureWidth == width && uiTextureHeight == height;
			boolean unchanged = textureReady && previousPixelData != null
					&& previousPixelData.length == pixelData.length
					&& java.util.Arrays.equals(previousPixelData, pixelData);

			if (!unchanged) {
				/* Padded up to the next power-of-two size in both dimensions -
				 * real pre-2003 OpenGL ICDs (this engine's actual target, unlike
				 * Wine's much more permissive translation) require exact
				 * power-of-two texture dimensions, and the game's own canvas size
				 * (e.g. 512x346, or 640x480 in letterboxed fullscreen) never is
				 * in both axes. Confirmed necessary by real-hardware testing on a
				 * Voodoo 5: without this, the GL window rendered solid blank
				 * white despite working under Wine throughout development. Only
				 * the top-left width x height region holds real content; the
				 * padding is left zeroed (never sampled - see uMax/vMax below and
				 * NativeGL.drawUIOverlay's doc comment). */
				byte[] rgba = new byte[potWidth * potHeight * 4];
				boolean haveExpected = spriteExpectedColor != null && spriteExpectedColor.length == pixelData.length;
				int pixelCount = Math.min(width * height, pixelData.length);
				int p = 0;
				outer:
				for (int y = 0; y < height; ++y) {
					int destRowBase = y * potWidth;
					for (int x = 0; x < width; ++x) {
						if (p >= pixelCount) {
							break outer;
						}
						int rgb = pixelData[p] & 0xFFFFFF;
						int o = (destRowBase + x) * 4;
						rgba[o] = (byte) ((rgb >> 16) & 0xFF);
						rgba[o + 1] = (byte) ((rgb >> 8) & 0xFF);
						rgba[o + 2] = (byte) (rgb & 0xFF);
						boolean spriteOwned = haveExpected && spriteExpectedColor[p] == rgb;
						boolean transparent = spriteOwned ? (rgb == 0xF800FF) : (rgb == 0);
						rgba[o + 3] = (byte) (transparent ? 0x00 : 0xFF);
						++p;
					}
				}
				long profU1 = PROFILE ? System.currentTimeMillis() : 0;
				if (uiTextureId == 0 || uiTextureWidth != width || uiTextureHeight != height) {
					if (uiTextureId != 0) {
						NativeGL.deleteTexture(ctx, uiTextureId);
					}
					uiTextureId = NativeGL.uploadTexture(ctx, potWidth, potHeight, rgba);
					uiTextureWidth = width;
					uiTextureHeight = height;
				} else {
					NativeGL.updateTexture(ctx, uiTextureId, potWidth, potHeight, rgba);
				}

				// Snapshot for next frame's comparison - a real copy (not a
				// reference assignment), since pixelData is mutated in place -
				// see previousPixelData's own doc comment.
				if (previousPixelData == null || previousPixelData.length != pixelData.length) {
					previousPixelData = new int[pixelData.length];
				}
				System.arraycopy(pixelData, 0, previousPixelData, 0, pixelData.length);

				long profU2 = PROFILE ? System.currentTimeMillis() : 0;
				if (uiTextureId != 0) {
					NativeGL.drawUIOverlay(ctx, uiTextureId, (float) width / potWidth, (float) height / potHeight);
				}
				if (PROFILE) {
					profileUiConvertMs += profU1 - profU0;
					profileUiUploadMs += profU2 - profU1;
					profileUiDrawMs += System.currentTimeMillis() - profU2;
				}
			} else {
				// Unchanged since last frame - the already-uploaded texture is
				// still byte-for-byte correct, so just redraw it (still needed
				// every frame regardless - beginFrame() clears the color
				// buffer each frame, so nothing persists on screen without
				// this) without touching the conversion/upload work at all.
				long profU2 = PROFILE ? System.currentTimeMillis() : 0;
				NativeGL.drawUIOverlay(ctx, uiTextureId, (float) width / potWidth, (float) height / potHeight);
				if (PROFILE) {
					profileUiDrawMs += System.currentTimeMillis() - profU2;
				}
			}
		}

		NativeGL.endFrame(ctx);

		if (PROFILE) {
			profileTotalMs += System.currentTimeMillis() - profU0;
			profileFrameCount++;
			if (profileFrameCount >= PROFILE_REPORT_EVERY) {
				reportProfile();
			}
		}
	}

	/**
	 * Temporary profiling instrumentation for a real profiling pass (not
	 * guesswork) requested after the crash-fix + cheaper-pick-restore
	 * round, to find out what's actually slow rather than optimizing by
	 * hypothesis. Prints averages to System.out every PROFILE_REPORT_EVERY
	 * frames, then resets the accumulators. Remove once its job is done -
	 * see PLAN.md.
	 */
	private void reportProfile() {
		float n = (float) profileFrameCount;
		System.out.println("GLSceneRenderer PROFILE (avg ms over " + profileFrameCount + " frames):"
				+ " rotate=" + (profileRotateMs / n)
				+ " fill=" + (profileFillMs / n)
				+ " draw=" + (profileDrawMs / n)
				+ " sprite.total=" + (profileSpriteMs / n)
				+ " [occlusion=" + (profileSpriteOcclusionMs / n)
				+ " [read=" + (profileOcclusionReadMs / n)
				+ " cpu=" + (profileOcclusionCpuMs / n)
				+ " finalize=" + (profileOcclusionFinalizeMs / n) + "]]"
				+ " pick.total=" + (profilePickTotalMs / n)
				+ " [build=" + (profilePickBuildMs / n)
				+ " gl=" + (profilePickGLMs / n)
				+ " readback=" + (profilePickReadbackMs / n)
				+ " restore=" + (profilePickRestoreMs / n) + "]"
				+ " ui.convert=" + (profileUiConvertMs / n)
				+ " ui.upload=" + (profileUiUploadMs / n)
				+ " ui.draw=" + (profileUiDrawMs / n)
				+ " TOTAL=" + (profileTotalMs / n)
				+ " triVerts=" + profileTriVerts
				+ " pickVerts=" + profilePickVerts);
		profileFrameCount = 0;
		profileRotateMs = 0;
		profileFillMs = 0;
		profileDrawMs = 0;
		profileSpriteMs = 0;
		profileSpriteOcclusionMs = 0;
		profileOcclusionReadMs = 0;
		profileOcclusionCpuMs = 0;
		profileOcclusionFinalizeMs = 0;
		profilePickTotalMs = 0;
		profilePickBuildMs = 0;
		profilePickGLMs = 0;
		profilePickReadbackMs = 0;
		profilePickRestoreMs = 0;
		profileUiConvertMs = 0;
		profileUiUploadMs = 0;
		profileUiDrawMs = 0;
		profileTotalMs = 0;
	}

	private int textureBucketOf(int rawResourceId, int textureSlots) {
		if (rawResourceId >= 0 && rawResourceId < textureSlots && indexedPixels[rawResourceId] != null) {
			return rawResourceId;
		}
		return -1;
	}

	/**
	 * Emits one face, in either its normal winding (reversed=false, using
	 * the face's front side) or reversed winding (reversed=true, using the
	 * back side) - see the class doc comment on double-sided faces. Writes
	 * into either flatBuf (at flatOffset, returned via the 1-element
	 * holder array so this can update it like an out-parameter) or
	 * texBufs[bucket]/texOffsets[bucket], depending on whether
	 * rawResourceId resolves to a real uploaded texture.
	 */
	private int emitFaceSide(RSModel model, int f, int n, int rawResourceId, boolean reversed, int textureSlots,
			float[] flatBuf, int flatOffset, float[][] texBufs, int[] texOffsets) {
		int bucket = textureBucketOf(rawResourceId, textureSlots);
		boolean textured = bucket >= 0;

		float baseR = 1f;
		float baseG = 1f;
		float baseB = 1f;
		if (textured) {
			ensureTextureUploaded(bucket);
		} else {
			int color = resourceToColor(rawResourceId, true);
			baseR = ((color >> 16) & 0xFF) / 255f;
			baseG = ((color >> 8) & 0xFF) / 255f;
			baseB = (color & 0xFF) / 255f;
		}

		float[] u = null;
		float[] v = null;
		if (textured) {
			u = new float[n];
			v = new float[n];
			cornerUV(n, u, v);
		}

		boolean flatLit = model.faceDiffuseLight[f] != Scene.TRANSPARENT;
		float flatBrightness = flatLit
				? lightScalarToBrightness(model.diffuseParam1 + model.faceDiffuseLight[f])
				: 0f;

		float[] out = textured ? texBufs[bucket] : flatBuf;
		int offset = textured ? texOffsets[bucket] : flatOffset;

		int[] indices = model.faceIndices[f];
		for (int i = 1; i < n - 1; ++i) {
			int a0 = 0;
			int a1 = i;
			int a2 = i + 1;
			if (reversed) {
				int t = a1;
				a1 = a2;
				a2 = t;
			}
			offset = emitVertex(model, indices, a0, textured, u, v, baseR, baseG, baseB, flatLit, flatBrightness,
					out, offset);
			offset = emitVertex(model, indices, a1, textured, u, v, baseR, baseG, baseB, flatLit, flatBrightness,
					out, offset);
			offset = emitVertex(model, indices, a2, textured, u, v, baseR, baseG, baseB, flatLit, flatBrightness,
					out, offset);
		}

		if (textured) {
			texOffsets[bucket] = offset;
			return flatOffset;
		} else {
			return offset;
		}
	}

	private int emitVertex(RSModel model, int[] indices, int cornerIndex, boolean textured, float[] u, float[] v,
			float baseR, float baseG, float baseB, boolean flatLit, float flatBrightness, float[] out, int offset) {
		int vertIndex = indices[cornerIndex];
		float brightness = flatLit ? flatBrightness
				: lightScalarToBrightness(model.diffuseParam1 + model.vertDiffuseLight[vertIndex]
						+ model.vertLightOther[vertIndex]);
		float r = baseR * brightness;
		float g = baseG * brightness;
		float b = baseB * brightness;
		float uu = textured ? u[cornerIndex] : 0f;
		float vv = textured ? v[cornerIndex] : 0f;
		return putVertex(model, vertIndex, r, g, b, uu, vv, out, offset);
	}

	private float lightScalarToBrightness(int scalar) {
		float brightness = scalar / LIGHT_NORM;
		if (brightness < LIGHT_MIN) {
			return LIGHT_MIN;
		}
		if (brightness > LIGHT_MAX) {
			return LIGHT_MAX;
		}
		return brightness;
	}

	private void ensureTextureUploaded(int resourceId) {
		if (glTextureIds[resourceId] != 0) {
			return;
		}
		byte[] indexed = indexedPixels[resourceId];
		int[] palette = palettes[resourceId];
		if (indexed == null || palette == null) {
			return;
		}
		int size = highResFlags[resourceId] != 0 ? 128 : 64;
		byte[] rgba = new byte[size * size * 4];
		for (int p = 0; p < size * size; ++p) {
			int color = decodeTexel(palette[indexed[p] & 0xFF]);
			int o = p * 4;
			rgba[o] = (byte) ((color >> 16) & 0xFF);
			rgba[o + 1] = (byte) ((color >> 8) & 0xFF);
			rgba[o + 2] = (byte) (color & 0xFF);
			// color == 0 is Scene's color-key sentinel (see decodeTexel) -
			// make it transparent instead of opaque black.
			rgba[o + 3] = (byte) (color == 0 ? 0x00 : 0xFF);
		}
		glTextureIds[resourceId] = NativeGL.uploadTexture(ctx, size, size, rgba);
	}

	/**
	 * Replicates Scene's real texture decode (the private b(int,boolean) /
	 * setFrustum(int,byte) pixel-resolve routine): every raw palette color
	 * is masked with 0xF8F8FF, and a masked result of exactly 0xF800FF
	 * (magenta) is a color-key sentinel meaning "no pixel here" - Scene
	 * zeroes it and flags the whole texture (m_S) so its shader variant
	 * skips those pixels. GLSceneRenderer has no CPU shader variants to
	 * pick between, so it does the GL equivalent instead: decode to 0 here,
	 * upload as alpha 0, and rely on GL_ALPHA_TEST (see
	 * NativeGL.setPerspectiveFrustum) to discard those fragments.
	 */
	private int decodeTexel(int rawPaletteColor) {
		int masked = rawPaletteColor & 0xF8F8FF;
		if (masked == 0) {
			return 1;
		}
		if (masked == 0xF800FF) {
			return 0;
		}
		return masked;
	}

	/** See the class doc comment - approximated, this engine stores no real per-vertex UVs. */
	private void cornerUV(int n, float[] u, float[] v) {
		if (n == 4) {
			u[0] = 0f; v[0] = 0f;
			u[1] = 1f; v[1] = 0f;
			u[2] = 1f; v[2] = 1f;
			u[3] = 0f; v[3] = 1f;
		} else if (n == 3) {
			u[0] = 0f; v[0] = 0f;
			u[1] = 1f; v[1] = 0f;
			u[2] = 0.5f; v[2] = 1f;
		} else {
			for (int i = 0; i < n; ++i) {
				u[i] = (float) i / (float) (n - 1);
				v[i] = 0f;
			}
		}
	}

	private int putVertex(RSModel model, int vertIndex, float r, float g, float b, float u, float v,
			float[] out, int offset) {
		// Negate Y (this engine's screen-space Y+ is downward, GL's is up)
		// and Z (this engine's camera looks down +Z, GL's default looks
		// down -Z).
		out[offset++] = model.vertXRot[vertIndex];
		out[offset++] = -model.vertYRot[vertIndex];
		out[offset++] = -model.vertZRot[vertIndex];
		out[offset++] = r;
		out[offset++] = g;
		out[offset++] = b;
		out[offset++] = u;
		out[offset++] = v;
		return offset;
	}

	public void setCamera(int centerX, int centerY, int centerZ, int xRot, int yRot, int zRot, int offset) {
		// Mirrors Scene.setCamera() exactly - self-contained trig producing
		// the offsets/angles RSModel.rotate1024() needs, reused verbatim
		// since GLSceneRenderer can't extend Scene to inherit it.
		xRot &= 1023;
		yRot &= 1023;
		zRot &= 1023;

		this.cameraProjX = 1024 - xRot & 1023;
		this.cameraProjY = 1024 - yRot & 1023;
		this.cameraProjZ = 1024 - zRot & 1023;

		int offX = 0;
		int offY = 0;
		int offZ = offset;
		int sin;
		int cos;
		int tmp;

		if (xRot != 0) {
			sin = FastMath.trigTable_1024[xRot];
			cos = FastMath.trigTable_1024[xRot + 1024];
			tmp = cos * offY - sin * offset >> 15;
			offZ = sin * offY + offset * cos >> 15;
			offY = tmp;
		}

		if (yRot != 0) {
			sin = FastMath.trigTable_1024[yRot];
			cos = FastMath.trigTable_1024[yRot + 1024];
			tmp = offX * cos + offZ * sin >> 15;
			offZ = cos * offZ - sin * offX >> 15;
			offX = tmp;
		}

		if (zRot != 0) {
			cos = FastMath.trigTable_1024[zRot + 1024];
			sin = FastMath.trigTable_1024[zRot];
			tmp = offX * cos + sin * offY >> 15;
			offY = offY * cos - sin * offX >> 15;
			offX = tmp;
		}

		this.rot1024_off_z = centerZ - offZ;
		this.rot1024_off_y = centerY - offY;
		this.rot1024_off_x = centerX - offX;
	}

	/**
	 * Records the mouse position performPicking() will query this frame -
	 * mirrors Scene.setMouseLoc() (confirmed against its real call site,
	 * `scene.setMouseLoc(0, this.mouseX, this.mouseY)`, called once per
	 * frame with mudclient's own already-top-left-origin mouse coordinates
	 * - no rescaling needed here, unlike Scene's `x - m_Zb` centering,
	 * since performPicking() works entirely in screen pixel space via GL
	 * scissoring/readback rather than Scene's centered projection space).
	 */
	public void setMouseLoc(int arg, int x, int y) {
		this.mouseX = x;
		this.mouseY = y;
	}

	/**
	 * Picking result count - always 0 or 1 here (never Scene's up-to-100),
	 * since a GL color-ID readback can only ever report whichever single
	 * face's fragment actually won the depth test at that pixel. Callers
	 * (mudclient) already loop `count` times over b(byte)/getQB(byte), so
	 * a smaller-than-Scene count is a safe, valid input, not a special
	 * case they need to handle differently.
	 */
	public int b(int arg) {
		return pickHitCount;
	}

	public RSModel[] b(byte arg) {
		return pickHitModels;
	}

	public int[] getQB(byte arg) {
		return pickHitFaceIndices;
	}

	/**
	 * Was a stub returning null before sprite picking existed - no caller
	 * needed the real answer yet, since nothing ever set pickHitModels[0]
	 * to spriteBillboardModel. Now that performSpritePicking() does,
	 * mudclient's own pick-result dispatch (which decides "sprite vs. world
	 * object" by checking `scene.getSpriteBillboardModel() == pickedModel`)
	 * needs the real field back, or every sprite hit would still route to
	 * the world-object branch instead - confirmed exactly that: sprite
	 * picking detected hits correctly but characters were still never
	 * selectable, because this always returned null.
	 */
	public RSModel getSpriteBillboardModel() {
		return spriteBillboardModel;
	}

	/**
	 * See the interface doc comment for why this exists at all:
	 * endScene() here submits geometry straight to the native GL context,
	 * never touching graphics.pixelData the way Scene's software renderer
	 * does - so anything that reads pixelData expecting the just-rendered
	 * frame to already be there (confirmed case: mudclient's login-screen
	 * carousel, which calls scene.endScene() from several fixed camera
	 * angles purely to snapshot each result via
	 * GraphicsController.storeSpriteVert() - not part of the normal per-
	 * frame game loop) needs this pulled back explicitly first. Reads the
	 * whole canvas back via NativeGL.readColorRect() (a plain glReadPixels,
	 * not a redraw - the frame is already sitting in the back buffer from
	 * endScene()'s own drawTriangles() calls) and writes it into
	 * graphics.pixelData, flipping the row order the same way
	 * occludeAgainstDepthBuffer() already has to for depth reads.
	 */
	public void captureFrameToPixelData() {
		if (ctx == 0 || graphics.pixelData == null) {
			return;
		}
		int width = graphics.width2;
		int height = graphics.height2;
		int[] colors = new int[width * height];
		NativeGL.readColorRect(ctx, 0, 0, width, height, height, colors);

		int[] pixelData = graphics.pixelData;
		int count = Math.min(colors.length, pixelData.length);
		for (int yy = 0; yy < height; ++yy) {
			int srcRow = (height - 1 - yy) * width;
			int dstRow = yy * width;
			for (int xx = 0; xx < width; ++xx) {
				int dstIdx = dstRow + xx;
				if (dstIdx >= count) {
					break;
				}
				pixelData[dstIdx] = colors[srcRow + xx];
			}
		}
	}

	/**
	 * Registers one sprite billboard (player/NPC/projectile/item) for this
	 * frame - mirrors Scene.drawSprite() exactly, including its parameter
	 * order, confirmed against a real call site (mudclient's per-player
	 * loop: `scene.drawSprite(centerX + 5000, playerZ, centerX + 10000,
	 * playerX, -world.getElevation(...), 145, 220, (byte) 109)`) rather
	 * than assumed from Scene's own signature alone: arg1 = entity id
	 * (drawEntity's dispatch range - see MudClientGraphics.drawEntity()),
	 * arg2 = world Z, arg3 = pick index, arg4 = world X, arg5 = world Y
	 * (ground/base level), arg6 = width, arg7 = height, arg8 = unused in
	 * Scene's own implementation too.
	 *
	 * arg3 (pickIndex) is mudclient's own `entityType * 10000 +
	 * arrayIndex` packed id (confirmed against Scene.drawSprite(), which
	 * writes it to `this.m_T.facePickIndex[this.m_n]` immediately on
	 * registration, and mudclient's real decode at its picking-menu call
	 * site: `id = model.facePickIndex[faceIndex] / 10000`,
	 * `arrayIndex = model.facePickIndex[faceIndex] % 10000` - e.g. id 3
	 * means an NPC, arrayIndex indexes mudclient's own npcs[] array).
	 * Recorded the same way here: spriteBillboardModel.facePickIndex[]
	 * is populated at the same insertFace() index this sprite's face
	 * actually lands at, so performPicking()'s sprite hit-test (see its
	 * doc comment) can report a hit exactly the way mudclient already
	 * knows how to decode - no new protocol on mudclient's side needed.
	 *
	 * Inserts a 2-vertex "pole" (base to base+height) into
	 * spriteBillboardModel, exactly like Scene does into m_T - reusing
	 * RSModel's own insertVertex2()/insertFace() rather than tracking
	 * world positions separately, so the same rotate1024() call in
	 * endScene() projects these along with everything else.
	 */
	public int drawSprite(int entityId, int worldZ, int pickIndex, int worldX, int worldY, int widthRaw,
			int heightRaw, byte skew) {
		int base = spriteBillboardModel.insertVertex2(false, worldZ, worldX, worldY);
		int top = spriteBillboardModel.insertVertex2(false, worldZ, worldX, worldY - heightRaw);
		int faceIndex = spriteBillboardModel.insertFace(2, new int[]{base, top}, 0, 0, false);
		spriteBillboardModel.facePickIndex[faceIndex] = pickIndex;

		SpriteDraw draw = new SpriteDraw();
		draw.entityId = entityId;
		draw.baseVertexIndex = base;
		draw.topVertexIndex = top;
		draw.widthRaw = widthRaw;
		draw.heightRaw = heightRaw;
		draw.faceIndex = faceIndex;
		spriteDraws.add(draw);
		return spriteDraws.size() - 1;
	}

	public void loadTexture(int resourceId, int[] palette, int highRes, byte[] indexedBitmap) {
		if (palettes == null || resourceId >= palettes.length) {
			return;
		}
		palettes[resourceId] = palette;
		indexedPixels[resourceId] = indexedBitmap;
		highResFlags[resourceId] = highRes;
		glTextureIds[resourceId] = 0;
	}

	public int resourceToColor(int resource, boolean arg) {
		if (resource == Scene.TRANSPARENT) {
			return 0;
		}
		if (resource >= 0) {
			if (palettes == null || resource >= palettes.length || palettes[resource] == null) {
				return 0;
			}
			int index = indexedPixels[resource][0] & 0xFF;
			return decodeTexel(palettes[resource][index]);
		}
		int packed = -(resource + 1);
		int r5 = (packed & 0x7C00) >> 10;
		int g5 = (packed & 0x3E0) >> 5;
		int b5 = packed & 0x1F;
		return (b5 << 3) + (g5 << 11) + (r5 << 19);
	}

	public void setFrustum(int arg1, int arg2, int arg3, int textureCount) {
		palettes = new int[textureCount][];
		indexedPixels = new byte[textureCount][];
		highResFlags = new int[textureCount];
		glTextureIds = new int[textureCount];
	}

	public void setFrustum(int var1, int var2, int fromModelIndex, int var4, int var5, int var6) {
		// Mirrors Scene's real "relight models from fromModelIndex onward"
		// call exactly, including its pre-loop default-value quirk, then
		// forwards to RSModel.setDiffuseLight() with the same argument
		// order Scene uses (reused as-is, not reimplemented - see the
		// class doc comment on why the underlying math can't be
		// reimplemented here even if wanted: its inputs are private on
		// RSModel).
		if (var4 == 0 && var6 == 0 && var1 == 0) {
			var4 = 32;
		}
		int modelCount = models.size();
		for (int i = fromModelIndex; i < modelCount; ++i) {
			RSModel model = (RSModel) models.get(i);
			model.setDiffuseLight(var2, var5, var6, -115, var4, var1);
		}
	}

	public void d(int arg1, int resourceId) {
		// Per-frame texture animation effect: no-op for now.
	}

	// No-op deliberately, not an oversight: per Scene's real source, both of
	// these only affect Scene's picking hit-test region/exclusion, never
	// the actual drawEntity() rendering call - see drawSpriteBillboards()'s
	// doc comment. Nothing to implement until picking exists.
	public void setFaceSpriteLocalPlayer(int arg1, int arg2) {
	}

	public void setCombatXOffset(int arg1, int arg2, int arg3) {
	}

	public void setDiffuseDir(int dirZ, int dirY, boolean arg, int dirX) {
	}

	/**
	 * Mirrors Scene.setMidpoints() field-for-field (confirmed against
	 * mudclient's real call: `scene.setMidpoints(halfGameHeight(), true,
	 * getGameWidth(), halfGameWidth(), halfGameHeight(), m_qd,
	 * halfGameWidth())`, and Scene's own body, which maps arg6 to
	 * rot1024_vp_src and arg5 to m_Nb - not assumed from this method's
	 * signature alone). mudclient.m_qd (arg6) defaults to 9, not 8 - the
	 * value this renderer wrongly hardcoded from Phase 3 onward until this
	 * call was wired up. arg1/arg4 (half-height/half-width) are also the
	 * authoritative source for halfHeight/halfWidth, superseding the
	 * constructor's estimate from graphics.width2/height2 at whatever point
	 * construction happened to run.
	 */
	public void setMidpoints(int halfGameHeight, boolean arg2, int arg3, int halfGameWidth, int screenMidYArg,
			int vpSrcArg, int screenMidXArg) {
		this.vpSrc = vpSrcArg;
		this.screenMidX = screenMidXArg;
		this.screenMidY = screenMidYArg;
		this.halfHeight = halfGameHeight;
		this.halfWidth = halfGameWidth;
	}

	/**
	 * Clears this frame's sprite-billboard registrations, called at the
	 * start of mudclient's per-entity draw loop (confirmed at its real call
	 * site: `scene.reduceSprites((byte) 67, this.spriteCount);` immediately
	 * followed by `this.spriteCount = 0;`, right before iterating
	 * players/NPCs/items to call drawSprite() for each) - so this is
	 * exactly the right place to reset, not a guess. Scene's real
	 * implementation shrinks m_T's arrays by `count`; this renderer uses a
	 * plain List and a reusable RSModel instead, so the simpler and
	 * equally correct equivalent is just clearing both outright.
	 */
	public void reduceSprites(byte arg, int count) {
		spriteDraws.clear();
		spriteBillboardModel.resetFaceVertHead(1);
	}

	public void setFogLandscapeDistance(int distance) {
		this.fogLandscapeDistance = distance;
	}

	public void setFogEntityDistance(int distance) {
		this.fogEntityDistance = distance;
	}

	public void setFogZFalloff(int falloff) {
		this.fogZFalloff = falloff;
	}

	public void setFogSmoothingStartDistance(int distance) {
		this.fogSmoothingStartDistance = distance;
	}
}
