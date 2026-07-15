/**
 * Phase 0 spike entry point. Opens a native Win32 window with a WGL
 * context, renders a flat-colored triangle for ~15 seconds (or until the
 * window is closed), then tears down cleanly.
 */
public class GLTest {
	public static void main(String[] args) throws Exception {
		System.out.println("GLTest: creating window...");
		long hwnd = NativeGL.createWindow(640, 480, "NativeGL Spike - WGL Triangle Test");
		if (hwnd == 0) {
			System.out.println("GLTest: createWindow failed");
			return;
		}
		System.out.println("GLTest: window created, hwnd=" + hwnd);

		long start = System.currentTimeMillis();
		boolean running = true;
		int frames = 0;
		while (running && (System.currentTimeMillis() - start) < 15000) {
			running = NativeGL.pumpMessages(hwnd);
			if (!running) {
				break;
			}
			NativeGL.renderFrame(hwnd);
			++frames;
			Thread.sleep(16);
		}

		System.out.println("GLTest: rendered " + frames + " frames, destroying window");
		NativeGL.destroyWindow(hwnd);
		System.out.println("GLTest: done");
	}
}
