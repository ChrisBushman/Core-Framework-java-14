package orsc;

import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Plain AWT Frame host for the game applet on classic Mac OS 9 / MRJ 2.2.5.
 *
 * Deliberately not a Swing JFrame: ORSCApplet is a heavyweight AWT component
 * (extends Applet), and hosting a heavyweight component inside a JFrame's
 * lightweight contentPane hierarchy was a classically buggy combination in
 * early Swing (1.1/1.2 especially) - mouse events still worked (delivered by
 * absolute screen-coordinate hit-testing) but keyboard focus transfer across
 * that boundary failed silently on real MRJ hardware. Since no custom L&F is
 * even applied here, JFrame wasn't buying anything anyway.
 *
 * No runtime image scaling here (unlike the Windows/modern-JVM build) since
 * MRJ has no java.awt.image.BufferedImage/Graphics2D at all (added in Java
 * 1.2), and a fixed-resolution classic Mac display doesn't need it anyway.
 * ORSCApplet paints its own produced Image directly onto itself.
 */
public class ScaledWindow extends Frame {

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
		setBackground(Color.black);

		// windowActivated() re-requests focus for the applet each time - mouse
		// events go to whatever's under the cursor regardless of focus, but
		// keyboard events only go to the focused component.
		addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				System.exit(0);
			}

			public void windowActivated(WindowEvent e) {
				if (getComponentCount() > 0) {
					getComponent(0).requestFocus();
				}
			}
		});
	}

	public static ScaledWindow getInstance() {
		if (instance == null) {
			instance = new ScaledWindow();
		}
		return instance;
	}

	public void launchScaledWindow() {
		pack();
		Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
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
