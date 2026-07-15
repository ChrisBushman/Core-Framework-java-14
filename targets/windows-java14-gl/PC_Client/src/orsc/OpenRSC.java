package orsc;

import orsc.util.Utils;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

public class OpenRSC extends ORSCApplet {

	public static OpenRSC applet;
	static JFrame jframe;
	private static final long serialVersionUID = 1L;

	public static void main(String[] args) {
		// Force AWT/AppKit to bootstrap here on the main thread, before any code
		// touches SwingUtilities.invokeAndWait() (which ScaledWindow's constructor
		// does immediately). On old Apple JVMs, the first-ever AWT initialization
		// has thread affinity to whichever thread triggers it, and the runtime
		// tries to hand that bootstrap back to the main thread - if that first
		// touch instead happens via invokeAndWait() called from main(), main()
		// blocks waiting on the EDT while AWT-init on the EDT blocks waiting to
		// sync back to the (unavailable) main thread, deadlocking inside pack().
		Toolkit.getDefaultToolkit();

		// MUST do this before anything else runs in order to override OS-level dpi settings
		// (not applicable to macOS, which implements OS-scaling in a different fashion)
		if (!Utils.isMacOS()) {
			// Disable OS-level scaling in all JREs > 8
			System.setProperty("sun.java2d.uiScale.enabled", "false");
			System.setProperty("sun.java2d.uiScale", "1");

			// Required for newer versions of Oracle 8 to disable OS-level scaling
			System.setProperty("sun.java2d.dpiaware", "true");

			// Linux / other
			if (!Utils.isWindowsOS()) {
				System.setProperty("GDK_SCALE", "1");
			}
		}

		if (Utils.isMacOS()) {
			// Note: Only works on some Java 8 implementations
			System.setProperty("apple.awt.application.appearance", "system");
		}

		File scalingSettings = new File("./clientSettings.conf");
		if (scalingSettings.exists()) {
			Properties props = new Properties();

			FileInputStream in14 = null;
			try {
				in14 = new FileInputStream(scalingSettings.getAbsolutePath());
				props.load(in14);

				// Load scaling settings
				String scalingTypeString = props.getProperty("scaling_type");
				String scalarString = props.getProperty("scaling_scalar");
				if (scalingTypeString != null && scalingTypeString.length() > 0) {
					int scalingTypeOrdinal = Integer.parseInt(scalingTypeString);
					mudclient.scalingType = ScaledWindow.ScalingAlgorithm.VALUES[scalingTypeOrdinal];
				}
				if (scalarString != null && scalarString.length() > 0) {
					ORSCApplet.oldRenderingScalar = mudclient.renderingScalar;
					mudclient.newRenderingScalar = Float.parseFloat(scalarString);
				}
			} catch (Exception e) {
				System.out.println("Something went wrong loading scaling settings");
				e.printStackTrace();
			} finally {
				if (in14 != null) try { in14.close(); } catch (Exception _e) {}
			}
		}

		scaledWindow = ScaledWindow.getInstance();
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				OpenRSC.createAndShowGUI();
			}
		});
	}

	public static void createAndShowGUI() {
		try {
			jframe = new JFrame(Config.getServerNameWelcome());
			applet = new OpenRSC();
			// Here we add 12 because 12 was added back in 2009 for the skip tutorial line.
			// applet.setPreferredSize(new Dimension(512, 334 + 12)); // Java 1.5+
			jframe.getContentPane().setLayout(new BorderLayout());
			jframe.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
			jframe.setIconImage(Utils.getImage("icon.png").getImage());
			jframe.setTitle(Config.WINDOW_TITLE);
			jframe.getContentPane().add(applet);
			jframe.setResizable(true); // true or false based on server sent config
			jframe.setVisible(false); // All rendering is forwarded to the ScaledWindow class
			jframe.setBackground(Color.black);
			// Just like above, here we add 12 because 12 was added back in 2009 for the skip tutorial line.
			// jframe.setMinimumSize(new Dimension(512, 334 + 12)); // Java 1.5+
			jframe.pack();
			jframe.setLocationRelativeTo(null);
			applet.init();
			applet.start();

			// The GL renderer's own native window (see GLSceneRenderer,
			// ORSCApplet.draw()/pollGLInput()) is fully interactive on its
			// own as of Phase 5 - no need for this Swing window too. Only
			// skip *showing* it: scaledWindow itself still needs to exist,
			// since draw() calls setGameImage() on it every frame
			// regardless of which renderer is active.
			if (!"gl".equals(System.getProperty("orsc.renderer"))) {
				scaledWindow.launchScaledWindow();
			}

			applet.resizeMudclient(512, 346);
		} catch (HeadlessException e) {
			e.printStackTrace();
		}
	}

	public void setTitle(String title) {
		scaledWindow.setTitle(title);
	}

	public void setIconImage(String serverName) {
		if ("RSC Coleslaw".equals(serverName)) {
			scaledWindow.setIconImage(Utils.getImage("coleslaw.icon.png").getImage());
		} else if ("RSC Uranium".equals(serverName)) {
			scaledWindow.setIconImage(Utils.getImage("uranium.icon.png").getImage());
		} else if ("RSC Cabbage".equals(serverName)) {
			scaledWindow.setIconImage(Utils.getImage("cabbage.icon.png").getImage());
		} else if ("OpenPK".equals(serverName)) {
			scaledWindow.setIconImage(Utils.getImage("openpk.icon.png").getImage());
		} else {
			scaledWindow.setIconImage(Utils.getImage("icon.png").getImage());
		}
	}

	public String getCacheLocation() {
		return Config.F_CACHE_DIR + File.separator;
	}

	public void playSound(byte[] soundData, int offset, int dataLength) {
		throw new UnsupportedOperationException("Not supported yet.");
	}

	public void stopSoundPlayer() {
		throw new UnsupportedOperationException("Not supported yet.");
	}

	public boolean getResizable() {
		return Config.allowResize1();
	}
}
