package orsc;

import orsc.util.Utils;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

public class OpenRSC extends ORSCApplet {

	public static OpenRSC applet;
	private static final long serialVersionUID = 1L;

	public static void main(String[] args) {
		File scalingSettings = new File("./clientSettings.conf");
		if (scalingSettings.exists()) {
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

		scaledWindow = ScaledWindow.getInstance();
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				OpenRSC.createAndShowGUI();
			}
		});
	}

	public static void createAndShowGUI() {
		try {
			applet = new OpenRSC();

			scaledWindow.getContentPane().setLayout(new BorderLayout());
			scaledWindow.getContentPane().add(applet);
			scaledWindow.setResizable(false);
			scaledWindow.setBackground(Color.black);
			scaledWindow.setTitle(Config.WINDOW_TITLE);
			scaledWindow.setIconImage(Utils.getImage("icon.png").getImage());

			applet.init();
			applet.start();

			scaledWindow.launchScaledWindow();

			applet.resizeMudclient(512, 346);
		} catch (RuntimeException e) {
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
		throw new RuntimeException("Not supported yet.");
	}

	public void stopSoundPlayer() {
		throw new RuntimeException("Not supported yet.");
	}

	public boolean getResizable() {
		return Config.allowResize1();
	}
}
