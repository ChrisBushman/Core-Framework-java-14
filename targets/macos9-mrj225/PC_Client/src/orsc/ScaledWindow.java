package orsc;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Plain JFrame host for the game applet on classic Mac OS 9 / MRJ 2.2.5.
 * No runtime image scaling here (unlike the Windows/modern-JVM build) since
 * MRJ has no java.awt.image.BufferedImage/Graphics2D at all (added in Java
 * 1.2), and a fixed-resolution classic Mac display doesn't need it anyway.
 * ORSCApplet paints its own produced Image directly onto itself.
 */
public class ScaledWindow extends JFrame {

	private static ScaledWindow instance = null;

	/**
	 * All possible types of scaling supported by the client on other platforms.
	 * mudclient.java still tracks a scalingType field and lets the player cycle
	 * it via F10; kept here purely so that state compiles and round-trips -
	 * nothing on this platform acts on it since there's no scaling renderer.
	 */
	public static final class ScalingAlgorithm {
		public static final ScalingAlgorithm INTEGER_SCALING = new ScalingAlgorithm(0);
		public static final ScalingAlgorithm BILINEAR_INTERPOLATION = new ScalingAlgorithm(1);
		public static final ScalingAlgorithm BICUBIC_INTERPOLATION = new ScalingAlgorithm(2);

		public static final ScalingAlgorithm[] VALUES = {
			INTEGER_SCALING, BILINEAR_INTERPOLATION, BICUBIC_INTERPOLATION
		};

		private final int ordinal;

		private ScalingAlgorithm(int ordinal) {
			this.ordinal = ordinal;
		}

		public int ordinal() {
			return ordinal;
		}
	}

	private ScaledWindow() {
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (Exception e) {
			System.out.println("Unable to set L&F: " + e);
		}

		setBackground(Color.black);

		// JFrame.EXIT_ON_CLOSE doesn't exist in Swing 1.1.1 (added in a later
		// Swing/JDK revision), so exit is wired up manually here instead.
		addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				System.exit(0);
			}
		});
	}

	public static ScaledWindow getInstance() {
		if (instance == null) {
			synchronized (ScaledWindow.class) {
				instance = new ScaledWindow();
			}
		}
		return instance;
	}

	public void launchScaledWindow() {
		pack();
		Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
		// Component.getWidth()/getHeight() were added in Java 1.2; getSize() is the pre-1.2 equivalent
		Dimension ownSize = getSize();
		setLocation((screenSize.width - ownSize.width) / 2, (screenSize.height - ownSize.height) / 2);
		setVisible(true);
	}

	/** No runtime image scaling on this platform; kept as a no-op for API compatibility. */
	public void resizeWindowToScalar() {
	}

	/** No runtime image scaling on this platform; kept as a no-op for API compatibility. */
	public void validateAppletSize() {
	}

	public boolean isViewportLoaded() {
		return true;
	}
}
