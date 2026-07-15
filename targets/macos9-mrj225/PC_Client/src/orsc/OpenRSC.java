package orsc;

import orsc.util.Utils;

import java.awt.*;
import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

public class OpenRSC extends ORSCApplet {

	public static OpenRSC applet;
	private static final long serialVersionUID = 1L;

	// Prints and flushes immediately - JBindery's redirected stdout file is
	// otherwise buffered, so a hang (not a crash) can look identical to no
	// output ever having been written at all.
	private static void log(String message) {
		System.out.println(message);
		System.out.flush();
	}

	public static void main(String[] args) {
		log("main() entered");
		try {
			File scalingSettings = new File("./clientSettings.conf");
			if (scalingSettings.exists()) {
				log("loading clientSettings.conf");
				Properties props = new Properties();

				FileInputStream in14 = null;
				try {
					in14 = new FileInputStream(scalingSettings.getAbsolutePath());
					props.load(in14);

					// Load scaling settings. No runtime image scaling on this platform,
					// so only the stored scalingType is restored, not the scalar itself.
					String scalingTypeString = props.getProperty("scaling_type");
					if (scalingTypeString != null && scalingTypeString.length() > 0) {
						int scalingTypeOrdinal = Integer.parseInt(scalingTypeString);
						mudclient.scalingType = ScaledWindow.ScalingAlgorithm.VALUES[scalingTypeOrdinal];
					}
				} catch (Exception e) {
					System.out.println("Something went wrong loading scaling settings");
					e.printStackTrace();
				} finally {
					if (in14 != null) try { in14.close(); } catch (Exception _e) {}
				}
			}

			log("calling ScaledWindow.getInstance()");
			scaledWindow = ScaledWindow.getInstance();
			log("ScaledWindow.getInstance() returned");
			// Calling this directly rather than via SwingUtilities.invokeLater():
			// if the event-dispatch thread never picks up a queued Runnable on
			// this platform, main() would return having scheduled nothing visible
			// and the process would just sit there with no window - exactly the
			// silent-failure mode this is guarding against.
			OpenRSC.createAndShowGUI();
			log("createAndShowGUI() returned");
		} catch (Throwable t) {
			// Mac OS 9 has no terminal, so a stack trace on stderr is invisible
			// here - show it in a plain AWT window instead (not Swing, in case
			// Swing/mac.jar setup itself is what's failing).
			log("main() caught: " + t);
			showFatalError(t);
		}
	}

	public static void createAndShowGUI() {
		try {
			// Show the window immediately, with the title updated before each
			// step below - if startup hangs rather than throwing, whichever
			// title is showing when it freezes tells us exactly where.
			log("setting starting title + showing window");
			scaledWindow.setTitle("OpenRSC - starting...");
			scaledWindow.setSize(400, 100);
			scaledWindow.setVisible(true);
			log("window shown");

			applet = new OpenRSC();
			scaledWindow.setLayout(new BorderLayout());
			scaledWindow.add(applet);
			scaledWindow.setResizable(false);
			scaledWindow.setBackground(Color.black);

			log("loading icon");
			scaledWindow.setTitle("OpenRSC - loading icon...");
			scaledWindow.setIconImage(Utils.getImage("icon.png").getImage());
			log("icon loaded");

			log("calling applet.init()");
			scaledWindow.setTitle("OpenRSC - initializing client...");
			applet.init();
			log("applet.init() returned");

			log("calling applet.start()");
			scaledWindow.setTitle("OpenRSC - starting client thread...");
			applet.start();
			log("applet.start() returned");

			scaledWindow.setTitle(Config.WINDOW_TITLE);
			scaledWindow.launchScaledWindow();
			// Mouse events go to whatever's under the cursor regardless of
			// focus, but keyboard events only go to the focused component -
			// request it explicitly (also re-requested on windowActivated).
			applet.requestFocus();

			applet.resizeMudclient(512, 346);
			log("createAndShowGUI() finished normally");
		} catch (Throwable t) {
			log("createAndShowGUI() caught: " + t);
			showFatalError(t);
		}
	}

	/** Plain AWT (not Swing) so it can still show up even if Swing setup is what failed. */
	private static void showFatalError(Throwable t) {
		t.printStackTrace();

		java.io.StringWriter sw = new java.io.StringWriter();
		t.printStackTrace(new java.io.PrintWriter(sw));

		Frame errorFrame = new Frame("OpenRSC failed to start");
		TextArea textArea = new TextArea(sw.toString(), 20, 70, TextArea.SCROLLBARS_VERTICAL_ONLY);
		textArea.setEditable(false);
		errorFrame.add(textArea, BorderLayout.CENTER);

		Button dismiss = new Button("Quit");
		dismiss.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent e) {
				System.exit(1);
			}
		});
		Panel buttonPanel = new Panel();
		buttonPanel.add(dismiss);
		errorFrame.add(buttonPanel, BorderLayout.SOUTH);

		errorFrame.pack();
		Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
		Dimension frameSize = errorFrame.getSize();
		errorFrame.setLocation((screenSize.width - frameSize.width) / 2, (screenSize.height - frameSize.height) / 2);
		errorFrame.setVisible(true);
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
		throw new RuntimeException("Not supported yet.");
	}

	public void stopSoundPlayer() {
		throw new RuntimeException("Not supported yet.");
	}

	public boolean getResizable() {
		return Config.allowResize1();
	}
}
