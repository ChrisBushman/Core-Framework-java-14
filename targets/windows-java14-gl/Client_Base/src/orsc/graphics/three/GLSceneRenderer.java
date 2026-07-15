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
 *   now too - see performPicking()'s doc comment. It's a GL color-ID
 *   render pass, not a port of Scene's scanline-edge hit test (which is a
 *   large, intricate part of Scene.java - reusing the GPU's own
 *   rasterizer/depth-test to answer "what's under the mouse" fits this
 *   project's whole direction better than duplicating that CPU algorithm
 *   a second time). Covers world/scenery geometry only, not sprite
 *   billboards (players/NPCs/items) - a documented gap, since this round
 *   was scoped to click-to-*move* specifically.
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

	private static final class SpriteDraw {
		int entityId;
		int baseVertexIndex;
		int topVertexIndex;
		int widthRaw;
		int heightRaw;
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
			ctx = NativeGL.createContext(graphics.width2, graphics.height2, "OpenRSC (GL, experimental)");
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
		for (int m = 0; m < modelCount; ++m) {
			RSModel model = (RSModel) models.get(m);
			model.rotate1024(rot1024_off_y, vpSrc, rot1024_off_x, (byte) -122, rot1024_off_z,
					cameraProjY, cameraProjZ, cameraProjX, Z_TOP);
		}
		long profT1 = PROFILE ? System.currentTimeMillis() : 0;

		int textureSlots = (palettes != null) ? palettes.length : 0;
		int[] texVertexCounts = new int[textureSlots];
		int flatVertexCount = 0;

		// Pass 1: count vertices per bucket (flat, or per texture id) so
		// pass 2 can fill pre-sized arrays with no dynamic growth. Front
		// and back textures are counted independently - see the class doc
		// comment on double-sided faces.
		for (int m = 0; m < modelCount; ++m) {
			RSModel model = (RSModel) models.get(m);
			if (!model.m_dc) {
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

		float[] flatBuf = flatVertexCount > 0 ? new float[flatVertexCount * FLOATS_PER_VERTEX] : null;
		float[][] texBufs = new float[textureSlots][];
		for (int t = 0; t < textureSlots; ++t) {
			if (texVertexCounts[t] > 0) {
				texBufs[t] = new float[texVertexCounts[t] * FLOATS_PER_VERTEX];
			}
		}

		int flatOffset = 0;
		int[] texOffsets = new int[textureSlots];

		// Pass 2: fill.
		for (int m = 0; m < modelCount; ++m) {
			RSModel model = (RSModel) models.get(m);
			if (!model.m_dc) {
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
			if (texBufs[t] != null && glTextureIds[t] != 0) {
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
	 * Result is always 0 or 1 hits (see b(int)'s doc comment), and covers
	 * world/scenery geometry only - sprite billboards (players/NPCs/items)
	 * aren't pickable yet, a documented gap, not an oversight: this round
	 * of work was scoped to click-to-*move*, which only needs ground/wall
	 * geometry to be pickable.
	 */
	private void performPicking() {
		long profP0 = PROFILE ? System.currentTimeMillis() : 0;
		pickFaceRefs.clear();
		pickHitCount = 0;

		int modelCount = models.size();
		int pickVertexCount = 0;
		for (int m = 0; m < modelCount; ++m) {
			RSModel model = (RSModel) models.get(m);
			if (!model.m_dc) {
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

		float[] pickBuf = new float[pickVertexCount * FLOATS_PER_VERTEX];
		int offset = 0;
		for (int m = 0; m < modelCount; ++m) {
			RSModel model = (RSModel) models.get(m);
			if (!model.m_dc) {
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
		// what mudclient sees, so it gets its own check regardless.
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
		int[] screenXs = new int[count];
		int[] topYs = new int[count];
		int[] widths = new int[count];
		int[] heights = new int[count];
		int[] overlayMovements = new int[count];
		int[] topPixelSkews = new int[count];
		int[] baseDepths = new int[count];
		int[] topDepths = new int[count];
		boolean[] visible = new boolean[count];

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
		// here: spriteOrder.length is the count of sprites visible on screen
		// this frame, not a scene-wide total.
		int[] spriteOrder = new int[count];
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

		for (int k = 0; k < count; ++k) {
			int i = spriteOrder[k];
			if (!visible[i]) {
				continue;
			}
			SpriteDraw d = (SpriteDraw) spriteDraws.get(i);
			graphics.drawEntity(d.entityId, screenXs[i], topYs[i], widths[i], heights[i],
					overlayMovements[i], topPixelSkews[i]);
		}

		long occT0 = PROFILE ? System.currentTimeMillis() : 0;
		if (ctx != 0) {
			for (int k = 0; k < count; ++k) {
				int i = spriteOrder[k];
				if (!visible[i]) {
					continue;
				}
				occludeAgainstDepthBuffer(screenXs[i], topYs[i], widths[i], heights[i], topDepths[i], baseDepths[i]);
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
		for (int i = 0; i < count; ++i) {
			if (!visible[i]) {
				continue;
			}
			finalizeGapColor(screenXs[i], topYs[i], widths[i], heights[i]);
		}
		if (PROFILE) {
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
	 * See drawSpriteBillboards()'s doc comment. Reads back the real
	 * geometry's depth buffer for exactly this sprite's (clamped) rect and,
	 * for any pixel where that depth is nearer to the camera than the
	 * sprite's own depth at that row, resets pixelData back to the fill
	 * sentinel there. Also records, for every pixel in the rect regardless
	 * of the occlusion outcome, the final color into spriteExpectedColor -
	 * folded into this same loop rather than a separate pass, since this
	 * method already visits every pixel in the rect once.
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
	private void occludeAgainstDepthBuffer(int x, int y, int width, int height, int topDepth, int baseDepth) {
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
		int rectWidth = x1 - x0;
		int rectHeight = y1 - y0;
		if (rectWidth <= 0 || rectHeight <= 0) {
			return;
		}

		float[] depths = new float[rectWidth * rectHeight];
		NativeGL.readDepthRect(ctx, x0, y0, rectWidth, rectHeight, canvasHeight, depths);

		float near = Z_TOP;
		float far = Z_FAR;
		// height - 1 can be 0 for a 1px-tall (clamped-degenerate) rect;
		// guard against dividing by zero rather than special-casing it away.
		float rowSpan = (height > 1) ? (float) (height - 1) : 1f;
		for (int yy = y0; yy < y1; ++yy) {
			float rowFrac = (yy - y) / rowSpan;
			float spriteDepth = topDepth + rowFrac * (baseDepth - topDepth);

			// readDepthRect's buffer comes back GL-style, bottom row first -
			// flip when mapping to pixelData's top-down rows (see its doc
			// comment).
			int depthRow = (y1 - 1 - yy) * rectWidth;
			int rowBase = yy * canvasWidth;
			for (int xx = x0; xx < x1; ++xx) {
				float d = depths[depthRow + (xx - x0)];
				float ndcZ = 2f * d - 1f;
				float eyeDistance = (2f * near * far) / (far + near - ndcZ * (far - near));
				int idx = rowBase + xx;
				if (eyeDistance < spriteDepth) {
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
			byte[] rgba = new byte[width * height * 4];
			int pixelCount = Math.min(width * height, pixelData.length);
			boolean haveExpected = spriteExpectedColor != null && spriteExpectedColor.length == pixelData.length;
			for (int p = 0; p < pixelCount; ++p) {
				int rgb = pixelData[p] & 0xFFFFFF;
				int o = p * 4;
				rgba[o] = (byte) ((rgb >> 16) & 0xFF);
				rgba[o + 1] = (byte) ((rgb >> 8) & 0xFF);
				rgba[o + 2] = (byte) (rgb & 0xFF);
				boolean spriteOwned = haveExpected && spriteExpectedColor[p] == rgb;
				boolean transparent = spriteOwned ? (rgb == 0xF800FF) : (rgb == 0);
				rgba[o + 3] = (byte) (transparent ? 0x00 : 0xFF);
			}
			long profU1 = PROFILE ? System.currentTimeMillis() : 0;
			if (uiTextureId == 0 || uiTextureWidth != width || uiTextureHeight != height) {
				if (uiTextureId != 0) {
					NativeGL.deleteTexture(ctx, uiTextureId);
				}
				uiTextureId = NativeGL.uploadTexture(ctx, width, height, rgba);
				uiTextureWidth = width;
				uiTextureHeight = height;
			} else {
				NativeGL.updateTexture(ctx, uiTextureId, width, height, rgba);
			}
			long profU2 = PROFILE ? System.currentTimeMillis() : 0;
			if (uiTextureId != 0) {
				NativeGL.drawUIOverlay(ctx, uiTextureId);
			}
			if (PROFILE) {
				profileUiConvertMs += profU1 - profU0;
				profileUiUploadMs += profU2 - profU1;
				profileUiDrawMs += System.currentTimeMillis() - profU2;
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
				+ " [occlusion=" + (profileSpriteOcclusionMs / n) + "]"
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

	public RSModel getSpriteBillboardModel() {
		return null;
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
	 * arg2 = world Z, arg3 = pick index (unused - no picking yet), arg4 =
	 * world X, arg5 = world Y (ground/base level), arg6 = width, arg7 =
	 * height, arg8 = unused in Scene's own implementation too.
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
		spriteBillboardModel.insertFace(2, new int[]{base, top}, 0, 0, false);

		SpriteDraw draw = new SpriteDraw();
		draw.entityId = entityId;
		draw.baseVertexIndex = base;
		draw.topVertexIndex = top;
		draw.widthRaw = widthRaw;
		draw.heightRaw = heightRaw;
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
