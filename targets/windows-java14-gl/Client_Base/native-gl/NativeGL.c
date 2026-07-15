/*
 * Native Win32 + WGL implementation of orsc.graphics.gl.NativeGL, promoted
 * from the Phase 0 spike (../../gl-spike/NativeGL.c) with beginFrame/
 * endFrame split out of the old single renderFrame() call so
 * GLSceneRenderer can submit real geometry between them (Phase 3).
 *
 * Same two gotchas as the spike apply here (see ../../gl-spike/../PLAN.md):
 * MinGW stdcall name decoration needs -Wl,--kill-at at link time, and the
 * Win32 message queue / WGL current-context are thread-affine - all calls
 * for a given ctx must come from the same Java thread that created it.
 *
 * Only OpenGL 1.1 fixed-function calls are used anywhere in here, matching
 * what a Voodoo2 MiniGL ICD can actually support.
 */

#include <jni.h>
#include <windows.h>
#include <GL/gl.h>
#include <stdlib.h>

#define MAX_CONTEXTS 4
#define MAX_INPUT_EVENTS 256

/* Input event types, mirrored in NativeGL.java's INPUT_* constants. */
#define INPUT_MOUSE_MOVE 1
#define INPUT_MOUSE_DOWN 2
#define INPUT_MOUSE_UP   3
#define INPUT_KEY_DOWN   4
#define INPUT_KEY_UP     5
#define INPUT_CHAR       6

typedef struct {
	int type;
	int x;
	int y;
	int extra; /* mouse button number, or a Win32 virtual-key/char code */
} InputEvent;

/* Generous fixed cap for the picking-restore save buffer (see
 * setPickScissor/restorePickPixels) - NativeGL.PICK_SCISSOR_SIZE on the
 * Java side is a small constant (3), so this has ample headroom. */
#define PICK_SAVE_BUFFER_MAX 64

typedef struct {
	HWND hwnd;
	HDC hdc;
	HGLRC hglrc;
	InputEvent inputQueue[MAX_INPUT_EVENTS];
	int inputHead;
	int inputTail;
	unsigned char pickSaveBuffer[PICK_SAVE_BUFFER_MAX * PICK_SAVE_BUFFER_MAX * 3];
} GLContext;

static GLContext g_contexts[MAX_CONTEXTS];
static int g_contextCount = 0;
static int g_classRegistered = 0;
static const char *CLASS_NAME = "OrscNativeGLWindowClass";

static GLContext *findContext(HWND hwnd) {
	int i;
	for (i = 0; i < g_contextCount; ++i) {
		if (g_contexts[i].hwnd == hwnd) {
			return &g_contexts[i];
		}
	}
	return NULL;
}

/* Drops the event if the queue is full rather than blocking or growing -
 * a GL window's input rate is low enough (mouse move dominates) that this
 * shouldn't matter in practice, and dropping is a safe failure mode here
 * since every consumer (mudclient's field-based input state) only cares
 * about the latest/most-recent state, not a guaranteed-complete history. */
static void pushInputEvent(HWND hwnd, int type, int x, int y, int extra) {
	GLContext *c = findContext(hwnd);
	int next;
	if (c == NULL) {
		return;
	}
	next = (c->inputTail + 1) % MAX_INPUT_EVENTS;
	if (next == c->inputHead) {
		return;
	}
	c->inputQueue[c->inputTail].type = type;
	c->inputQueue[c->inputTail].x = x;
	c->inputQueue[c->inputTail].y = y;
	c->inputQueue[c->inputTail].extra = extra;
	c->inputTail = next;
}

static void setOrthoProjection(int width, int height) {
	glViewport(0, 0, width, height);
	glMatrixMode(GL_PROJECTION);
	glLoadIdentity();
	glOrtho(-1.0, 1.0, -1.0, 1.0, -1.0, 1.0);
	glMatrixMode(GL_MODELVIEW);
	glLoadIdentity();
}

static LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
	int x, y;
	switch (msg) {
		case WM_CLOSE:
			DestroyWindow(hwnd);
			return 0;
		case WM_DESTROY:
			PostQuitMessage(0);
			return 0;
		case WM_MOUSEMOVE:
			/* Sign-extend: mouse coords are stored as signed 16-bit even
			 * though lParam's low/high words are conventionally read
			 * unsigned - matters near/off the left or top edge. */
			x = (int) (short) (lParam & 0xFFFF);
			y = (int) (short) ((lParam >> 16) & 0xFFFF);
			pushInputEvent(hwnd, INPUT_MOUSE_MOVE, x, y, 0);
			return 0;
		case WM_LBUTTONDOWN:
			x = (int) (short) (lParam & 0xFFFF);
			y = (int) (short) ((lParam >> 16) & 0xFFFF);
			pushInputEvent(hwnd, INPUT_MOUSE_DOWN, x, y, 1);
			return 0;
		case WM_LBUTTONUP:
			x = (int) (short) (lParam & 0xFFFF);
			y = (int) (short) ((lParam >> 16) & 0xFFFF);
			pushInputEvent(hwnd, INPUT_MOUSE_UP, x, y, 1);
			return 0;
		case WM_RBUTTONDOWN:
			x = (int) (short) (lParam & 0xFFFF);
			y = (int) (short) ((lParam >> 16) & 0xFFFF);
			pushInputEvent(hwnd, INPUT_MOUSE_DOWN, x, y, 3);
			return 0;
		case WM_RBUTTONUP:
			x = (int) (short) (lParam & 0xFFFF);
			y = (int) (short) ((lParam >> 16) & 0xFFFF);
			pushInputEvent(hwnd, INPUT_MOUSE_UP, x, y, 3);
			return 0;
		case WM_KEYDOWN:
			pushInputEvent(hwnd, INPUT_KEY_DOWN, 0, 0, (int) wParam);
			return 0;
		case WM_KEYUP:
			pushInputEvent(hwnd, INPUT_KEY_UP, 0, 0, (int) wParam);
			return 0;
		case WM_CHAR:
			pushInputEvent(hwnd, INPUT_CHAR, 0, 0, (int) wParam);
			return 0;
		default:
			return DefWindowProc(hwnd, msg, wParam, lParam);
	}
}

JNIEXPORT jlong JNICALL Java_orsc_graphics_gl_NativeGL_createContext(JNIEnv *env, jclass clazz,
		jint width, jint height, jstring title) {
	const char *titleChars;
	HWND hwnd;
	HDC hdc;
	HGLRC hglrc;
	PIXELFORMATDESCRIPTOR pfd;
	int pixelFormat;

	if (g_contextCount >= MAX_CONTEXTS) {
		return 0;
	}

	if (!g_classRegistered) {
		WNDCLASS wc;
		ZeroMemory(&wc, sizeof(wc));
		wc.lpfnWndProc = WndProc;
		wc.hInstance = GetModuleHandle(NULL);
		wc.lpszClassName = CLASS_NAME;
		wc.hCursor = LoadCursor(NULL, IDC_ARROW);
		wc.hbrBackground = NULL;
		if (!RegisterClass(&wc)) {
			return 0;
		}
		g_classRegistered = 1;
	}

	{
		/* CreateWindowEx's width/height describe the whole window rect
		 * (title bar + borders included), not the client area - passing
		 * width/height directly makes the actual drawable/clickable client
		 * area smaller than intended, compressing the rendered content
		 * (glViewport uses width/height as if they were the client size)
		 * into less space than its own coordinates assume. AdjustWindowRect
		 * computes the outer size needed so the CLIENT area ends up exactly
		 * width x height, matching what glViewport and this engine's mouse
		 * coordinates (already client-relative) both expect. */
		RECT rect;
		rect.left = 0;
		rect.top = 0;
		rect.right = width;
		rect.bottom = height;
		AdjustWindowRect(&rect, WS_OVERLAPPEDWINDOW, FALSE);

		titleChars = (*env)->GetStringUTFChars(env, title, NULL);
		hwnd = CreateWindowEx(0, CLASS_NAME, titleChars, WS_OVERLAPPEDWINDOW,
				CW_USEDEFAULT, CW_USEDEFAULT, rect.right - rect.left, rect.bottom - rect.top,
				NULL, NULL, GetModuleHandle(NULL), NULL);
		(*env)->ReleaseStringUTFChars(env, title, titleChars);
	}

	if (hwnd == NULL) {
		return 0;
	}

	hdc = GetDC(hwnd);
	if (hdc == NULL) {
		DestroyWindow(hwnd);
		return 0;
	}

	ZeroMemory(&pfd, sizeof(pfd));
	pfd.nSize = sizeof(pfd);
	pfd.nVersion = 1;
	pfd.dwFlags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER;
	pfd.iPixelType = PFD_TYPE_RGBA;
	/* 16-bit color to match Voodoo2-era hardware; ChoosePixelFormat picks
	 * the closest match the driver actually offers. */
	pfd.cColorBits = 16;
	pfd.cDepthBits = 16;
	pfd.iLayerType = PFD_MAIN_PLANE;

	pixelFormat = ChoosePixelFormat(hdc, &pfd);
	if (pixelFormat == 0 || !SetPixelFormat(hdc, pixelFormat, &pfd)) {
		ReleaseDC(hwnd, hdc);
		DestroyWindow(hwnd);
		return 0;
	}

	hglrc = wglCreateContext(hdc);
	if (hglrc == NULL) {
		ReleaseDC(hwnd, hdc);
		DestroyWindow(hwnd);
		return 0;
	}

	if (!wglMakeCurrent(hdc, hglrc)) {
		wglDeleteContext(hglrc);
		ReleaseDC(hwnd, hdc);
		DestroyWindow(hwnd);
		return 0;
	}

	setOrthoProjection(width, height);
	glDisable(GL_DEPTH_TEST);

	g_contexts[g_contextCount].hwnd = hwnd;
	g_contexts[g_contextCount].hdc = hdc;
	g_contexts[g_contextCount].hglrc = hglrc;
	g_contexts[g_contextCount].inputHead = 0;
	g_contexts[g_contextCount].inputTail = 0;
	++g_contextCount;

	ShowWindow(hwnd, SW_SHOW);
	UpdateWindow(hwnd);
	SetForegroundWindow(hwnd);
	SetFocus(hwnd);

	return (jlong) (intptr_t) hwnd;
}

JNIEXPORT void JNICALL Java_orsc_graphics_gl_NativeGL_resizeContext(JNIEnv *env, jclass clazz,
		jlong ctxHandle, jint width, jint height) {
	HWND hwnd = (HWND) (intptr_t) ctxHandle;
	GLContext *c = findContext(hwnd);
	if (c == NULL) {
		return;
	}
	wglMakeCurrent(c->hdc, c->hglrc);
	setOrthoProjection(width, height);
}

JNIEXPORT jboolean JNICALL Java_orsc_graphics_gl_NativeGL_pumpMessages(JNIEnv *env, jclass clazz, jlong ctxHandle) {
	MSG msg;
	HWND hwnd = (HWND) (intptr_t) ctxHandle;

	while (PeekMessage(&msg, NULL, 0, 0, PM_REMOVE)) {
		if (msg.message == WM_QUIT) {
			return JNI_FALSE;
		}
		TranslateMessage(&msg);
		DispatchMessage(&msg);
	}

	if (!IsWindow(hwnd)) {
		return JNI_FALSE;
	}

	return JNI_TRUE;
}

JNIEXPORT jint JNICALL Java_orsc_graphics_gl_NativeGL_pollInputEvent(JNIEnv *env, jclass clazz,
		jlong ctxHandle, jintArray outData) {
	HWND hwnd = (HWND) (intptr_t) ctxHandle;
	GLContext *c = findContext(hwnd);
	InputEvent *evt;
	jint buf[3];

	if (c == NULL || c->inputHead == c->inputTail) {
		return 0;
	}

	evt = &c->inputQueue[c->inputHead];
	c->inputHead = (c->inputHead + 1) % MAX_INPUT_EVENTS;

	buf[0] = evt->x;
	buf[1] = evt->y;
	buf[2] = evt->extra;
	(*env)->SetIntArrayRegion(env, outData, 0, 3, buf);

	return evt->type;
}

JNIEXPORT void JNICALL Java_orsc_graphics_gl_NativeGL_beginFrame(JNIEnv *env, jclass clazz,
		jlong ctxHandle, jfloat r, jfloat g, jfloat b) {
	HWND hwnd = (HWND) (intptr_t) ctxHandle;
	GLContext *c = findContext(hwnd);
	if (c == NULL) {
		return;
	}

	/* Re-assert current context every frame rather than assuming it's
	 * still current - same lesson gl2d learned with GLX contexts. */
	wglMakeCurrent(c->hdc, c->hglrc);

	glClearColor(r, g, b, 1.0f);
	glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
}

JNIEXPORT void JNICALL Java_orsc_graphics_gl_NativeGL_endFrame(JNIEnv *env, jclass clazz, jlong ctxHandle) {
	HWND hwnd = (HWND) (intptr_t) ctxHandle;
	GLContext *c = findContext(hwnd);
	if (c == NULL) {
		return;
	}

	glFlush();
	SwapBuffers(c->hdc);
}

JNIEXPORT void JNICALL Java_orsc_graphics_gl_NativeGL_setPerspectiveFrustum(JNIEnv *env, jclass clazz,
		jlong ctxHandle, jfloat left, jfloat right, jfloat bottom, jfloat top, jfloat zNear, jfloat zFar) {
	HWND hwnd = (HWND) (intptr_t) ctxHandle;
	GLContext *c = findContext(hwnd);
	if (c == NULL) {
		return;
	}
	wglMakeCurrent(c->hdc, c->hglrc);

	glMatrixMode(GL_PROJECTION);
	glLoadIdentity();
	glFrustum(left, right, bottom, top, zNear, zFar);
	glMatrixMode(GL_MODELVIEW);
	glLoadIdentity();
	glEnable(GL_DEPTH_TEST);

	/* Textures decoded from this engine's palette data use alpha 0 as a
	 * color-key "no pixel here" hole (see GLSceneRenderer.decodeTexel).
	 * Alpha-test discards those fragments outright rather than blending -
	 * matches Scene's original "just don't draw this pixel" behavior and,
	 * unlike alpha blending, doesn't need back-to-front sorting to look
	 * right with GL_DEPTH_TEST on. */
	glEnable(GL_ALPHA_TEST);
	glAlphaFunc(GL_GREATER, 0.5f);

	/* Back-face culling. Winding convention chosen by reasoning about the
	 * Y/Z-negation GLSceneRenderer.putVertex() applies (a proper rotation,
	 * preserves winding sense) rather than from a directly-read Scene
	 * winding check - see GLSceneRenderer's class doc comment. If
	 * front-facing geometry disappears instead of back-facing geometry,
	 * this is the one line to flip (GL_CCW <-> GL_CW). */
	glEnable(GL_CULL_FACE);
	glCullFace(GL_BACK);
	glFrontFace(GL_CCW);

	/* GL_LEQUAL rather than the default GL_LESS: picking (see
	 * setPickScissor/readPixelColor/clearPickScissor below) redraws the
	 * exact same triangles a second and third time (once with ID colors,
	 * once to restore the real colors) into the same depth values already
	 * written by the first pass. GL_LESS would fail those redraws outright
	 * since they're never *strictly* closer than what's already there;
	 * GL_LEQUAL lets identical-depth geometry redraw correctly while
	 * behaving exactly like GL_LESS everywhere else. */
	glDepthFunc(GL_LEQUAL);
}

JNIEXPORT void JNICALL Java_orsc_graphics_gl_NativeGL_setPickScissor(JNIEnv *env, jclass clazz,
		jlong ctxHandle, jint x, jint y, jint size, jint screenHeight) {
	HWND hwnd = (HWND) (intptr_t) ctxHandle;
	GLContext *c = findContext(hwnd);
	int glY;
	int half;

	if (c == NULL || size <= 0 || size > PICK_SAVE_BUFFER_MAX) {
		return;
	}
	wglMakeCurrent(c->hdc, c->hglrc);

	/* Flip Y: this engine's mouse coordinates are top-left-origin, GL's
	 * glScissor/glReadPixels are bottom-left-origin. */
	glY = screenHeight - y - 1;
	half = size / 2;
	glEnable(GL_SCISSOR_TEST);
	glScissor(x - half, glY - half, size, size);

	/* Save the real pixels here before the ID-color pass overwrites them,
	 * so restorePickPixels() can put them back afterward with a tiny
	 * glDrawPixels blit instead of redrawing the whole scene's geometry a
	 * second time - the redraw-based restore this replaced cost as much
	 * GPU vertex work as the real render pass itself, every frame. */
	glReadPixels(x - half, glY - half, size, size, GL_RGB, GL_UNSIGNED_BYTE, c->pickSaveBuffer);

	/* Clear just this scissored region to black (matching
	 * GLSceneRenderer's "id 0 = no hit" sentinel) before the ID-color pass
	 * draws into it. Without this, any pixel not covered by an ID triangle
	 * keeps whatever color the real pass already left there, which can
	 * decode to a valid-*looking* but wrong id - not a hypothetical, this
	 * is exactly what caused a real NullPointerException/facePickIndex
	 * mismatch crash the first time this was tested. Depth is left alone:
	 * picking depth-tests against the real pass's already-written values
	 * on purpose, for correct occlusion without recomputing it. */
	glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
	glClear(GL_COLOR_BUFFER_BIT);
}

JNIEXPORT void JNICALL Java_orsc_graphics_gl_NativeGL_restorePickPixels(JNIEnv *env, jclass clazz,
		jlong ctxHandle, jint x, jint y, jint size, jint screenWidth, jint screenHeight) {
	HWND hwnd = (HWND) (intptr_t) ctxHandle;
	GLContext *c = findContext(hwnd);
	int glY;
	int half;

	if (c == NULL || size <= 0 || size > PICK_SAVE_BUFFER_MAX) {
		return;
	}
	wglMakeCurrent(c->hdc, c->hglrc);

	glY = screenHeight - y - 1;
	half = size / 2;

	/* glRasterPos (which glDrawPixels anchors on) is transformed by the
	 * current modelview/projection like any other vertex - under the real
	 * 3D perspective frustum, a raw window-pixel position would almost
	 * certainly fall outside the view volume and make the raster position
	 * invalid, silently dropping the glDrawPixels call. Temporarily switch
	 * to a pixel-space ortho (matching drawUIOverlay's existing push/pop
	 * pattern) so glRasterPos2i can take plain window coordinates. */
	glMatrixMode(GL_PROJECTION);
	glPushMatrix();
	glLoadIdentity();
	glOrtho(0.0, (double) screenWidth, 0.0, (double) screenHeight, -1.0, 1.0);
	glMatrixMode(GL_MODELVIEW);
	glPushMatrix();
	glLoadIdentity();

	glRasterPos2i(x - half, glY - half);
	glDrawPixels(size, size, GL_RGB, GL_UNSIGNED_BYTE, c->pickSaveBuffer);

	glMatrixMode(GL_PROJECTION);
	glPopMatrix();
	glMatrixMode(GL_MODELVIEW);
	glPopMatrix();
}

JNIEXPORT jint JNICALL Java_orsc_graphics_gl_NativeGL_readPixelColor(JNIEnv *env, jclass clazz,
		jlong ctxHandle, jint x, jint y, jint screenHeight) {
	HWND hwnd = (HWND) (intptr_t) ctxHandle;
	GLContext *c = findContext(hwnd);
	int glY;
	unsigned char pixel[3];

	if (c == NULL) {
		return -1;
	}
	wglMakeCurrent(c->hdc, c->hglrc);

	glY = screenHeight - y - 1;
	pixel[0] = pixel[1] = pixel[2] = 0;
	glReadPixels(x, glY, 1, 1, GL_RGB, GL_UNSIGNED_BYTE, pixel);

	return ((jint) pixel[0] << 16) | ((jint) pixel[1] << 8) | (jint) pixel[2];
}

JNIEXPORT void JNICALL Java_orsc_graphics_gl_NativeGL_readDepthRect(JNIEnv *env, jclass clazz,
		jlong ctxHandle, jint x, jint y, jint width, jint height, jint screenHeight, jfloatArray outDepths) {
	HWND hwnd = (HWND) (intptr_t) ctxHandle;
	GLContext *c = findContext(hwnd);
	jfloat *buf;
	int glY;

	if (c == NULL || width <= 0 || height <= 0) {
		return;
	}
	wglMakeCurrent(c->hdc, c->hglrc);

	/* Flip Y: this engine's rects are top-left-origin, GL's glReadPixels is
	 * bottom-left-origin - same flip setPickScissor/readPixelColor already
	 * do, just over a whole rect instead of a point. Unlike those, nothing
	 * here needs a save/restore round trip: this only ever reads the depth
	 * buffer the real geometry pass already wrote, never modifies it -
	 * used by GLSceneRenderer's sprite-vs-wall occlusion check (see
	 * drawSpriteBillboards()), which needs to know how far away the
	 * nearest real geometry is at each pixel a sprite billboard covers.
	 *
	 * glReadPixels itself returns rows bottom-to-top (GL's own convention),
	 * so row 0 of outDepths is this rect's *bottom* image row, not its top
	 * - the Java caller flips when indexing against pixelData, same as it
	 * already has to for x/y here. */
	glY = screenHeight - y - height;

	buf = (*env)->GetFloatArrayElements(env, outDepths, NULL);
	if (buf == NULL) {
		return;
	}
	glReadPixels(x, glY, width, height, GL_DEPTH_COMPONENT, GL_FLOAT, buf);
	(*env)->ReleaseFloatArrayElements(env, outDepths, buf, 0);
}

JNIEXPORT void JNICALL Java_orsc_graphics_gl_NativeGL_readColorRect(JNIEnv *env, jclass clazz,
		jlong ctxHandle, jint x, jint y, jint width, jint height, jint screenHeight, jintArray outColors) {
	HWND hwnd = (HWND) (intptr_t) ctxHandle;
	GLContext *c = findContext(hwnd);
	unsigned char *buf;
	jint *outBuf;
	int glY;
	int i, total;

	if (c == NULL || width <= 0 || height <= 0) {
		return;
	}
	wglMakeCurrent(c->hdc, c->hglrc);

	/* Same Y-flip as readDepthRect above - see its comment. Used to pull a
	 * just-rendered GL frame back into the CPU pixelData buffer for the
	 * login-screen carousel (mudclient.renderLoginScreenViewports()):
	 * GLSceneRenderer.endScene() submits geometry straight to this native
	 * GL context and never touches pixelData at all (unlike the software
	 * Scene renderer, which draws directly into it), so without this,
	 * whatever routine snapshots the "rendered" frame into a sprite would
	 * just be capturing stale leftover pixelData content instead of the
	 * actual 3D scene. Not performance-sensitive (called a handful of
	 * times at login, not per-frame), so a straightforward malloc'd scratch
	 * buffer is fine here rather than a fixed-size static one. */
	glY = screenHeight - y - height;

	total = width * height;
	buf = (unsigned char *) malloc((size_t) total * 3);
	if (buf == NULL) {
		return;
	}
	glReadPixels(x, glY, width, height, GL_RGB, GL_UNSIGNED_BYTE, buf);

	outBuf = (*env)->GetIntArrayElements(env, outColors, NULL);
	if (outBuf != NULL) {
		for (i = 0; i < total; ++i) {
			outBuf[i] = ((jint) buf[i * 3] << 16) | ((jint) buf[i * 3 + 1] << 8) | (jint) buf[i * 3 + 2];
		}
		(*env)->ReleaseIntArrayElements(env, outColors, outBuf, 0);
	}
	free(buf);
}

JNIEXPORT void JNICALL Java_orsc_graphics_gl_NativeGL_clearPickScissor(JNIEnv *env, jclass clazz, jlong ctxHandle) {
	HWND hwnd = (HWND) (intptr_t) ctxHandle;
	GLContext *c = findContext(hwnd);

	if (c == NULL) {
		return;
	}
	wglMakeCurrent(c->hdc, c->hglrc);

	glDisable(GL_SCISSOR_TEST);
}

JNIEXPORT void JNICALL Java_orsc_graphics_gl_NativeGL_drawTriangles(JNIEnv *env, jclass clazz,
		jlong ctxHandle, jint texId, jfloatArray vertexData, jint vertexCount) {
	HWND hwnd = (HWND) (intptr_t) ctxHandle;
	GLContext *c = findContext(hwnd);
	jfloat *data;
	GLsizei stride;

	if (c == NULL || vertexCount <= 0) {
		return;
	}

	data = (*env)->GetFloatArrayElements(env, vertexData, NULL);
	if (data == NULL) {
		return;
	}

	if (texId != 0) {
		glEnable(GL_TEXTURE_2D);
		glBindTexture(GL_TEXTURE_2D, (GLuint) texId);
	} else {
		glDisable(GL_TEXTURE_2D);
	}

	/* Vertex arrays, not immediate mode (glBegin/glVertex3f/...): a
	 * profiling pass showed the old per-vertex glColor4f/glTexCoord2f/
	 * glVertex3f loop costing 300-400ms/frame for ~90-100k vertices under
	 * Wine's OpenGL-on-Metal translation - each call crosses that
	 * translation layer separately, so a scene's worth of vertices meant
	 * hundreds of thousands of translated calls every frame. Vertex arrays
	 * collapse that to a handful of pointer setup calls plus one
	 * glDrawArrays, and are core OpenGL 1.1 - not a later addition, so this
	 * stays exactly as Voodoo2/MiniGL-compatible as immediate mode was.
	 *
	 * Stride is 8 floats/vertex: x,y,z, r,g,b, u,v (see
	 * GLSceneRenderer.putVertex()). glColorPointer with size 3 (not 4)
	 * implicitly sets alpha to 1.0 per the GL spec, the same guarantee the
	 * old code got from calling glColor4f explicitly - not lost here. */
	stride = 8 * sizeof(GLfloat);

	glEnableClientState(GL_VERTEX_ARRAY);
	glVertexPointer(3, GL_FLOAT, stride, data);

	glEnableClientState(GL_COLOR_ARRAY);
	glColorPointer(3, GL_FLOAT, stride, data + 3);

	if (texId != 0) {
		glEnableClientState(GL_TEXTURE_COORD_ARRAY);
		glTexCoordPointer(2, GL_FLOAT, stride, data + 6);
	}

	glDrawArrays(GL_TRIANGLES, 0, vertexCount);

	glDisableClientState(GL_VERTEX_ARRAY);
	glDisableClientState(GL_COLOR_ARRAY);
	if (texId != 0) {
		glDisableClientState(GL_TEXTURE_COORD_ARRAY);
	}

	(*env)->ReleaseFloatArrayElements(env, vertexData, data, JNI_ABORT);
}

JNIEXPORT jint JNICALL Java_orsc_graphics_gl_NativeGL_uploadTexture(JNIEnv *env, jclass clazz,
		jlong ctxHandle, jint width, jint height, jbyteArray rgba) {
	HWND hwnd = (HWND) (intptr_t) ctxHandle;
	GLContext *c = findContext(hwnd);
	jbyte *data;
	GLuint texId;

	if (c == NULL) {
		return 0;
	}
	wglMakeCurrent(c->hdc, c->hglrc);

	data = (*env)->GetByteArrayElements(env, rgba, NULL);
	if (data == NULL) {
		return 0;
	}

	glGenTextures(1, &texId);
	glBindTexture(GL_TEXTURE_2D, texId);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
	glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, data);

	(*env)->ReleaseByteArrayElements(env, rgba, data, JNI_ABORT);

	return (jint) texId;
}

JNIEXPORT void JNICALL Java_orsc_graphics_gl_NativeGL_deleteTexture(JNIEnv *env, jclass clazz,
		jlong ctxHandle, jint texId) {
	HWND hwnd = (HWND) (intptr_t) ctxHandle;
	GLContext *c = findContext(hwnd);
	GLuint t;

	if (c == NULL) {
		return;
	}
	wglMakeCurrent(c->hdc, c->hglrc);

	t = (GLuint) texId;
	glDeleteTextures(1, &t);
}

JNIEXPORT void JNICALL Java_orsc_graphics_gl_NativeGL_updateTexture(JNIEnv *env, jclass clazz,
		jlong ctxHandle, jint texId, jint width, jint height, jbyteArray rgba) {
	HWND hwnd = (HWND) (intptr_t) ctxHandle;
	GLContext *c = findContext(hwnd);
	jbyte *data;

	if (c == NULL) {
		return;
	}
	wglMakeCurrent(c->hdc, c->hglrc);

	data = (*env)->GetByteArrayElements(env, rgba, NULL);
	if (data == NULL) {
		return;
	}

	glBindTexture(GL_TEXTURE_2D, (GLuint) texId);
	glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, data);

	(*env)->ReleaseByteArrayElements(env, rgba, data, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_orsc_graphics_gl_NativeGL_drawUIOverlay(JNIEnv *env, jclass clazz,
		jlong ctxHandle, jint texId) {
	HWND hwnd = (HWND) (intptr_t) ctxHandle;
	GLContext *c = findContext(hwnd);

	if (c == NULL || texId == 0) {
		return;
	}
	wglMakeCurrent(c->hdc, c->hglrc);

	/* Temporarily swap in an identity/ortho projection for one full-screen
	 * quad, then restore the perspective projection push/pop leaves
	 * intact for the next frame's 3D geometry. */
	glMatrixMode(GL_PROJECTION);
	glPushMatrix();
	glLoadIdentity();
	glOrtho(-1.0, 1.0, -1.0, 1.0, -1.0, 1.0);
	glMatrixMode(GL_MODELVIEW);
	glPushMatrix();
	glLoadIdentity();

	glDisable(GL_DEPTH_TEST);
	glDisable(GL_CULL_FACE);
	glDisable(GL_ALPHA_TEST);
	glEnable(GL_TEXTURE_2D);
	glBindTexture(GL_TEXTURE_2D, (GLuint) texId);

	/* Real alpha blending rather than alpha-test here: this is a single
	 * full-screen quad drawn last, so there's no multi-triangle sort-order
	 * problem, and blending looks better than a hard cutout for UI. */
	glEnable(GL_BLEND);
	glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

	glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
	glBegin(GL_QUADS);
		glTexCoord2f(0.0f, 1.0f); glVertex2f(-1.0f, -1.0f);
		glTexCoord2f(1.0f, 1.0f); glVertex2f(1.0f, -1.0f);
		glTexCoord2f(1.0f, 0.0f); glVertex2f(1.0f, 1.0f);
		glTexCoord2f(0.0f, 0.0f); glVertex2f(-1.0f, 1.0f);
	glEnd();

	glDisable(GL_BLEND);
	glEnable(GL_ALPHA_TEST);
	glEnable(GL_DEPTH_TEST);
	glEnable(GL_CULL_FACE);

	glMatrixMode(GL_PROJECTION);
	glPopMatrix();
	glMatrixMode(GL_MODELVIEW);
	glPopMatrix();
}

JNIEXPORT void JNICALL Java_orsc_graphics_gl_NativeGL_destroyContext(JNIEnv *env, jclass clazz, jlong ctxHandle) {
	HWND hwnd = (HWND) (intptr_t) ctxHandle;
	GLContext *c = findContext(hwnd);
	int i;
	int foundIndex = -1;

	if (c == NULL) {
		return;
	}

	wglMakeCurrent(NULL, NULL);
	wglDeleteContext(c->hglrc);
	ReleaseDC(hwnd, c->hdc);
	DestroyWindow(hwnd);

	for (i = 0; i < g_contextCount; ++i) {
		if (&g_contexts[i] == c) {
			foundIndex = i;
			break;
		}
	}
	if (foundIndex >= 0) {
		for (i = foundIndex; i < g_contextCount - 1; ++i) {
			g_contexts[i] = g_contexts[i + 1];
		}
		--g_contextCount;
	}
}
