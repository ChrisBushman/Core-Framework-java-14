/**
 * Phase 0 spike: proves a Win32 + WGL context can be created and drawn to
 * from this Java process at all, on the real Java 1.4.2 JVM we ship for.
 * No AWT/JAWT integration here on purpose — embedding into Swing is a
 * separate, later decision (see ../PLAN.md, Phase 5).
 */
public class NativeGL {

	static {
		System.loadLibrary("NativeGL");
	}

	/**
	 * Creates a native Win32 window with a WGL context current on it.
	 * Returns the HWND (cast to long), or 0 on failure.
	 */
	public static native long createWindow(int width, int height, String title);

	/**
	 * Pumps pending Win32 messages for the window. Must be called from the
	 * same thread that called createWindow. Returns false once the window
	 * has received WM_QUIT (e.g. the user closed it), true otherwise.
	 */
	public static native boolean pumpMessages(long hwnd);

	/**
	 * Clears the frame, draws one immediate-mode triangle, and swaps buffers.
	 */
	public static native void renderFrame(long hwnd);

	/**
	 * Destroys the GL context and the window.
	 */
	public static native void destroyWindow(long hwnd);
}
