/*
 * Phase 0 spike native implementation: NativeGL.java's Win32 + WGL backend.
 *
 * Scope is deliberately narrow: create our own top-level window (no AWT/JAWT
 * peer interop), create a WGL context on it, draw one immediate-mode
 * triangle per frame, and swap buffers. Only OpenGL 1.1 fixed-function
 * calls are used anywhere in here, matching what a Voodoo2 MiniGL ICD can
 * actually support.
 *
 * See ../PLAN.md "Gotchas anticipated going in" for the two pitfalls this
 * file works around: stdcall name decoration on the exported JNI symbols,
 * and WGL contexts / Win32 message queues being thread-affine.
 */

#include <jni.h>
#include <windows.h>
#include <GL/gl.h>

#define MAX_WINDOWS 4

typedef struct {
	HWND hwnd;
	HDC hdc;
	HGLRC hglrc;
} GLWindow;

static GLWindow g_windows[MAX_WINDOWS];
static int g_windowCount = 0;
static int g_classRegistered = 0;
static const char *CLASS_NAME = "NativeGLSpikeWindowClass";

static GLWindow *findWindow(HWND hwnd) {
	int i;
	for (i = 0; i < g_windowCount; ++i) {
		if (g_windows[i].hwnd == hwnd) {
			return &g_windows[i];
		}
	}
	return NULL;
}

static LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
	switch (msg) {
		case WM_CLOSE:
			DestroyWindow(hwnd);
			return 0;
		case WM_DESTROY:
			PostQuitMessage(0);
			return 0;
		default:
			return DefWindowProc(hwnd, msg, wParam, lParam);
	}
}

JNIEXPORT jlong JNICALL Java_NativeGL_createWindow(JNIEnv *env, jclass clazz,
		jint width, jint height, jstring title) {
	const char *titleChars;
	HWND hwnd;
	HDC hdc;
	HGLRC hglrc;
	PIXELFORMATDESCRIPTOR pfd;
	int pixelFormat;

	if (g_windowCount >= MAX_WINDOWS) {
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

	titleChars = (*env)->GetStringUTFChars(env, title, NULL);
	hwnd = CreateWindowEx(0, CLASS_NAME, titleChars, WS_OVERLAPPEDWINDOW,
			CW_USEDEFAULT, CW_USEDEFAULT, width, height,
			NULL, NULL, GetModuleHandle(NULL), NULL);
	(*env)->ReleaseStringUTFChars(env, title, titleChars);

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

	glViewport(0, 0, width, height);
	glMatrixMode(GL_PROJECTION);
	glLoadIdentity();
	glOrtho(-1.0, 1.0, -1.0, 1.0, -1.0, 1.0);
	glMatrixMode(GL_MODELVIEW);
	glLoadIdentity();
	glDisable(GL_DEPTH_TEST);

	g_windows[g_windowCount].hwnd = hwnd;
	g_windows[g_windowCount].hdc = hdc;
	g_windows[g_windowCount].hglrc = hglrc;
	++g_windowCount;

	ShowWindow(hwnd, SW_SHOW);
	UpdateWindow(hwnd);

	return (jlong) (intptr_t) hwnd;
}

JNIEXPORT jboolean JNICALL Java_NativeGL_pumpMessages(JNIEnv *env, jclass clazz, jlong hwndHandle) {
	MSG msg;
	HWND hwnd = (HWND) (intptr_t) hwndHandle;

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

JNIEXPORT void JNICALL Java_NativeGL_renderFrame(JNIEnv *env, jclass clazz, jlong hwndHandle) {
	HWND hwnd = (HWND) (intptr_t) hwndHandle;
	GLWindow *w = findWindow(hwnd);
	if (w == NULL) {
		return;
	}

	/* Re-assert current context every frame rather than assuming it's
	 * still current - same lesson gl2d learned with GLX contexts. */
	wglMakeCurrent(w->hdc, w->hglrc);

	glClearColor(0.10f, 0.10f, 0.15f, 1.0f);
	glClear(GL_COLOR_BUFFER_BIT);

	glBegin(GL_TRIANGLES);
		glColor3f(1.0f, 0.0f, 0.0f);
		glVertex3f(0.0f, 0.6f, 0.0f);
		glColor3f(0.0f, 1.0f, 0.0f);
		glVertex3f(-0.6f, -0.5f, 0.0f);
		glColor3f(0.0f, 0.4f, 1.0f);
		glVertex3f(0.6f, -0.5f, 0.0f);
	glEnd();

	glFlush();
	SwapBuffers(w->hdc);
}

JNIEXPORT void JNICALL Java_NativeGL_destroyWindow(JNIEnv *env, jclass clazz, jlong hwndHandle) {
	HWND hwnd = (HWND) (intptr_t) hwndHandle;
	GLWindow *w = findWindow(hwnd);
	int i;
	int foundIndex = -1;

	if (w == NULL) {
		return;
	}

	wglMakeCurrent(NULL, NULL);
	wglDeleteContext(w->hglrc);
	ReleaseDC(hwnd, w->hdc);
	DestroyWindow(hwnd);

	for (i = 0; i < g_windowCount; ++i) {
		if (&g_windows[i] == w) {
			foundIndex = i;
			break;
		}
	}
	if (foundIndex >= 0) {
		for (i = foundIndex; i < g_windowCount - 1; ++i) {
			g_windows[i] = g_windows[i + 1];
		}
		--g_windowCount;
	}
}
