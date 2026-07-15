package orsc.graphics.gui;

import orsc.util.SimpleList;

public class KillAnnouncerQueue {

	public SimpleList Kill = new SimpleList();

	public void addKill(KillAnnouncer kill) {
		try {
			Kill.add(0, kill);
			if (Kill.size() >= 10) {
				Kill.remove(Kill.size() - 1);

			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void clean() {
		try {
			for (int i = Kill.size() - 1; i >= 0; i--) {
				KillAnnouncer k = (KillAnnouncer) Kill.get(i);
				if (System.currentTimeMillis() - k.displayTime > 8000) {
					Kill.remove(i);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
