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
#include <stddef.h> /* size_t only - a compiler-provided freestanding header,
                     * no libc function declarations in it */

/* No <stdlib.h>/libc at all, deliberately - see zeroBytes()'s comment
 * below and build-nativegl.sh's -nostdlib flags. */

/* ---- Dynamic OpenGL loading -------------------------------------------
 *
 * Every gl*()/wgl*() function used in this file used to be a normal static
 * import (opengl32.lib, via -lopengl32 in build-nativegl.sh) - resolved
 * by the OS loader before any of our own code runs at all, using
 * whatever the default DLL search order picks. That's not good enough
 * here: a locally-supplied OpenGL32.dll (e.g. a WickedGL or MesaFx
 * install - known to have meaningfully better Voodoo2-era 3D
 * compatibility than stock 3dfx MiniGL in some cases, particularly in
 * windowed mode, see PLAN.md) needs to be tried *first*, deterministically,
 * regardless of Windows version. A bare LoadLibraryA("opengl32.dll") call
 * can't guarantee that either: SafeDllSearchMode (default since XP SP1)
 * checks System32 *before* the current directory - the opposite of what
 * we want.
 *
 * So every gl*()/wgl*() symbol is now resolved manually by
 * ensureOpenGLLoaded(), which tries an exact relative path to
 * ".\OpenGL32.dll" first (the current working directory - Client_Base/,
 * where the jar/NativeGL.dll/a user-supplied OpenGL32.dll all sit side
 * by side) - an explicit path bypasses the OS's own search-order logic
 * entirely for this attempt, so it can't be pre-empted by a System32 copy.
 * Only falls back to the bare "opengl32.dll" name (normal system
 * search/whatever ICD is otherwise registered) if that exact file isn't
 * present. -lopengl32 was removed from build-nativegl.sh's link line -
 * nothing in this file references the statically-imported symbols
 * anymore, every call site below goes through the p-prefixed function
 * pointers via the #define block at the end of this section. */

typedef void (WINAPI *PFNGLALPHAFUNC)(GLenum func, GLclampf ref);
typedef void (WINAPI *PFNGLBEGIN)(GLenum mode);
typedef void (WINAPI *PFNGLBINDTEXTURE)(GLenum target, GLuint texture);
typedef void (WINAPI *PFNGLBLENDFUNC)(GLenum sfactor, GLenum dfactor);
typedef void (WINAPI *PFNGLCLEAR)(GLbitfield mask);
typedef void (WINAPI *PFNGLCLEARCOLOR)(GLclampf red, GLclampf green, GLclampf blue, GLclampf alpha);
typedef void (WINAPI *PFNGLCOLOR4F)(GLfloat red, GLfloat green, GLfloat blue, GLfloat alpha);
typedef void (WINAPI *PFNGLCOLORPOINTER)(GLint size, GLenum type, GLsizei stride, const GLvoid *pointer);
typedef void (WINAPI *PFNGLCULLFACE)(GLenum mode);
typedef void (WINAPI *PFNGLDELETETEXTURES)(GLsizei n, const GLuint *textures);
typedef void (WINAPI *PFNGLDEPTHFUNC)(GLenum func);
typedef void (WINAPI *PFNGLDISABLE)(GLenum cap);
typedef void (WINAPI *PFNGLDISABLECLIENTSTATE)(GLenum array);
typedef void (WINAPI *PFNGLDRAWARRAYS)(GLenum mode, GLint first, GLsizei count);
typedef void (WINAPI *PFNGLDRAWPIXELS)(GLsizei width, GLsizei height, GLenum format, GLenum type, const GLvoid *pixels);
typedef void (WINAPI *PFNGLENABLE)(GLenum cap);
typedef void (WINAPI *PFNGLENABLECLIENTSTATE)(GLenum array);
typedef void (WINAPI *PFNGLEND)(void);
typedef void (WINAPI *PFNGLFLUSH)(void);
typedef void (WINAPI *PFNGLFRONTFACE)(GLenum mode);
typedef void (WINAPI *PFNGLFRUSTUM)(GLdouble left, GLdouble right, GLdouble bottom, GLdouble top, GLdouble zNear, GLdouble zFar);
typedef void (WINAPI *PFNGLGENTEXTURES)(GLsizei n, GLuint *textures);
typedef const GLubyte * (WINAPI *PFNGLGETSTRING)(GLenum name);
typedef void (WINAPI *PFNGLLOADIDENTITY)(void);
typedef void (WINAPI *PFNGLMATRIXMODE)(GLenum mode);
typedef void (WINAPI *PFNGLORTHO)(GLdouble left, GLdouble right, GLdouble bottom, GLdouble top, GLdouble zNear, GLdouble zFar);
typedef void (WINAPI *PFNGLPOPMATRIX)(void);
typedef void (WINAPI *PFNGLPUSHMATRIX)(void);
typedef void (WINAPI *PFNGLRASTERPOS2I)(GLint x, GLint y);
typedef void (WINAPI *PFNGLREADPIXELS)(GLint x, GLint y, GLsizei width, GLsizei height, GLenum format, GLenum type, GLvoid *pixels);
typedef void (WINAPI *PFNGLSCISSOR)(GLint x, GLint y, GLsizei width, GLsizei height);
typedef void (WINAPI *PFNGLTEXCOORD2F)(GLfloat s, GLfloat t);
typedef void (WINAPI *PFNGLTEXCOORDPOINTER)(GLint size, GLenum type, GLsizei stride, const GLvoid *pointer);
typedef void (WINAPI *PFNGLTEXIMAGE2D)(GLenum target, GLint level, GLint internalformat, GLsizei width, GLsizei height, GLint border, GLenum format, GLenum type, const GLvoid *pixels);
typedef void (WINAPI *PFNGLTEXPARAMETERI)(GLenum target, GLenum pname, GLint param);
typedef void (WINAPI *PFNGLTEXSUBIMAGE2D)(GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLenum type, const GLvoid *pixels);
typedef void (WINAPI *PFNGLVERTEX2F)(GLfloat x, GLfloat y);
typedef void (WINAPI *PFNGLVERTEXPOINTER)(GLint size, GLenum type, GLsizei stride, const GLvoid *pointer);
typedef void (WINAPI *PFNGLVIEWPORT)(GLint x, GLint y, GLsizei width, GLsizei height);
typedef HGLRC (WINAPI *PFNWGLCREATECONTEXT)(HDC hdc);
typedef BOOL (WINAPI *PFNWGLDELETECONTEXT)(HGLRC hglrc);
typedef BOOL (WINAPI *PFNWGLMAKECURRENT)(HDC hdc, HGLRC hglrc);

static PFNGLALPHAFUNC pglAlphaFunc;
static PFNGLBEGIN pglBegin;
static PFNGLBINDTEXTURE pglBindTexture;
static PFNGLBLENDFUNC pglBlendFunc;
static PFNGLCLEAR pglClear;
static PFNGLCLEARCOLOR pglClearColor;
static PFNGLCOLOR4F pglColor4f;
static PFNGLCOLORPOINTER pglColorPointer;
static PFNGLCULLFACE pglCullFace;
static PFNGLDELETETEXTURES pglDeleteTextures;
static PFNGLDEPTHFUNC pglDepthFunc;
static PFNGLDISABLE pglDisable;
static PFNGLDISABLECLIENTSTATE pglDisableClientState;
static PFNGLDRAWARRAYS pglDrawArrays;
static PFNGLDRAWPIXELS pglDrawPixels;
static PFNGLENABLE pglEnable;
static PFNGLENABLECLIENTSTATE pglEnableClientState;
static PFNGLEND pglEnd;
static PFNGLFLUSH pglFlush;
static PFNGLFRONTFACE pglFrontFace;
static PFNGLFRUSTUM pglFrustum;
static PFNGLGENTEXTURES pglGenTextures;
static PFNGLGETSTRING pglGetString;
static PFNGLLOADIDENTITY pglLoadIdentity;
static PFNGLMATRIXMODE pglMatrixMode;
static PFNGLORTHO pglOrtho;
static PFNGLPOPMATRIX pglPopMatrix;
static PFNGLPUSHMATRIX pglPushMatrix;
static PFNGLRASTERPOS2I pglRasterPos2i;
static PFNGLREADPIXELS pglReadPixels;
static PFNGLSCISSOR pglScissor;
static PFNGLTEXCOORD2F pglTexCoord2f;
static PFNGLTEXCOORDPOINTER pglTexCoordPointer;
static PFNGLTEXIMAGE2D pglTexImage2D;
static PFNGLTEXPARAMETERI pglTexParameteri;
static PFNGLTEXSUBIMAGE2D pglTexSubImage2D;
static PFNGLVERTEX2F pglVertex2f;
static PFNGLVERTEXPOINTER pglVertexPointer;
static PFNGLVIEWPORT pglViewport;
static PFNWGLCREATECONTEXT pwglCreateContext;
static PFNWGLDELETECONTEXT pwglDeleteContext;
static PFNWGLMAKECURRENT pwglMakeCurrent;

static int g_openGLLoadAttempted = 0;

/* Called at the top of createContext() (the one function guaranteed to
 * run before anything else in this file touches GL) - see this section's
 * header comment. Idempotent: only actually loads once per process, safe
 * to call on every createContext() invocation. Leaves every pointer NULL
 * on total failure (no local or system OpenGL32.dll at all) - existing
 * NULL-return-value checks throughout this file already handle a failed
 * context creation the same way they always did. */
static void ensureOpenGLLoaded(void) {
	HMODULE mod;

	if (g_openGLLoadAttempted) {
		return;
	}
	g_openGLLoadAttempted = 1;

	mod = LoadLibraryA(".\\OpenGL32.dll");
	if (mod == NULL) {
		mod = LoadLibraryA("opengl32.dll");
	}
	if (mod == NULL) {
		return;
	}

	pglAlphaFunc = (PFNGLALPHAFUNC) GetProcAddress(mod, "glAlphaFunc");
	pglBegin = (PFNGLBEGIN) GetProcAddress(mod, "glBegin");
	pglBindTexture = (PFNGLBINDTEXTURE) GetProcAddress(mod, "glBindTexture");
	pglBlendFunc = (PFNGLBLENDFUNC) GetProcAddress(mod, "glBlendFunc");
	pglClear = (PFNGLCLEAR) GetProcAddress(mod, "glClear");
	pglClearColor = (PFNGLCLEARCOLOR) GetProcAddress(mod, "glClearColor");
	pglColor4f = (PFNGLCOLOR4F) GetProcAddress(mod, "glColor4f");
	pglColorPointer = (PFNGLCOLORPOINTER) GetProcAddress(mod, "glColorPointer");
	pglCullFace = (PFNGLCULLFACE) GetProcAddress(mod, "glCullFace");
	pglDeleteTextures = (PFNGLDELETETEXTURES) GetProcAddress(mod, "glDeleteTextures");
	pglDepthFunc = (PFNGLDEPTHFUNC) GetProcAddress(mod, "glDepthFunc");
	pglDisable = (PFNGLDISABLE) GetProcAddress(mod, "glDisable");
	pglDisableClientState = (PFNGLDISABLECLIENTSTATE) GetProcAddress(mod, "glDisableClientState");
	pglDrawArrays = (PFNGLDRAWARRAYS) GetProcAddress(mod, "glDrawArrays");
	pglDrawPixels = (PFNGLDRAWPIXELS) GetProcAddress(mod, "glDrawPixels");
	pglEnable = (PFNGLENABLE) GetProcAddress(mod, "glEnable");
	pglEnableClientState = (PFNGLENABLECLIENTSTATE) GetProcAddress(mod, "glEnableClientState");
	pglEnd = (PFNGLEND) GetProcAddress(mod, "glEnd");
	pglFlush = (PFNGLFLUSH) GetProcAddress(mod, "glFlush");
	pglFrontFace = (PFNGLFRONTFACE) GetProcAddress(mod, "glFrontFace");
	pglFrustum = (PFNGLFRUSTUM) GetProcAddress(mod, "glFrustum");
	pglGenTextures = (PFNGLGENTEXTURES) GetProcAddress(mod, "glGenTextures");
	pglGetString = (PFNGLGETSTRING) GetProcAddress(mod, "glGetString");
	pglLoadIdentity = (PFNGLLOADIDENTITY) GetProcAddress(mod, "glLoadIdentity");
	pglMatrixMode = (PFNGLMATRIXMODE) GetProcAddress(mod, "glMatrixMode");
	pglOrtho = (PFNGLORTHO) GetProcAddress(mod, "glOrtho");
	pglPopMatrix = (PFNGLPOPMATRIX) GetProcAddress(mod, "glPopMatrix");
	pglPushMatrix = (PFNGLPUSHMATRIX) GetProcAddress(mod, "glPushMatrix");
	pglRasterPos2i = (PFNGLRASTERPOS2I) GetProcAddress(mod, "glRasterPos2i");
	pglReadPixels = (PFNGLREADPIXELS) GetProcAddress(mod, "glReadPixels");
	pglScissor = (PFNGLSCISSOR) GetProcAddress(mod, "glScissor");
	pglTexCoord2f = (PFNGLTEXCOORD2F) GetProcAddress(mod, "glTexCoord2f");
	pglTexCoordPointer = (PFNGLTEXCOORDPOINTER) GetProcAddress(mod, "glTexCoordPointer");
	pglTexImage2D = (PFNGLTEXIMAGE2D) GetProcAddress(mod, "glTexImage2D");
	pglTexParameteri = (PFNGLTEXPARAMETERI) GetProcAddress(mod, "glTexParameteri");
	pglTexSubImage2D = (PFNGLTEXSUBIMAGE2D) GetProcAddress(mod, "glTexSubImage2D");
	pglVertex2f = (PFNGLVERTEX2F) GetProcAddress(mod, "glVertex2f");
	pglVertexPointer = (PFNGLVERTEXPOINTER) GetProcAddress(mod, "glVertexPointer");
	pglViewport = (PFNGLVIEWPORT) GetProcAddress(mod, "glViewport");
	pwglCreateContext = (PFNWGLCREATECONTEXT) GetProcAddress(mod, "wglCreateContext");
	pwglDeleteContext = (PFNWGLDELETECONTEXT) GetProcAddress(mod, "wglDeleteContext");
	pwglMakeCurrent = (PFNWGLMAKECURRENT) GetProcAddress(mod, "wglMakeCurrent");
}

/* Every gl*()/wgl*() call site elsewhere in this file transparently becomes a
 * call through the function pointers above - see this section's header
 * comment. */
#define glAlphaFunc pglAlphaFunc
#define glBegin pglBegin
#define glBindTexture pglBindTexture
#define glBlendFunc pglBlendFunc
#define glClear pglClear
#define glClearColor pglClearColor
#define glColor4f pglColor4f
#define glColorPointer pglColorPointer
#define glCullFace pglCullFace
#define glDeleteTextures pglDeleteTextures
#define glDepthFunc pglDepthFunc
#define glDisable pglDisable
#define glDisableClientState pglDisableClientState
#define glDrawArrays pglDrawArrays
#define glDrawPixels pglDrawPixels
#define glEnable pglEnable
#define glEnableClientState pglEnableClientState
#define glEnd pglEnd
#define glFlush pglFlush
#define glFrontFace pglFrontFace
#define glFrustum pglFrustum
#define glGenTextures pglGenTextures
#define glGetString pglGetString
#define glLoadIdentity pglLoadIdentity
#define glMatrixMode pglMatrixMode
#define glOrtho pglOrtho
#define glPopMatrix pglPopMatrix
#define glPushMatrix pglPushMatrix
#define glRasterPos2i pglRasterPos2i
#define glReadPixels pglReadPixels
#define glScissor pglScissor
#define glTexCoord2f pglTexCoord2f
#define glTexCoordPointer pglTexCoordPointer
#define glTexImage2D pglTexImage2D
#define glTexParameteri pglTexParameteri
#define glTexSubImage2D pglTexSubImage2D
#define glVertex2f pglVertex2f
#define glVertexPointer pglVertexPointer
#define glViewport pglViewport
#define wglCreateContext pwglCreateContext
#define wglDeleteContext pwglDeleteContext
#define wglMakeCurrent pwglMakeCurrent

#define MAX_CONTEXTS 4
#define MAX_INPUT_EVENTS 256

/* Input event types, mirrored in NativeGL.java's INPUT_* constants. */
#define INPUT_MOUSE_MOVE  1
#define INPUT_MOUSE_DOWN  2
#define INPUT_MOUSE_UP    3
#define INPUT_KEY_DOWN    4
#define INPUT_KEY_UP      5
#define INPUT_CHAR        6
#define INPUT_MOUSE_WHEEL 7

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

/* See createContext()'s doc comment for the fullscreenWidth/Height
 * parameters it takes - the actual resolution choice (native width/height
 * with no letterboxing, vs. a larger classic mode like 640x480 with the
 * smaller content centered within it) is decided on the Java side
 * (GLSceneRenderer/-Dorsc.gl.fullscreen.width/height), not hardcoded here. */

typedef struct {
	HWND hwnd;
	HDC hdc;
	HGLRC hglrc;
	InputEvent inputQueue[MAX_INPUT_EVENTS];
	int inputHead;
	int inputTail;
	unsigned char pickSaveBuffer[PICK_SAVE_BUFFER_MAX * PICK_SAVE_BUFFER_MAX * 3];
	/* Whether createContext() did a CDS_FULLSCREEN display-mode switch for
	 * this context, so destroyContext() knows whether it needs to restore
	 * the original mode - see createContext()'s doc comment. */
	int fullscreen;
	/* How far the letterboxed/pillarboxed content area (see
	 * createContext()'s doc comment) is inset from the top-left of the
	 * actual display mode - 0 for a normal window, or for fullscreen
	 * "mode 1" (native resolution, no letterboxing at all). Every function
	 * that takes a game-space pixel coordinate (mouse input, picking,
	 * depth/color readback) adds this before treating it as a real
	 * window/GL coordinate. */
	int fullscreenOffsetX;
	int fullscreenOffsetY;
} GLContext;

static GLContext g_contexts[MAX_CONTEXTS];
static int g_contextCount = 0;
static int g_classRegistered = 0;
static const char *CLASS_NAME = "OrscNativeGLWindowClass";

/* Hand-rolled in place of memset/ZeroMemory: this DLL is built with no CRT
 * at all (see build-nativegl.sh) so it only ever imports from kernel32/
 * user32/gdi32/opengl32 - all genuinely present on Windows 98 - rather than
 * modern mingw-w64's default CRT import library, which routes even
 * "msvcrt"-mode builds through api-ms-win-crt-*.dll API-set forwarders that
 * only exist on Windows 10+. ZeroMemory()/RtlZeroMemory() are themselves
 * just macros expanding to memset() in this toolchain's headers (confirmed
 * via winnt.h/winternl.h), so calling them still pulls in libc - this small
 * manual loop sidesteps that macro entirely. -fno-builtin/-ffreestanding
 * (also in build-nativegl.sh) keep GCC from optimizing it back into a
 * memset call under -O2. */
static void zeroBytes(void *dst, size_t n) {
	unsigned char *p = (unsigned char *) dst;
	size_t i;
	for (i = 0; i < n; ++i) {
		p[i] = 0;
	}
}

/* Appends one line to nativegl-fullscreen.log (next to the jar, same
 * convention as the .bat launchers' own *-out.log/*-err.log redirection)
 * recording exactly what fullscreen mode was requested vs. what
 * EnumDisplaySettings reports actually got applied. Added investigating a
 * real Voodoo 5 showing CRT sync-loss artifacts (rolling/torn image) in
 * fullscreen - the requested DEVMODE alone was not enough to diagnose
 * that, since the driver is free to coerce an unsupported field (refresh
 * rate in particular) to something else silently. wsprintfA/CreateFileA/
 * WriteFile are plain user32/kernel32 exports, not CRT - consistent with
 * this file having no libc at all (see the file-level comment). */
static void logFullscreenDiag(int reqW, int reqH, int reqBpp, int reqHz,
		int gotW, int gotH, int gotBpp, int gotHz, int success) {
	HANDLE hFile;
	char buf[256];
	int len;
	DWORD written;

	hFile = CreateFileA("nativegl-fullscreen.log", FILE_APPEND_DATA,
			FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
	if (hFile == INVALID_HANDLE_VALUE) {
		return;
	}
	len = wsprintfA(buf,
			"fullscreen mode switch: requested %dx%d @ %dbpp %dHz (0=driver default) -> %s, applied %dx%d @ %dbpp %dHz\r\n",
			reqW, reqH, reqBpp, reqHz, success ? "OK" : "FAILED (fell back to windowed)", gotW, gotH, gotBpp, gotHz);
	WriteFile(hFile, buf, (DWORD) len, &written, NULL);
	CloseHandle(hFile);
}

/* Logs exactly which OpenGL implementation ended up current on this
 * context - GL_VENDOR/GL_RENDERER/GL_VERSION, straight from the driver
 * itself, no guessing. Added after a real Pentium MMX + Voodoo2 test
 * showed catastrophic per-frame costs (rotate/draw/ui.convert all in the
 * hundreds of ms) alongside fullscreen silently falling back to windowed
 * - consistent with Microsoft's built-in software OpenGL implementation
 * being what's actually current, not the Voodoo2's real MiniGL ICD (which
 * historically only ever activated in genuine fullscreen-exclusive mode -
 * see PLAN.md's "Hard constraint driving the design"). Real 3dfx MiniGL
 * reports itself distinctly (typically "3Dfx Interactive Inc." /
 * a Voodoo-referencing renderer string); Microsoft's software fallback
 * reports its own generic strings instead - this makes the difference
 * unambiguous from the log alone, no need to dig through Windows 95's
 * registry/control panel to check ICD registration. Logged unconditionally
 * on every successful context creation (fullscreen or windowed), not just
 * the fullscreen path - also serves as a check that file-writing to
 * wherever the jar is running from actually works at all, since
 * logFullscreenDiag() above was confirmed NOT to produce a file on that
 * same test run. */
static void logGlInfo(int fullscreenRequested, int fullscreenActive) {
	HANDLE hFile;
	char buf[512];
	int len;
	DWORD written;
	const char *vendor;
	const char *renderer;
	const char *version;

	vendor = (const char *) glGetString(GL_VENDOR);
	renderer = (const char *) glGetString(GL_RENDERER);
	version = (const char *) glGetString(GL_VERSION);
	if (vendor == NULL) vendor = "(null)";
	if (renderer == NULL) renderer = "(null)";
	if (version == NULL) version = "(null)";

	hFile = CreateFileA("nativegl-glinfo.log", FILE_APPEND_DATA,
			FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
	if (hFile == INVALID_HANDLE_VALUE) {
		return;
	}
	len = wsprintfA(buf,
			"context created: fullscreenRequested=%d fullscreenActive=%d GL_VENDOR=\"%s\" GL_RENDERER=\"%s\" GL_VERSION=\"%s\"\r\n",
			fullscreenRequested, fullscreenActive, vendor, renderer, version);
	WriteFile(hFile, buf, (DWORD) len, &written, NULL);
	CloseHandle(hFile);
}

/* memcpy/memmove/memset - not called directly anywhere in this file (see
 * zeroBytes() above for our own zeroing needs), but GCC's own codegen for
 * large aggregate copies (e.g. destroyContext()'s `g_contexts[i] =
 * g_contexts[i + 1];`, which copies a whole GLContext struct - kilobytes,
 * thanks to pickSaveBuffer/inputQueue) unconditionally lowers to a call to
 * "memcpy" regardless of -fno-builtin/-ffreestanding (those flags only stop
 * GCC from *recognizing hand-written loops* as library-call patterns, not
 * its own aggregate-copy codegen strategy). Defining real functions with
 * these exact names in this translation unit satisfies that codegen with a
 * local call instead of an external CRT import - confirmed necessary by a
 * `ld: undefined reference to memcpy` failure without them. */
void *memcpy(void *dest, const void *src, size_t n) {
	unsigned char *d = (unsigned char *) dest;
	const unsigned char *s = (const unsigned char *) src;
	size_t i;
	for (i = 0; i < n; ++i) {
		d[i] = s[i];
	}
	return dest;
}

void *memmove(void *dest, const void *src, size_t n) {
	unsigned char *d = (unsigned char *) dest;
	const unsigned char *s = (const unsigned char *) src;
	size_t i;
	if (d < s) {
		for (i = 0; i < n; ++i) {
			d[i] = s[i];
		}
	} else {
		while (n > 0) {
			--n;
			d[n] = s[n];
		}
	}
	return dest;
}

void *memset(void *dest, int val, size_t n) {
	unsigned char *d = (unsigned char *) dest;
	size_t i;
	for (i = 0; i < n; ++i) {
		d[i] = (unsigned char) val;
	}
	return dest;
}

/* Real PE entry point (see build-nativegl.sh's -nostdlib/--entry flags) -
 * this DLL skips mingw's normal DllMainCRTStartup wrapper entirely, since
 * that wrapper is what pulls in the api-ms-win-crt-runtime onexit-table
 * functions (_initialize_onexit_table etc.) that don't exist pre-Windows 10,
 * even though nothing in this file's own code needs them. No CRT init
 * happens before this runs - fine here, since nothing below relies on
 * global C++-style constructors, atexit, or any other CRT-provided
 * machinery. */
BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
	return TRUE;
}

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
	/* Raw mouse coordinates are relative to the whole client area - in
	 * fullscreen mode that's the full display mode (see createContext()'s
	 * doc comment), not the smaller, letterboxed content area this engine's
	 * own click/game logic expects coordinates relative to. Subtracting the
	 * same offset createContext() centered the content by converts back to
	 * game-space, matching the (0,0)-offset windowed case exactly. Only
	 * for mouse events - key/char events are pushed with dummy (0,0), which
	 * must stay exactly (0,0) per NativeGL.java's documented {0,0,code}
	 * convention, not get offset into negative dummy coordinates. */
	if (type == INPUT_MOUSE_MOVE || type == INPUT_MOUSE_DOWN || type == INPUT_MOUSE_UP) {
		x -= c->fullscreenOffsetX;
		y -= c->fullscreenOffsetY;
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

static void setOrthoProjection(int offsetX, int offsetY, int width, int height) {
	glViewport(offsetX, offsetY, width, height);
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
		/* Alt (and Alt+key combos, F10) arrive as WM_SYSKEYDOWN/UP, not the
		 * plain WM_KEYDOWN/UP above - a real, if narrow, input gap without
		 * this: KeyHandler.keyReleased() specifically checks
		 * `keyCode == KeyEvent.VK_ALT` to reset swipe-drag zoom tracking,
		 * which never fired at all through the GL window before this.
		 * Consuming these here (not falling through to DefWindowProc) is
		 * deliberate, same as every other key message - a borderless game
		 * window doesn't want Windows' own system-menu/Alt handling. */
		case WM_SYSKEYDOWN:
			pushInputEvent(hwnd, INPUT_KEY_DOWN, 0, 0, (int) wParam);
			return 0;
		case WM_SYSKEYUP:
			pushInputEvent(hwnd, INPUT_KEY_UP, 0, 0, (int) wParam);
			return 0;
		case WM_MOUSEWHEEL: {
			/* Unlike every other mouse message, WM_MOUSEWHEEL's coordinates
			 * are screen-relative, not client-relative - ScreenToClient
			 * converts so pushInputEvent()'s existing fullscreen-offset
			 * subtraction (see its own comment) still applies correctly.
			 * The wheel delta is in the high word of wParam, a signed
			 * multiple of WHEEL_DELTA (120) per notch - reported here as
			 * whole notches (extra), matching what pollGLInput() needs to
			 * build an AWT MouseWheelEvent's wheelRotation. */
			POINT pt;
			int delta = (int) (short) HIWORD(wParam);
			pt.x = (int) (short) (lParam & 0xFFFF);
			pt.y = (int) (short) ((lParam >> 16) & 0xFFFF);
			ScreenToClient(hwnd, &pt);
			pushInputEvent(hwnd, INPUT_MOUSE_WHEEL, pt.x, pt.y, delta / WHEEL_DELTA);
			return 0;
		}
		default:
			return DefWindowProc(hwnd, msg, wParam, lParam);
	}
}

JNIEXPORT jlong JNICALL Java_orsc_graphics_gl_NativeGL_createContext(JNIEnv *env, jclass clazz,
		jint width, jint height, jstring title, jboolean fullscreen, jint fullscreenWidth, jint fullscreenHeight,
		jint fullscreenRefreshHz, jboolean zeroOffset) {
	const char *titleChars;
	HWND hwnd;
	HDC hdc;
	HGLRC hglrc;
	PIXELFORMATDESCRIPTOR pfd;
	int pixelFormat;
	int didModeSwitch = 0;
	int offsetX = 0;
	int offsetY = 0;
	/* Captured before `fullscreen` can be overwritten to JNI_FALSE below
	 * (the mode-switch-failed fallback) - see logGlInfo()'s doc comment. */
	int fullscreenRequested = fullscreen;
	/* fullscreenWidth/Height <= 0 means "mode 1": the display mode matches
	 * width/height exactly (this engine's own native render resolution),
	 * so there's no letterboxing/pillarboxing at all - offsetX/Y stay 0.
	 * A real value (e.g. 640x480 - "mode 2", the classic Voodoo2-era
	 * standard) is a *different, larger* display mode the smaller content
	 * area is centered within instead - see the fullscreen block below. */
	int realFullscreenWidth = (fullscreenWidth > 0) ? fullscreenWidth : width;
	int realFullscreenHeight = (fullscreenHeight > 0) ? fullscreenHeight : height;

	/* Must run before anything else in this function - every gl*()/wgl*()
	 * call from here on is a function-pointer call through the pointers
	 * this populates. See this file's "Dynamic OpenGL loading" section. */
	ensureOpenGLLoaded();

	if (g_contextCount >= MAX_CONTEXTS) {
		return 0;
	}

	if (!g_classRegistered) {
		WNDCLASS wc;
		zeroBytes(&wc, sizeof(wc));
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

	/* Real Voodoo2 MiniGL ICDs historically only rendered in a genuine
	 * exclusive-mode display switch, not just a borderless/maximized
	 * window - see PLAN.md's "Hard constraint driving the design". This is
	 * gated behind the fullscreen flag (see NativeGL.java/GLSceneRenderer's
	 * -Dorsc.gl.fullscreen system property) since it can't be meaningfully
	 * exercised under Wine/macOS, only dgVoodoo2 or real hardware - and
	 * falls back to the normal windowed path below if the mode switch
	 * itself fails, rather than hard-failing context creation, since an
	 * unsupported resolution/bit-depth shouldn't be fatal. 16 bits/pixel
	 * matches the pixel format already chosen below for the same Voodoo2-
	 * era reasoning.
	 *
	 * The display mode itself is realFullscreenWidth/Height - either
	 * width/height directly ("mode 1": this engine's own native render
	 * resolution, no letterboxing at all), or a different, larger mode
	 * such as the classic Voodoo2-era 640x480 standard ("mode 2" - see
	 * GLSceneRenderer/-Dorsc.gl.fullscreen.width/height), in which case
	 * width/height is centered within it (letterboxed/pillarboxed) rather
	 * than stretched, or the engine's own resolution changing to match
	 * (which would touch UI layout/click-region math calibrated to it
	 * throughout mudclient.java). offsetX/offsetY record that centering
	 * (0 for mode 1) so mouse input (pushInputEvent) and every native call
	 * that reads back a game-space pixel coordinate (picking, depth/color
	 * readback) can convert between the two consistently. */
	if (fullscreen) {
		DEVMODE currentDm;
		DEVMODE dm;
		DEVMODE appliedDm;
		int currentBpp;
		int currentHz;
		int targetHz;
		LONG changeResult;

		/* Match the desktop's own current color depth AND refresh rate
		 * rather than forcing/leaving either to a driver default -
		 * windowed mode never changes either at all (it just renders into
		 * whatever the desktop already is), and that's the one thing that
		 * reliably works on real hardware so far. Every fullscreen attempt
		 * so far forced 16bpp and left dmDisplayFrequency unset (driver's
		 * own default) regardless of what the desktop was actually
		 * running, and every one of them (640x480 and 1024x768, both with
		 * and without a centered viewport offset - see zeroOffset below)
		 * showed real visual instability on a real Voodoo 5: either tiled
		 * into two side-by-side copies, or (at 1024x768) correctly
		 * centered but flickering in and out - the kind of symptom a CRT
		 * failing to sync to an unsupported/unstable refresh rate
		 * produces. A forced color-depth switch and an undefined refresh
		 * rate, both away from whatever the card/driver/monitor combo is
		 * already happily running at, are the remaining untested
		 * differences between the working (windowed) and broken
		 * (fullscreen) paths. */
		zeroBytes(&currentDm, sizeof(currentDm));
		currentDm.dmSize = sizeof(currentDm);
		currentBpp = 16;
		currentHz = 0;
		if (EnumDisplaySettings(NULL, ENUM_CURRENT_SETTINGS, &currentDm)) {
			if (currentDm.dmBitsPerPel > 0) {
				currentBpp = currentDm.dmBitsPerPel;
			}
			if (currentDm.dmDisplayFrequency > 0) {
				currentHz = currentDm.dmDisplayFrequency;
			}
		}

		/* Matching the desktop's own refresh rate (above) still showed
		 * sync-loss artifacts (rolling/torn image) on a real Voodoo 5 -
		 * the VSA-100-era 3dfx driver may simply be negotiating/reporting
		 * a rate the CRT can't actually lock to. fullscreenRefreshHz > 0
		 * (-Dorsc.gl.fullscreen.refresh, see NativeGL.java/
		 * GLSceneRenderer) is an explicit manual override for exactly that
		 * case - falls back to the desktop-matching behavior above when
		 * left at 0, and to driver-default (dmFields left unset) if
		 * neither is available. */
		targetHz = (fullscreenRefreshHz > 0) ? fullscreenRefreshHz : currentHz;

		zeroBytes(&dm, sizeof(dm));
		dm.dmSize = sizeof(dm);
		dm.dmPelsWidth = realFullscreenWidth;
		dm.dmPelsHeight = realFullscreenHeight;
		dm.dmBitsPerPel = currentBpp;
		/* Resolution, color depth, and refresh rate are set as fields of
		 * one DEVMODE and applied via a single ChangeDisplaySettings call
		 * (not staged/sequential calls) - the VSA-100 driver stack has
		 * historically handled a simultaneous change far more reliably. */
		dm.dmFields = DM_PELSWIDTH | DM_PELSHEIGHT | DM_BITSPERPEL;
		if (targetHz > 0) {
			/* 0 (unset field) means "driver default" - only specify this
			 * if a real value is available (explicit override or a
			 * successfully queried desktop rate), rather than substituting
			 * some other hardcoded guess. */
			dm.dmDisplayFrequency = targetHz;
			dm.dmFields |= DM_DISPLAYFREQUENCY;
		}
		changeResult = ChangeDisplaySettings(&dm, CDS_FULLSCREEN);
		if (changeResult == DISP_CHANGE_SUCCESSFUL) {
			didModeSwitch = 1;

			/* Give the monitor/driver time to actually relock to the new
			 * timing before a GL context and first SwapBuffers land on
			 * top of it - mode switch and context creation previously
			 * happened back-to-back with no settle time at all, which the
			 * VSA-100-era driver stack may not tolerate as well as a
			 * modern one. */
			Sleep(500);

			/* Log what was actually negotiated, not just what was asked
			 * for - ChangeDisplaySettings can silently coerce an
			 * unsupported field (refresh rate in particular) to something
			 * else rather than failing outright. See
			 * logFullscreenDiag()'s comment. */
			zeroBytes(&appliedDm, sizeof(appliedDm));
			appliedDm.dmSize = sizeof(appliedDm);
			EnumDisplaySettings(NULL, ENUM_CURRENT_SETTINGS, &appliedDm);
			logFullscreenDiag(realFullscreenWidth, realFullscreenHeight, currentBpp, targetHz,
					(int) appliedDm.dmPelsWidth, (int) appliedDm.dmPelsHeight,
					(int) appliedDm.dmBitsPerPel, (int) appliedDm.dmDisplayFrequency, 1);

			/* Diagnostic only (-Dorsc.gl.fullscreen.zerooffset, see
			 * GLSceneRenderer/NativeGL.java) - forces the GL viewport to
			 * start at (0,0) instead of centering it. Confirmed on a real
			 * Voodoo 5 this does NOT fix the tiling artifact either -
			 * ruled out as the cause, kept only in case a future driver
			 * combination behaves differently. */
			if (zeroOffset) {
				offsetX = 0;
				offsetY = 0;
			} else {
				offsetX = (realFullscreenWidth - width) / 2;
				offsetY = (realFullscreenHeight - height) / 2;
			}
		} else {
			logFullscreenDiag(realFullscreenWidth, realFullscreenHeight, currentBpp, targetHz, 0, 0, 0, 0, 0);
			fullscreen = JNI_FALSE;
		}
	}

	if (fullscreen) {
		/* Exclusive-mode fullscreen: a borderless popup exactly covering
		 * the (now-switched) display mode, topmost so nothing else can
		 * show through it - the standard Win32 pattern for GL/D3D
		 * fullscreen apps of this era. Sized to the full display mode, not
		 * width/height (the smaller content area centered within it) - the
		 * letterbox/pillarbox bars are real screen area this window still
		 * owns and paints (as the background clear color), just outside
		 * the viewport the actual game content renders into. */
		titleChars = (*env)->GetStringUTFChars(env, title, NULL);
		hwnd = CreateWindowEx(WS_EX_TOPMOST, CLASS_NAME, titleChars, WS_POPUP,
				0, 0, realFullscreenWidth, realFullscreenHeight, NULL, NULL, GetModuleHandle(NULL), NULL);
		(*env)->ReleaseStringUTFChars(env, title, titleChars);
	} else {
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
		if (didModeSwitch) {
			ChangeDisplaySettings(NULL, 0);
		}
		return 0;
	}

	hdc = GetDC(hwnd);
	if (hdc == NULL) {
		DestroyWindow(hwnd);
		if (didModeSwitch) {
			ChangeDisplaySettings(NULL, 0);
		}
		return 0;
	}

	zeroBytes(&pfd, sizeof(pfd));
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
		if (didModeSwitch) {
			ChangeDisplaySettings(NULL, 0);
		}
		return 0;
	}

	hglrc = wglCreateContext(hdc);
	if (hglrc == NULL) {
		ReleaseDC(hwnd, hdc);
		DestroyWindow(hwnd);
		if (didModeSwitch) {
			ChangeDisplaySettings(NULL, 0);
		}
		return 0;
	}

	if (!wglMakeCurrent(hdc, hglrc)) {
		wglDeleteContext(hglrc);
		ReleaseDC(hwnd, hdc);
		DestroyWindow(hwnd);
		if (didModeSwitch) {
			ChangeDisplaySettings(NULL, 0);
		}
		return 0;
	}

	logGlInfo(fullscreenRequested, didModeSwitch);

	setOrthoProjection(offsetX, offsetY, width, height);
	glDisable(GL_DEPTH_TEST);

	g_contexts[g_contextCount].hwnd = hwnd;
	g_contexts[g_contextCount].hdc = hdc;
	g_contexts[g_contextCount].hglrc = hglrc;
	g_contexts[g_contextCount].inputHead = 0;
	g_contexts[g_contextCount].inputTail = 0;
	g_contexts[g_contextCount].fullscreen = didModeSwitch;
	g_contexts[g_contextCount].fullscreenOffsetX = offsetX;
	g_contexts[g_contextCount].fullscreenOffsetY = offsetY;
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
	/* Not expected to actually fire in fullscreen mode (a fixed-size
	 * borderless popup doesn't get resized), but preserves the existing
	 * offset instead of silently resetting it to 0 if it somehow does. */
	setOrthoProjection(c->fullscreenOffsetX, c->fullscreenOffsetY, width, height);
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
	 * glScissor/glReadPixels are bottom-left-origin. Also shift both axes
	 * by the fullscreen letterbox/pillarbox offset (0 in windowed mode) -
	 * x/y/screenHeight here are game-space, but glScissor/glReadPixels need
	 * real window coordinates, and in fullscreen mode those differ by
	 * exactly this offset (see createContext()'s doc comment). */
	x += c->fullscreenOffsetX;
	glY = (screenHeight - y - 1) + c->fullscreenOffsetY;
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

	/* Same offset shift as setPickScissor() - see its comment. */
	x += c->fullscreenOffsetX;
	glY = (screenHeight - y - 1) + c->fullscreenOffsetY;
	half = size / 2;

	/* glRasterPos (which glDrawPixels anchors on) is transformed by the
	 * current modelview/projection like any other vertex - under the real
	 * 3D perspective frustum, a raw window-pixel position would almost
	 * certainly fall outside the view volume and make the raster position
	 * invalid, silently dropping the glDrawPixels call. Temporarily switch
	 * to a pixel-space ortho (matching drawUIOverlay's existing push/pop
	 * pattern) so glRasterPos2i can take plain window coordinates. Sized to
	 * the real window (screenWidth/Height plus the offset on both sides),
	 * not just the game content area, so a raster position shifted by the
	 * fullscreen offset is still within the valid ortho volume. */
	glMatrixMode(GL_PROJECTION);
	glPushMatrix();
	glLoadIdentity();
	glOrtho(0.0, (double) (screenWidth + 2 * c->fullscreenOffsetX),
			0.0, (double) (screenHeight + 2 * c->fullscreenOffsetY), -1.0, 1.0);
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

	/* Same offset shift as setPickScissor() - see its comment. */
	glY = (screenHeight - y - 1) + c->fullscreenOffsetY;
	pixel[0] = pixel[1] = pixel[2] = 0;
	glReadPixels(x + c->fullscreenOffsetX, glY, 1, 1, GL_RGB, GL_UNSIGNED_BYTE, pixel);

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
	 * already has to for x/y here. Also shifted by the fullscreen offset,
	 * same as setPickScissor() - see its comment. */
	glY = (screenHeight - y - height) + c->fullscreenOffsetY;

	/* GetPrimitiveArrayCritical, not GetFloatArrayElements: this runs once
	 * every frame that has visible sprites (see GLSceneRenderer.
	 * drawSpriteBillboards()), and older HotSpot Client VMs (Java 1.3/1.4,
	 * what this project actually ships for) typically copy the backing
	 * array on GetFloatArrayElements rather than pinning it - an extra
	 * memcpy of the whole rect on top of the real glReadPixels cost, every
	 * single frame. GetPrimitiveArrayCritical can return a direct pointer
	 * instead when the GC allows it. Safe here: nothing between Get/Release
	 * calls back into the JVM or blocks - just one GL call, same
	 * constraint drawTriangles() below relies on too. */
	buf = (*env)->GetPrimitiveArrayCritical(env, outDepths, NULL);
	if (buf == NULL) {
		return;
	}
	glReadPixels(x + c->fullscreenOffsetX, glY, width, height, GL_DEPTH_COMPONENT, GL_FLOAT, buf);
	(*env)->ReleasePrimitiveArrayCritical(env, outDepths, buf, 0);
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
	 * times at login, not per-frame), so a straightforward heap-allocated
	 * scratch buffer is fine here rather than a fixed-size static one -
	 * HeapAlloc/HeapFree (kernel32), not malloc/free, since this DLL has no
	 * CRT at all (see build-nativegl.sh and zeroBytes()'s comment above).
	 * Also shifted by the fullscreen offset, same as setPickScissor() - see
	 * its comment - since the caller (capturing the whole game-space
	 * canvas) has no idea its content is centered within a larger real
	 * framebuffer in fullscreen mode. */
	glY = (screenHeight - y - height) + c->fullscreenOffsetY;

	total = width * height;
	buf = (unsigned char *) HeapAlloc(GetProcessHeap(), 0, (SIZE_T) total * 3);
	if (buf == NULL) {
		return;
	}
	glReadPixels(x + c->fullscreenOffsetX, glY, width, height, GL_RGB, GL_UNSIGNED_BYTE, buf);

	outBuf = (*env)->GetIntArrayElements(env, outColors, NULL);
	if (outBuf != NULL) {
		for (i = 0; i < total; ++i) {
			outBuf[i] = ((jint) buf[i * 3] << 16) | ((jint) buf[i * 3 + 1] << 8) | (jint) buf[i * 3 + 2];
		}
		(*env)->ReleaseIntArrayElements(env, outColors, outBuf, 0);
	}
	HeapFree(GetProcessHeap(), 0, buf);
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

	/* GetPrimitiveArrayCritical, not GetFloatArrayElements: called once per
	 * texture bucket every frame (see GLSceneRenderer.endScene()), each
	 * carrying a whole scene's worth of vertices. Older HotSpot Client VMs
	 * (Java 1.3/1.4) typically copy the backing array on
	 * GetFloatArrayElements rather than pinning it - a full memcpy of the
	 * vertex buffer on top of the actual GL upload, every call, every
	 * frame. GetPrimitiveArrayCritical can return a direct pointer instead
	 * when the GC allows it. Safe here: nothing between Get/Release calls
	 * back into the JVM or blocks - only GL calls, which don't touch the
	 * JVM at all. */
	data = (*env)->GetPrimitiveArrayCritical(env, vertexData, NULL);
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

	(*env)->ReleasePrimitiveArrayCritical(env, vertexData, data, JNI_ABORT);
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

/* uMax/vMax: the fraction of the bound texture that actually holds real
 * content, everywhere else defaulting to the full 0..1 range this used to
 * hardcode. Needed because real pre-2003 OpenGL ICDs (this engine's actual
 * target, unlike Wine's much more permissive translation layer) require
 * texture dimensions to be an exact power of two - GLSceneRenderer pads the
 * uploaded texture up to the next power-of-two size to satisfy that, and
 * passes the real content's fraction of it here so this quad only samples
 * the valid region instead of stretching into the unused padding. Confirmed
 * necessary the hard way: without this, the GL window rendered solid blank
 * white on a real Voodoo 5 (3dfx MiniGL ICD), despite working fine under
 * Wine's translation the whole time this was being developed. */
JNIEXPORT void JNICALL Java_orsc_graphics_gl_NativeGL_drawUIOverlay(JNIEnv *env, jclass clazz,
		jlong ctxHandle, jint texId, jfloat uMax, jfloat vMax) {
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
		glTexCoord2f(0.0f, vMax); glVertex2f(-1.0f, -1.0f);
		glTexCoord2f(uMax, vMax); glVertex2f(1.0f, -1.0f);
		glTexCoord2f(uMax, 0.0f); glVertex2f(1.0f, 1.0f);
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

	/* Undo createContext()'s CDS_FULLSCREEN display-mode switch, if it did
	 * one - passing NULL restores whatever mode was active before it. */
	if (c->fullscreen) {
		ChangeDisplaySettings(NULL, 0);
	}

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
