package orsc.graphics.gui;

import java.util.LinkedList;

public class KillAnnouncerQueue {

	public LinkedList Kill = new LinkedList();

	public void addKill(KillAnnouncer kill) {
		try {
			Kill.addFirst(kill);
			if (Kill.size() >= 10) {
				Kill.removeLast();

			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void clean() {
		try {
			{ java.util.Iterator _it = Kill.iterator(); while (_it.hasNext()) { KillAnnouncer k = (KillAnnouncer) _it.next();
				if (System.currentTimeMillis() - k.displayTime > 8000) {
					_it.remove();
				}
			}}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}

