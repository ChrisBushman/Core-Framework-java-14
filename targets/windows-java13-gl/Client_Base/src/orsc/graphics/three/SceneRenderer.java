package orsc.graphics.three;

/**
 * The exact external contract mudclient/World/PacketHandler use against the
 * 3D renderer, extracted as-is from {@link Scene} (method names/signatures
 * are unchanged, including the obfuscation-leftover ones) so a GPU-backed
 * implementation can be swapped in later without touching call sites. See
 * ../../../../../PLAN.md Phase 1.
 */
public interface SceneRenderer {

	/** Registers a model to be rendered until removeModel() is called. */
	void addModel(RSModel mod);

	/** Unregisters a previously added model. */
	void removeModel(RSModel model);

	/** Renders the frame: transform, cull/sort, and rasterize every added model. */
	void endScene(int arg);

	/** Positions/orients the camera for the frame about to be rendered. */
	void setCamera(int centerX, int centerY, int centerZ, int xRot, int yRot, int zRot, int offset);

	/** Records the current mouse position for the picking query resolved after endScene(). */
	void setMouseLoc(int arg, int x, int y);

	/** Picking query result: number of models hit at the last setMouseLoc() position. */
	int b(int arg);

	/** Picking query result: the models hit at the last setMouseLoc() position. */
	RSModel[] b(byte arg);

	/** Picking query result: per-hit face/polygon ids, parallel to b(byte). */
	int[] getQB(byte arg);

	/** Registers a sprite billboard for the frame; returns its scene-local id. */
	int drawSprite(int arg1, int arg2, int arg3, int arg4, int arg5, int arg6, int arg7, byte arg8);

	/** Uploads/replaces raw pixel data for a texture/resource slot. */
	void loadTexture(int resourceId, int[] pixels, int arg1, byte[] arg2);

	/** Converts a resource id (or packed color) to an RGB color for shading. */
	int resourceToColor(int resource, boolean arg);

	/** Allocates the resource/texture lookup tables for a world of the given size and texture count. */
	void setFrustum(int arg1, int arg2, int arg3, int textureCount);

	/** Re-applies diffuse lighting to every model from the given index onward. */
	void setFrustum(int arg1, int arg2, int fromModelIndex, int arg4, int arg5, int arg6);

	/** Applies a per-frame texture animation/rotation effect to a resource slot. */
	void d(int arg1, int resourceId);

	/** Sets the local player's face/sprite orientation offset used when drawing. */
	void setFaceSpriteLocalPlayer(int arg1, int arg2);

	/** Sets the combat-animation X offset applied to a model's draw position. */
	void setCombatXOffset(int arg1, int arg2, int arg3);

	/** Sets the global diffuse light direction used for model shading. */
	void setDiffuseDir(int dirZ, int dirY, boolean arg, int dirX);

	/** Sets interpolation midpoints used when blending a model's animation frames. */
	void setMidpoints(int arg1, boolean arg2, int arg3, int arg4, int arg5, int arg6, int arg7);

	/** Undoes the last `count` drawSprite() calls made this frame. */
	void reduceSprites(byte arg, int count);

	/** Clears every added model, e.g. on floor/plane change. */
	void removeAllGameObjects(boolean arg);

	/** Sets the distance at which landscape fades out. */
	void setFogLandscapeDistance(int distance);

	/** Sets the distance at which entities (players/NPCs/items) fade out. */
	void setFogEntityDistance(int distance);

	/** Sets how sharply fog falls off with distance. */
	void setFogZFalloff(int falloff);

	/** Sets the distance at which fog smoothing/blending begins. */
	void setFogSmoothingStartDistance(int distance);

	/** The internal aggregate model that drawSprite()-registered billboards belong to; used to tell picked sprites apart from picked world models. */
	RSModel getSpriteBillboardModel();

	/**
	 * For renderers whose endScene() doesn't itself composite into
	 * GraphicsController's pixelData (GLSceneRenderer, which submits
	 * geometry to its own native GL context instead): pulls the frame
	 * endScene() just rendered back into pixelData, so code that reads
	 * pixelData directly afterward (e.g. mudclient's login-screen carousel
	 * snapshot, GraphicsController.storeSpriteVert()) sees the real
	 * rendered content rather than whatever was left over from earlier.
	 * A no-op for Scene, which already writes pixelData directly as part
	 * of endScene() itself - nothing to pull back.
	 */
	void captureFrameToPixelData();
}
