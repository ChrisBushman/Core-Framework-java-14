package orsc.util;

/**
 * java.util.Timer/TimerTask were added in Java 1.3 - not present in MRJ
 * 2.2.5's JDK 1.1 base. Minimal schedule(Runnable, delay, period)/cancel()
 * replacement backed by a plain daemon Thread.
 */
public class SimpleTimer {
	private Thread thread;
	private volatile boolean cancelled = false;

	public void schedule(final Runnable task, final long delay, final long period) {
		thread = new Thread() {
			public void run() {
				try {
					Thread.sleep(delay);
					while (!cancelled) {
						task.run();
						Thread.sleep(period);
					}
				} catch (InterruptedException e) {
					// cancelled
				}
			}
		};
		thread.setDaemon(true);
		thread.start();
	}

	public void cancel() {
		cancelled = true;
		if (thread != null) {
			thread.interrupt();
		}
	}
}
