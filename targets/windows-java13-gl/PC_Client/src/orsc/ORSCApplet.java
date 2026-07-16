package orsc;

import com.openrsc.client.model.Sprite;
import orsc.graphics.gl.NativeGL;
import orsc.graphics.three.GLSceneRenderer;
import orsc.graphics.two.Fonts;
import orsc.multiclient.ClientPort;
import orsc.util.GenUtil;

import javax.swing.*;
import java.applet.Applet;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.io.ByteArrayInputStream;

import orsc.multiclient.ClientPortHelper;

public class ORSCApplet extends Applet implements ComponentListener, ImageObserver, ImageProducer, ClientPort {
	private static final long serialVersionUID = 1L;
	public static int globalLoadingPercent = 0;
	public static String globalLoadingState = "";
	private static mudclient mudclient;
	static PacketHandler packetHandler;
	private final boolean m_hb = false;
	protected int resizeWidth;
	protected int resizeHeight;
	private Font createdbyFont = new Font("Helvetica", 1, 13);
	private Font copyrightFont2 = new Font("Helvetica", 0, 12);
	private Font loadingFont = new Font("TimesRoman", 0, 15);
	private Graphics loadingGraphics;
	private Image loadingLogo;
	private String loadingState = "Loading";
	boolean m_N = false;
	private String m_p = null;
	private int loadingPercent = 0;
	private int height = 384;
	private int width = 512;
	private DirectColorModel imageModel;
	private Image backingImage;
	private ImageConsumer imageProducer;
	private MouseHandler mouseHandler;
	private KeyHandler keyHandler;
	protected static ScaledWindow scaledWindow;
	private static BufferedImage game_image;
	private static Graphics2D g2dForGameImage;
	public static float oldRenderingScalar = 1.0f;

	public MouseHandler getMouseHandler() {
		return mouseHandler;
	}

	public KeyHandler getKeyHandler() {
		return keyHandler;
	}

	void addMouseClick(int button, int x, int y) {
		try {
		} catch (RuntimeException var6) {
			throw GenUtil.makeThrowable(var6, "e.Q(" + x + ',' + "dummy" + ',' + button + ',' + y + ')');
		}
	}

	private void drawCenteredString(Font var1, String str, int y, int x, Graphics g) {
		try {
			FontMetrics metrics = getFontMetrics(var1);
			g.setFont(var1);
			g.drawString(str, x - metrics.stringWidth(str) / 2, y + metrics.getHeight() / 4);
		} catch (RuntimeException var9) {
			throw GenUtil.makeThrowable(var9,
				"e.LE(" + (var1 != null ? "{...}" : "null") + ',' + (str != null ? "{...}" : "null") + ',' + y + ','
					+ true + ',' + x + ',' + (g != null ? "{...}" : "null") + ')');
		}
	}

	public final boolean drawLoading(int var1) {
		try {
			Graphics var2 = this.getGraphics();
			if (var2 != null) {
				this.loadingGraphics = scaledWindow.getGraphics();
				this.loadingGraphics.translate(mudclient.screenOffsetX, mudclient.screenOffsetY);
				this.loadingGraphics.setColor(Color.black);
				this.loadingGraphics.fillRect(0, 0, this.width, this.height);
				this.drawLoadingScreen("Loading...", 0, var1 ^ 103);
				return true;
			} else return false;
		} catch (RuntimeException var3) {
			throw GenUtil.makeThrowable(var3, "e.ME(" + var1 + ')');
		}
	}

	public boolean isDisplayable() {
		return super.isDisplayable();
	}

	private void drawLoadingScreen(String state, int percent, int var3) {
		try {
			try {
				int x = (this.width - 281) / 2;
				int y = (this.height - 148) / 2;
				this.loadingGraphics.setColor(Color.black);
				this.loadingGraphics.fillRect(0, 0, this.width, this.height);
				if (!this.m_hb) this.loadingGraphics.drawImage(this.loadingLogo, x, y, this);

				x += 2;
				this.loadingPercent = percent;
				y += 90;
				this.loadingState = state;
				if (var3 <= 97) mouseHandler.mouseReleased(null);

				this.loadingGraphics.setColor(new Color(132, 132, 132));
				if (this.m_hb) this.loadingGraphics.setColor(new Color(220, 0, 0));

				this.loadingGraphics.drawRect(x - 2, y - 2, 280, 23);
				this.loadingGraphics.fillRect(x, y, percent * 277 / 100, 20);
				this.loadingGraphics.setColor(new Color(198, 198, 198));
				if (this.m_hb) this.loadingGraphics.setColor(new Color(255, 255, 255));

				this.drawCenteredString(this.loadingFont, state, 10 + y, 138 + x, this.loadingGraphics);

				if (!this.m_hb) {
					this.drawCenteredString(this.createdbyFont, "Powered by Open RSC", 30 + y,
						x + 138, this.loadingGraphics);
					this.drawCenteredString(this.createdbyFont, "We support open source development.", y + 44, x + 138,
						this.loadingGraphics);
				} else {
					this.loadingGraphics.setColor(new Color(132, 132, 152));
					this.drawCenteredString(this.copyrightFont2, "We support open source development.", this.height - 20,
						138 + x, this.loadingGraphics);
				}

				if (null != this.m_p) {
					this.loadingGraphics.setColor(Color.white);
					this.drawCenteredString(this.createdbyFont, this.m_p, y - 120, x + 138, this.loadingGraphics);
				}
			} catch (Exception ignored) {
			}
		} catch (RuntimeException var7) {
			throw GenUtil.makeThrowable(var7,
				"e.FE(" + (state != null ? "{...}" : "null") + ',' + percent + ',' + var3 + ')');
		}
	}

	public final void paint(Graphics var1) {
		try {
			if (mudclient != null) {
				mudclient.rendering = true;
				if (mudclient.getGameState() == 2 && this.loadingLogo != null)
					this.drawLoadingScreen(this.loadingState, this.loadingPercent, 126);
			}
		} catch (RuntimeException var3) {
			throw GenUtil.makeThrowable(var3, "e.paint(" + (var1 != null ? "{...}" : "null") + ')');
		}
	}

	boolean reposition() {
		return false;
	}

	public final void showLoadingProgress(int percent, String state) {
		try {
			try {
				int x = (this.width - 281) / 2;
				x += 2;
				int y = (this.height - 148) / 2;
				this.loadingState = state;
				this.loadingPercent = percent;
				y += 90;
				int progress = percent * 277 / 100;
				this.loadingGraphics.setColor(new Color(132, 132, 132));
				if (this.m_hb) this.loadingGraphics.setColor(new Color(220, 0, 0));
				this.loadingGraphics.fillRect(x, y, progress, 20);
				this.loadingGraphics.setColor(Color.black);
				this.loadingGraphics.fillRect(progress + x, y, 277 - progress, 20);
				this.loadingGraphics.setColor(new Color(198, 198, 198));
				if (this.m_hb) this.loadingGraphics.setColor(new Color(255, 255, 255));
				this.drawCenteredString(this.loadingFont, state, 10 + y, 138 + x, this.loadingGraphics);
			} catch (Exception ignored) {
			}
		} catch (RuntimeException var8) {
			throw GenUtil.makeThrowable(var8, "e.EE(" + percent + ',' + (state != null ? "{...}" : "null") + ')');
		}
	}

	public final void init() {
		try {
			mudclient = new mudclient(this);
			mudclient.packetHandler = new PacketHandler(mudclient);
			loadLogo();

			mouseHandler = new MouseHandler();
			keyHandler = new KeyHandler();

			this.addMouseListener(mouseHandler);
			this.addMouseMotionListener(mouseHandler);
			this.addKeyListener(keyHandler);
			// setFocusTraversalKeysEnabled() added in Java 1.4; no equivalent pre-1.4,
			// so Tab may be intercepted by AWT's default focus-cycling on Java 1.3
			this.addComponentListener(this);
			// addMouseWheelListener() added in Java 1.4; no scroll-wheel support pre-1.4
		} catch (RuntimeException var2) {
			throw GenUtil.makeThrowable(var2, "client.init()");
		}
	}

	public void loadLogo() {
		// Leaving this blank
	}

	private void startApplet() {
		try {
			System.out.println("Started applet");
			this.width = 512;
			this.height = 346;
			mudclient.startMainThread();
		} catch (RuntimeException var12) {
			throw GenUtil.makeThrowable(var12, "e.OE(" + 346 + ',' + Config.CLIENT_VERSION + ',' + 12 + ',' + 512 + ')');
		}
		try {
			// Don't load Discord on ARM
			if (System.getProperty("os.arch").indexOf("aarch64") < 0) {
				Discord.InitalizeDiscord();
			}
		} catch (Exception e) { }
	}

	public final void stop() {
		try {
			try {
				mudclient.clientBaseThread.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			} finally {
				System.exit(0);
			}
		} catch (RuntimeException var2) {
			throw GenUtil.makeThrowable(var2, "e.stop()");
		}
	}

	public final void update(Graphics var1) {
		try {
			this.paint(var1);
		} catch (RuntimeException var3) {
			throw GenUtil.makeThrowable(var3, "e.update(" + (var1 != null ? "{...}" : "null") + ')');
		}
	}

	private void updateControlShiftState(InputEvent var1) {
		try {
			int mod = var1.getModifiers();
			if (mudclient == null)
				return;
			mudclient.controlPressed = (mod & Event.CTRL_MASK) != 0;
			mudclient.shiftPressed = (mod & Event.SHIFT_MASK) != 0;
		} catch (RuntimeException e) {
			throw GenUtil.makeThrowable(e, "e.SE(" + (var1 != null ? "{...}" : "null") + ',' + "dummy" + ')');
		}
	}

	public final void start() {
		try {
			if (mudclient.threadState >= 0) {
				mudclient.threadState = 0;
			}
			startApplet();
		} catch (RuntimeException var2) {
			throw GenUtil.makeThrowable(var2, "e.start()");
		}
	}

	public void componentShown(ComponentEvent e) {
	}

	void resizeMudclient(int width, int height) {
		mudclient.resizeWidth = width;
		mudclient.resizeHeight = height;
	}

	void resetArrowKeys() {
		mudclient.keyUp = false;
		mudclient.keyDown = false;
		mudclient.keyLeft = false;
		mudclient.keyRight = false;
	}

	public void componentResized(ComponentEvent e) {
		// See ScaledWindow.isGlRendererActive()'s comment: the hidden Swing
		// chrome's layout is stale/meaningless in GL mode (launchScaledWindow()
		// - the only call that ever resizes it correctly - is skipped), and
		// must not be allowed to overwrite mudclient's real game dimensions.
		if ("gl".equals(System.getProperty("orsc.renderer"))) {
			return;
		}
		mudclient.resizeWidth = e.getComponent().getWidth();
		mudclient.resizeHeight = e.getComponent().getHeight();
	}

	public void componentMoved(ComponentEvent e) {
	}

	public void componentHidden(ComponentEvent e) {
	}

	public void initListeners() {
	}

	public void crashed() {
	}

	public void drawLoadingError() {
		Graphics g = this.getGraphics();
		if (g != null) {
			g.translate(mudclient.screenOffsetX, mudclient.screenOffsetY);
			g.setColor(Color.black);
			g.fillRect(0, 0, 512, 356);
			g.setFont(new Font("Helvetica", 1, 16));
			g.setColor(Color.yellow);
			byte var3 = 35;
			g.drawString("Sorry, an error has occured whilst loading " + Config.getServerNameWelcome(), 30, var3);
			g.setColor(Color.white);
			int var6 = var3 + 50;
			g.drawString("To fix this try the following (in order):", 30, var6);
			g.setColor(Color.white);
			var6 += 50;
			g.setFont(new Font("Helvetica", 1, 12));
			g.drawString("1: Try closing ALL open web-browser windows, and reloading", 30, var6);
			var6 += 30;
			g.drawString("2: Try clearing your web-browsers cache from tools->internet options", 30, var6);
			var6 += 30;
			g.drawString("3: Try using a different game-world", 30, var6);
			var6 += 30;
			g.drawString("4: Try rebooting your computer", 30, var6);
			var6 += 30;
			g.drawString("5: Try selecting a different version of Java from the play-game menu", 30, var6);
		}
	}

	public void drawOutOfMemoryError() {
		Graphics g = this.getGraphics();
		if (null != g) {
			g.translate(mudclient.screenOffsetX, mudclient.screenOffsetY);
			g.setColor(Color.black);
			g.fillRect(0, 0, 512, 356);
			g.setFont(new Font("Helvetica", 1, 20));
			g.setColor(Color.white);
			g.drawString("Error - out of memory!", 50, 50);
			g.drawString("Close ALL unnecessary programs", 50, 100);
			g.drawString("and windows before loading the game", 50, 150);
			g.drawString(Config.getServerName() + " needs about 48meg of spare RAM", 50, 200);
		}
	}

	public void drawTextBox(String line2, byte var2, String line1) {
		Graphics g = this.getGraphics();
		if (null != g) {
			g.translate(mudclient.screenOffsetX, mudclient.screenOffsetY);
			Font font = new Font("Helvetica", 1, 15);
			short width = 512;
			g.setColor(Color.black);
			short height = 344;
			g.fillRect(width / 2 - 140, height / 2 - 25, 280, 50);
			g.setColor(Color.white);
			g.drawRect(width / 2 - 140, height / 2 - 25, 280, 50);
			this.drawCenteredString(font, line1, height / 2 - 10, width / 2, g);
			this.drawCenteredString(font, line2, 10 + height / 2, width / 2, g);
		}
	}

	public void initGraphics() {
		// draw() uses game_image.setRGB() directly \u2014 the old backingImage/ImageProducer
		// async pipeline is unused and crashes under Java 1.4 in Wine.
		this.imageModel = new DirectColorModel(32, 16711680, '\uff00', 255);
	}

	private synchronized void commitToImage(boolean var1) {
		try {
			if (null != this.imageProducer) {
				this.imageProducer.setPixels(0, 0, mudclient.getSurface().width2, mudclient.getSurface().height2,
					this.imageModel, mudclient.getSurface().pixelData, 0, mudclient.getSurface().width2);
				this.imageProducer.imageComplete(2);
			}
		} catch (RuntimeException var3) {
			throw GenUtil.makeThrowable(var3, "ua.CA(" + true + ')');
		}
	}

	public void addConsumer(ImageConsumer arg0) {
		try {
			this.imageProducer = arg0;
			arg0.setDimensions(mudclient.getSurface().width2, mudclient.getSurface().height2);
			arg0.setProperties(null);
			arg0.setColorModel(this.imageModel);
			arg0.setHints(14);
		} catch (RuntimeException var3) {
			throw GenUtil.makeThrowable(var3, "ua.addConsumer(" + (arg0 != null ? "{...}" : "null") + ')');
		}
	}

	public boolean isConsumer(ImageConsumer arg0) {
		return this.imageProducer == arg0;
	}

	public void removeConsumer(ImageConsumer arg0) {
		if (this.imageProducer == arg0) this.imageProducer = null;
	}

	public void requestTopDownLeftRightResend(ImageConsumer arg0) {
		try {
			System.out.println("TDLR");
		} catch (RuntimeException var3) {
			throw GenUtil.makeThrowable(var3,
				"ua.requestTopDownLeftRightResend(" + (arg0 != null ? "{...}" : "null") + ')');
		}
	}

	public void startProduction(ImageConsumer arg0) {
		this.addConsumer(arg0);
	}

	// On-screen FPS counter, opt-in via -Dorsc.fps=true (matching this
	// project's existing -Dorsc.* system-property convention rather than a
	// plain command-line flag, so it doesn't need its own args[] parsing in
	// OpenRSC.main()). Drawn here, not inside GLSceneRenderer, specifically
	// so it works identically for both render paths - draw() is the one
	// place both the GL and CPU/software paths pass through every frame,
	// right before whichever one actually composites/blits pixelData (see
	// the two branches below); GLSceneRenderer.presentUIOverlay() only
	// exists on the GL path, so putting it there first would have silently
	// done nothing under the plain software Scene renderer.
	private static final boolean SHOW_FPS = "true".equals(System.getProperty("orsc.fps"));
	private long fpsWindowStartMs;
	private int fpsFrameCount;
	private int fpsLastComputed;

	private void updateAndDrawFps() {
		if (mudclient.getSurface().pixelData == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (fpsWindowStartMs == 0) {
			fpsWindowStartMs = now;
		}
		++fpsFrameCount;
		long elapsed = now - fpsWindowStartMs;
		if (elapsed >= 1000) {
			// Rounds to the nearest whole FPS rather than truncating, and
			// accounts for windows that ran slightly over/under 1000ms
			// (this is only called once per real displayed frame, so the
			// window boundary rarely lands on an exact second).
			fpsLastComputed = Math.round(fpsFrameCount * 1000f / elapsed);
			fpsFrameCount = 0;
			fpsWindowStartMs = now;
		}
		mudclient.getSurface().drawShadowText("FPS: " + fpsLastComputed, 5, 12, 0xFFFF00, 1, false);
	}

	public final void draw() {
		boolean glActive = mudclient.getScene() instanceof GLSceneRenderer;

		if (SHOW_FPS) {
			updateAndDrawFps();
		}

		// Re-scale when needed
		if (orsc.mudclient.newRenderingScalar != oldRenderingScalar) {
			updateRenderingScalarAndResize(orsc.mudclient.newRenderingScalar, mudclient.getGameWidth(), mudclient.getGameHeight());
			oldRenderingScalar = orsc.mudclient.newRenderingScalar;
		}

		// Skipped entirely when the GL renderer is active: the Swing window
		// is never shown in that case (see OpenRSC.createAndShowGUI()), so
		// this copy - plus the Graphics2D blit setGameImage() does - would
		// be pure wasted work every frame, on top of the separate copy of
		// the same pixelData presentUIOverlay() below already does for the
		// GL texture upload. Copy pixel data directly into game_image,
		// bypassing the async ImageProducer chain which is unreliable in
		// Java 1.4 under Wine (imageProducer may never be set).
		if (!glActive) {
			if (game_image != null && mudclient.getSurface().pixelData != null) {
				int surfW = mudclient.getSurface().width2;
				int surfH = mudclient.getSurface().height2;
				if (surfW > 0 && surfH > 0) {
					int[] dst = ((DataBufferInt) game_image.getRaster().getDataBuffer()).getData();
					int len = Math.min(surfW * surfH, dst.length);
					if (mudclient.getSurface().pixelData.length >= len) {
						System.arraycopy(mudclient.getSurface().pixelData, 0, dst, 0, len);
					}
				}
			}

			// Forward the image to be drawn by ScaledWindow.java
			scaledWindow.setGameImage(game_image);
		}

		// This is the point in the frame where the 2D UI (chat, inventory,
		// minimap...) has finished drawing into pixelData - see
		// GLSceneRenderer.presentUIOverlay()'s doc comment for why here,
		// not inside endScene(). Not part of the SceneRenderer interface,
		// since Scene has no equivalent need.
		if (glActive) {
			((GLSceneRenderer) mudclient.getScene()).presentUIOverlay(
					mudclient.getSurface().pixelData, mudclient.getSurface().width2, mudclient.getSurface().height2);
			pollGLInput();
		}
	}

	// Tracked across calls so mouse events synthesized between key events
	// carry the right modifier bits - mirrors AWT's own per-InputEvent
	// getModifiers(), which we don't get here since these events never
	// pass through a real AWT peer.
	private boolean glShiftDown = false;
	private boolean glControlDown = false;
	private final int[] glInputBuf = new int[3];

	/**
	 * Drains the native GL window's input queue (see NativeGL.pollInputEvent()
	 * and Client_Base/native-gl/NativeGL.c's WndProc) and forwards each event
	 * to the exact same MouseHandler/KeyHandler ScaledWindow's real AWT
	 * listeners already call - so the GL window becomes independently
	 * interactive (Phase 5) without touching any of mudclient's existing
	 * input-handling logic.
	 *
	 * Deliberately not a full AWT event replica, though closer than it was:
	 * mouseWheelMoved is now forwarded (zoom and chat-panel scroll both
	 * depend on it - see MouseHandler.mouseWheelMoved()), and Alt now
	 * reaches KeyHandler via WM_SYSKEYDOWN/UP (NativeGL.c's WndProc) rather
	 * than being silently dropped (Win32 never sends plain WM_KEYDOWN/UP
	 * for it) - KeyHandler.keyReleased() specifically checks for it to
	 * reset swipe-drag zoom tracking. Checked what mouseClicked/
	 * mouseEntered/mouseExited/a real meta-key modifier would actually be
	 * used for before adding them: all three handlers only call
	 * updateControlShiftState() (redundant with what press/release/move
	 * already trigger), and nothing in this codebase reads a meta modifier
	 * at all - forwarding them would add surface area for zero behavior
	 * change, so they're still not sent. mouseDragged isn't distinguished
	 * from mouseMoved via raw Win32 button-state either, but via
	 * mudclient.currentMouseButtonDown - equivalent in practice, since this
	 * window is the sole source of this window's own press/release events.
	 */
	private void pollGLInput() {
		long ctx = ((GLSceneRenderer) mudclient.getScene()).getNativeContext();
		if (ctx == 0) {
			return;
		}
		int type;
		while ((type = NativeGL.pollInputEvent(ctx, glInputBuf)) != NativeGL.INPUT_NONE) {
			// MouseHandler.mousePressed/Released/Dragged all subtract
			// mudclient.screenOffsetX/Y from the event's raw coordinates
			// (real AWT embeddings can have the game viewport offset within
			// a larger canvas - screenOffsetX is 113 under some conditions,
			// see mudclient.setFPS()). This native window IS exactly the
			// game content area with no such offset, so add it back here to
			// cancel out that subtraction - otherwise every click lands
			// 113px away from where it visually is.
			int x = glInputBuf[0] + mudclient.screenOffsetX;
			int y = glInputBuf[1] + mudclient.screenOffsetY;
			int extra = glInputBuf[2];
			long when = System.currentTimeMillis();
			int modifiers = (glShiftDown ? InputEvent.SHIFT_MASK : 0) | (glControlDown ? InputEvent.CTRL_MASK : 0);

			switch (type) {
				case NativeGL.INPUT_MOUSE_MOVE: {
					boolean dragging = mudclient != null && mudclient.currentMouseButtonDown != 0;
					int id = dragging ? MouseEvent.MOUSE_DRAGGED : MouseEvent.MOUSE_MOVED;
					// MouseEvent(..., button) constructor and NOBUTTON added in
					// Java 1.4; the 8-arg ctor carries no button info, which is
					// fine here since move/drag events don't need it.
					MouseEvent evt = new MouseEvent(this, id, when, modifiers, x, y, 0, false);
					if (dragging) {
						getMouseHandler().mouseDragged(evt);
					} else {
						getMouseHandler().mouseMoved(evt);
					}
					break;
				}
				// mouseWheelMoved()/MouseWheelEvent added in Java 1.4; no
				// scroll-wheel support forwarded from the GL window pre-1.4,
				// same as the normal AWT/Swing path (see ScaledWindow).
				case NativeGL.INPUT_MOUSE_DOWN: {
					// MouseEvent(..., button) constructor added in Java 1.4;
					// button info is carried via modifiers
					// (BUTTON1_MASK/BUTTON3_MASK) instead, same pattern as
					// ScaledWindow.mapMouseEvent().
					int buttonMask = extra == 3 ? InputEvent.BUTTON3_MASK : InputEvent.BUTTON1_MASK;
					MouseEvent evt = new MouseEvent(this, MouseEvent.MOUSE_PRESSED, when, modifiers | buttonMask,
							x, y, 1, extra == 3);
					getMouseHandler().mousePressed(evt);
					break;
				}
				case NativeGL.INPUT_MOUSE_UP: {
					int buttonMask = extra == 3 ? InputEvent.BUTTON3_MASK : InputEvent.BUTTON1_MASK;
					MouseEvent evt = new MouseEvent(this, MouseEvent.MOUSE_RELEASED, when, modifiers | buttonMask,
							x, y, 1, false);
					getMouseHandler().mouseReleased(evt);
					break;
				}
				case NativeGL.INPUT_KEY_DOWN: {
					if (extra == KeyEvent.VK_SHIFT) {
						glShiftDown = true;
					}
					if (extra == KeyEvent.VK_CONTROL) {
						glControlDown = true;
					}
					KeyEvent evt = new KeyEvent(this, KeyEvent.KEY_PRESSED, when, modifiers, extra,
							KeyEvent.CHAR_UNDEFINED);
					getKeyHandler().keyPressed(evt);
					break;
				}
				case NativeGL.INPUT_KEY_UP: {
					if (extra == KeyEvent.VK_SHIFT) {
						glShiftDown = false;
					}
					if (extra == KeyEvent.VK_CONTROL) {
						glControlDown = false;
					}
					KeyEvent evt = new KeyEvent(this, KeyEvent.KEY_RELEASED, when, modifiers, extra,
							KeyEvent.CHAR_UNDEFINED);
					getKeyHandler().keyReleased(evt);
					break;
				}
				case NativeGL.INPUT_CHAR: {
					// keyPressed(), not keyTyped(): this engine's actual text
					// entry (login fields, chat) reads getKeyChar() inside
					// keyPressed - see KeyHandler.keyPressed(). keyTyped()
					// here only updates shift/control state, already covered
					// by the KEY_DOWN/KEY_UP cases above.
					KeyEvent evt = new KeyEvent(this, KeyEvent.KEY_PRESSED, when, modifiers, KeyEvent.VK_UNDEFINED,
							(char) extra);
					getKeyHandler().keyPressed(evt);
					break;
				}
				default:
					break;
			}
		}
	}

	/** Updates the rendering scalar and resizes the window accordingly */
	private static void updateRenderingScalarAndResize(float scalar, int newWidth, int newHeight) {
		int imageType = ScaledWindow.getBufferedImageType();

		// Reset the game image with the current type to ensure that affineOp
		// scaling will always have matching source and destination types
		game_image = new BufferedImage(newWidth, newHeight, imageType);

		// Handle rendering scalar value changes
		orsc.mudclient.renderingScalar = scalar;

		// Resize window only after it has begun rendering the game image,
		// (ie. not the loading screen)
		if (scaledWindow.isViewportLoaded()) {
			scaledWindow.resizeWindowToScalar();
		}
	}

	public void close() {
		stop();
	}

	public String getCacheLocation() {
		return "../OpenRSC/";
	}

	public Sprite getBattery(int level) {
		// This would be needed to be implemented if was desired to display Battery Status Icon
		return null;
	}

	public int getBatteryPercent() {
		// This would be needed to be implemented if was desired to display Battery Percent
		return 100;
	}

	public boolean getBatteryCharging() {
		// This would be needed to be implemented if was desired to display Battery Charging
		return false;
	}

	public Sprite getConnectivity(int level) {
		// This would be needed to be implemented if was desired to display Network Connectivity Status Icon
		return null;
	}

	public String getConnectivityText() {
		// This would be needed to be implemented if was desired to display Network Connectivity Status Text
		return null;
	}

	public void resized() {
		int newWidth = mudclient.getSurface().width2;
		int newHeight = mudclient.getSurface().height2;

		if (imageProducer != null) {
			imageProducer.setDimensions(newWidth, newHeight);
		}
		initGraphics();

		game_image = new BufferedImage(newWidth, newHeight, ScaledWindow.getBufferedImageType());
		g2dForGameImage = game_image.createGraphics();
	}

	public Sprite getSpriteFromByteArray(ByteArrayInputStream byteArrayInputStream) {
		try {
			// ImageIO added in Java 1.4; decode via Toolkit + MediaTracker instead
			byte[] imageBytes = new byte[byteArrayInputStream.available()];
			byteArrayInputStream.read(imageBytes);
			Image rawImage = Toolkit.getDefaultToolkit().createImage(imageBytes);
			MediaTracker tracker = new MediaTracker(this);
			tracker.addImage(rawImage, 0);
			tracker.waitForID(0);

			int captchaWidth = rawImage.getWidth(this);
			int captchaHeight = rawImage.getHeight(this);

			BufferedImage image = new BufferedImage(captchaWidth, captchaHeight, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g2d = image.createGraphics();
			g2d.drawImage(rawImage, 0, 0, this);
			g2d.dispose();

			int[] pixels = new int[image.getWidth() * image.getHeight()];
			for (int y = 0; y < image.getHeight(); y++)
				for (int x = 0; x < image.getWidth(); x++) {
					int rgb = image.getRGB(x, y);
					pixels[x + y * image.getWidth()] = rgb;
				}

			Sprite sprite = new Sprite(pixels, captchaWidth, captchaHeight);
			sprite.setSomething(captchaWidth, captchaHeight);
			sprite.setShift(0, 0);
			sprite.setRequiresShift(false);
			return sprite;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	public void drawKeyboard() {
	}

	public void closeKeyboard() {
	}

	public void playSound(byte[] soundData, int offset, int dataLength) {
		throw new UnsupportedOperationException("Not supported yet.");
	}

	public void stopSoundPlayer() {
		throw new UnsupportedOperationException("Not supported yet.");
	}

	public void setTitle(String title) {
	}

	public void setIconImage(String serverName) {

	}

	public boolean saveHideIp(int preference) {
		return ClientPortHelper.saveHideIp(preference);
	}

	public int loadHideIp() {
		return ClientPortHelper.loadHideIp();
	}

	public boolean saveCredentials(String creds) {
		return ClientPortHelper.saveCredentials(creds);
	}

	public String loadCredentials() {
		return ClientPortHelper.loadCredentials();
	}

	public String loadIP() {
		return ClientPortHelper.loadIP();
	}

	public int loadPort() {
		return ClientPortHelper.loadPort();
	}

	public class MouseHandler implements MouseListener, MouseMotionListener {
		public final void mouseClicked(MouseEvent var1) {
			try {
				updateControlShiftState(var1);
			} catch (RuntimeException var3) {
				throw GenUtil.makeThrowable(var3, "e.mouseClicked(" + (var1 != null ? "{...}" : "null") + ')');
			}
		}

		public final synchronized void mousePressed(MouseEvent var1) {
			try {
				if ((var1.getModifiers() & InputEvent.BUTTON2_MASK) != 0) { // getButton()/BUTTON2 const added in Java 1.4
					mudclient.mouseLastProcessedX = mudclient.mouseX;
					mudclient.mouseLastProcessedY = mudclient.mouseY;
					return;
				}
				updateControlShiftState(var1);
				mudclient.mouseX = var1.getX() - mudclient.screenOffsetX;
				mudclient.mouseY = var1.getY() - mudclient.screenOffsetY;

				if (!SwingUtilities.isRightMouseButton(var1)) mudclient.currentMouseButtonDown = 1;
				else mudclient.currentMouseButtonDown = 2;

				mudclient.lastMouseButtonDown = mudclient.currentMouseButtonDown;
				mudclient.lastMouseAction = 0;
				mudclient.addMouseClick(mudclient.currentMouseButtonDown, mudclient.mouseX, mudclient.mouseY);
			} catch (RuntimeException var3) {
				throw GenUtil.makeThrowable(var3, "e.mousePressed(" + (var1 != null ? "{...}" : "null") + ')');
			}
		}

		public final synchronized void mouseReleased(MouseEvent var1) {
			try {
				if ((var1.getModifiers() & InputEvent.BUTTON2_MASK) != 0) { // getButton()/BUTTON2 const added in Java 1.4
					mudclient.mouseLastProcessedX = 0;
					mudclient.mouseLastProcessedY = 0;
					return;
				}
				updateControlShiftState(var1);
				mudclient.mouseX = var1.getX() - mudclient.screenOffsetX;
				mudclient.mouseY = var1.getY() - mudclient.screenOffsetY;
				mudclient.currentMouseButtonDown = 0;
			} catch (RuntimeException var3) {
				throw GenUtil.makeThrowable(var3, "e.mouseReleased(" + (var1 != null ? "{...}" : "null") + ')');
			}
		}

		public final void mouseEntered(MouseEvent var1) {
			try {
				updateControlShiftState(var1);
			} catch (RuntimeException var3) {
				throw GenUtil.makeThrowable(var3, "e.mouseEntered(" + (var1 != null ? "{...}" : "null") + ')');
			}
		}

		public final void mouseExited(MouseEvent var1) {
			try {
				updateControlShiftState(var1);
			} catch (RuntimeException var3) {
				throw GenUtil.makeThrowable(var3, "e.mouseExited(" + (var1 != null ? "{...}" : "null") + ')');
			}
		}

		public final synchronized void mouseDragged(MouseEvent var1) {
			try {
				updateControlShiftState(var1);
				mudclient.mouseX = var1.getX() - mudclient.screenOffsetX;
				mudclient.mouseY = var1.getY() - mudclient.screenOffsetY;

				if (mudclient.mouseLastProcessedX != 0 && mudclient.mouseLastProcessedY != 0) {
					int distanceX = (mudclient.mouseX - mudclient.mouseLastProcessedX)/2;
					int distanceY = (mudclient.mouseY - mudclient.mouseLastProcessedY)/2;
					boolean touchedMessagePanelArea = mudclient.getGameHeight() - Math.max(mudclient.mouseY, mudclient.mouseLastProcessedY) <= 66;

					boolean scrollableMessagePanel = mudclient.hasScroll(mudclient.messageTabSelected) && touchedMessagePanelArea;
					boolean mayBeScrollable = mudclient.showUiTab != 0;
					boolean zoomable = (!scrollableMessagePanel && !mayBeScrollable) || osConfig.C_SWIPE_TO_SCROLL_MODE == 0;

					if (!mudclient.isInFirstPersonView() && zoomable && (Config.S_ZOOM_VIEW_TOGGLE || mudclient.getLocalPlayer().isStaff()) && !var1.isControlDown()) {
						if (osConfig.C_SWIPE_TO_ZOOM_MODE != 0) {
							int dir = osConfig.C_SWIPE_TO_ZOOM_MODE == 2 ? -1 : 1;
							int newZoom = osConfig.C_LAST_ZOOM + dir * distanceY;
							// Keep C_LAST_ZOOM aka the zoom increments on the range of [0, 255]
							if (newZoom >= 0 && newZoom <= 255) {
								osConfig.C_LAST_ZOOM = newZoom;
							}
						}
					} else if (mudclient.isInFirstPersonView() && mudclient.cameraAllowPitchModification) {
						mudclient.cameraPitch = (mudclient.cameraPitch + (-distanceY * 2)) & 1023;

						// Limit on the half circled where everything is right side up
						if (mudclient.cameraPitch > 256 && mudclient.cameraPitch <= 512)
							mudclient.cameraPitch = 256;

						if (mudclient.cameraPitch < 768 && mudclient.cameraPitch > 512)
							mudclient.cameraPitch = 768;
					}
					if (osConfig.C_SWIPE_TO_ROTATE_MODE != 0) {
						// camera set to auto does not like manual like rotation
						if (!mudclient.getOptionCameraModeAuto()) {
							int dir = osConfig.C_SWIPE_TO_ROTATE_MODE == 2 ? -1 : 1;
							float clientDist = distanceX / (getWidth() / (float) mudclient.getGameWidth());
							mudclient.cameraRotation = (255 & mudclient.cameraRotation + (int) (dir * clientDist));
						} else {
							// swipe to left gives negative distanceX, to left negative
							int dir = osConfig.C_SWIPE_TO_ROTATE_MODE == 2 ? -1 : 1;
							boolean toLeft = dir * distanceX < 0;
							if (toLeft) {
								mudclient.keyLeft = true;
							} else {
								mudclient.keyRight = true;
							}
						}
					}
					if (!zoomable) {
						if (osConfig.C_SWIPE_TO_SCROLL_MODE != 0) {
							int dir = osConfig.C_SWIPE_TO_SCROLL_MODE == 2 ? -1 : 1;
							mudclient.runScroll(dir * distanceY);
						}
					}

					// To make the mouse move:
					//mudclient.mouseLastProcessedX = mudclient.mouseX;
					//mudclient.mouseLastProcessedY = mudclient.mouseY;

					// Move the mouse back to the last processed position.
					// Robot mouse move removed (MouseInfo is Java 5+)
				}
				if (SwingUtilities.isRightMouseButton(var1)) mudclient.currentMouseButtonDown = 2;
				else mudclient.currentMouseButtonDown = 1;
			} catch (RuntimeException var3) {
				throw GenUtil.makeThrowable(var3, "e.mouseDragged(" + (var1 != null ? "{...}" : "null") + ')');
			}
		}

		public final synchronized void mouseMoved(MouseEvent var1) {
			try {
				updateControlShiftState(var1);
				mudclient.mouseX = var1.getX() - mudclient.screenOffsetX;
				mudclient.mouseY = var1.getY() - mudclient.screenOffsetY;
				mudclient.lastMouseAction = 0;
				mudclient.currentMouseButtonDown = 0;
			} catch (RuntimeException var3) {
				throw GenUtil.makeThrowable(var3, "e.mouseMoved(" + (var1 != null ? "{...}" : "null") + ')');
			}
		}

		// mouseWheelMoved()/MouseWheelEvent added in Java 1.4; no scroll-wheel
		// support (zoom or menu scrolling) pre-1.4
	}

	public class KeyHandler implements KeyListener {

		public final void keyTyped(KeyEvent var1) {
			try {
				updateControlShiftState(var1);
			} catch (RuntimeException var3) {
				throw GenUtil.makeThrowable(var3, "e.keyTyped(" + (var1 != null ? "{...}" : "null") + ')');
			}
		}

		public final synchronized void keyPressed(KeyEvent var1) {
			try {
				updateControlShiftState(var1);
				char keyChar = var1.getKeyChar();
				int keyCode = var1.getKeyCode();
				boolean hitInputFilter = false;
				mudclient.handleKeyPress((byte) 126, (int) keyChar);
				mudclient.lastMouseAction = 0;

				if (keyCode == 112) mudclient.interlace = !mudclient.interlace;
				if (keyCode == 113) Config.C_SIDE_MENU_OVERLAY = !Config.C_SIDE_MENU_OVERLAY;
				if (keyCode == KeyEvent.VK_F3) osConfig.C_LAST_ZOOM = 75;
				if (keyCode == KeyEvent.VK_F4) mudclient.toggleFirstPersonView();
				if (keyCode == KeyEvent.VK_F10) mudclient.cycleScalingType(); // type
				if (keyCode == KeyEvent.VK_F11) mudclient.scaleDown(); // scale down
				if (keyCode == KeyEvent.VK_F12) mudclient.scaleUp(); // scale up
				if (keyCode == 39) mudclient.keyRight = true;
				if (keyCode == 37) mudclient.keyLeft = true;
				if (keyCode == 13 || keyCode == 10) mudclient.enterPressed = true;
				if (keyCode == KeyEvent.VK_UP) mudclient.keyUp = true;
				if (keyCode == KeyEvent.VK_DOWN) mudclient.keyDown = true;
				if (keyCode == KeyEvent.VK_PAGE_DOWN) mudclient.pageDown = true;
				if (keyCode == KeyEvent.VK_PAGE_UP) mudclient.pageUp = true;

				for (int var5 = 0; var5 < Fonts.inputFilterChars.length(); ++var5)
					if (Fonts.inputFilterChars.charAt(var5) == keyChar) {
						hitInputFilter = true;
						break;
					}

				if (hitInputFilter && mudclient.inputTextCurrent.length() < 20)
					mudclient.inputTextCurrent = mudclient.inputTextCurrent + keyChar;

				if (hitInputFilter && mudclient.chatMessageInput.length() < 80 && !mudclient.getIsSleeping())
					mudclient.chatMessageInput = mudclient.chatMessageInput + keyChar;

				// Backspace
				if (keyChar == '\b' && mudclient.inputTextCurrent.length() > 0)
					mudclient.inputTextCurrent = mudclient.inputTextCurrent.substring(0,
						mudclient.inputTextCurrent.length() - 1);

				// Backspace
				if (keyChar == '\b' && mudclient.chatMessageInput.length() > 0)
					mudclient.chatMessageInput = mudclient.chatMessageInput.substring(0,
						mudclient.chatMessageInput.length() - 1);

				if (keyChar == '\n' || keyChar == '\r') {
					mudclient.inputTextFinal = mudclient.inputTextCurrent;
					mudclient.chatMessageInputCommit = mudclient.chatMessageInput;
				}
			} catch (RuntimeException var6) {
				throw GenUtil.makeThrowable(var6, "e.keyPressed(" + (var1 != null ? "{...}" : "null") + ')');
			}
		}

		public final synchronized void keyReleased(KeyEvent var1) {
			try {
				updateControlShiftState(var1);
				char c = var1.getKeyChar();
				int keyCode = var1.getKeyCode();

				if (keyCode == 39) mudclient.keyRight = false;
				if (keyCode == 37) mudclient.keyLeft = false;
				if (keyCode == KeyEvent.VK_UP) mudclient.keyUp = false;
				if (keyCode == KeyEvent.VK_DOWN) mudclient.keyDown = false;
				if (keyCode == KeyEvent.VK_PAGE_DOWN) mudclient.pageDown = false;
				if (keyCode == KeyEvent.VK_PAGE_UP) mudclient.pageUp = false;

				if (keyCode == KeyEvent.VK_ALT) {
					mudclient.mouseLastProcessedX = 0;
					mudclient.mouseLastProcessedY = 0;
				}
			} catch (RuntimeException var4) {
				throw GenUtil.makeThrowable(var4, "e.keyReleased(" + (var1 != null ? "{...}" : "null") + ')');
			}
		}
	}
}
