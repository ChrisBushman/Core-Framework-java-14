package orsc.enumerations;

import orsc.util.GenUtil;

public final class GameModeWhat {
	public static final GameModeWhat WIP = new GameModeWhat("WIP", 2);
	public static final GameModeWhat RC = new GameModeWhat("RC", 1);
	public static final GameModeWhat LIVE = new GameModeWhat("LIVE", 0);

	public final int val;

	private GameModeWhat(String name, int val) {
		try {
			this.val = val;
		} catch (RuntimeException var4) {
			throw GenUtil.makeThrowable(var4, "i.<init>(" + (name != null ? "{...}" : "null") + ',' + val + ')');
		}
	}

	public static GameModeWhat lookupModeWhat(int val) {
		try {
			GameModeWhat[] var2 = gameModesWhat();
			for (int i = 0; i < var2.length; i++) {
				if (var2[i].val == val) {
					return var2[i];
				}
			}
			return null;
		} catch (RuntimeException var5) {
			throw GenUtil.makeThrowable(var5, "u.C(" + "dummy" + ',' + val + ')');
		}
	}

	public static GameModeWhat[] gameModesWhat() {
		try {
			return new GameModeWhat[]{GameModeWhat.LIVE, GameModeWhat.RC, GameModeWhat.WIP};
		} catch (RuntimeException var2) {
			throw GenUtil.makeThrowable(var2, "gb.H(" + "dummy" + ')');
		}
	}

	public final String toString() {
		try {
			throw new IllegalStateException();
		} catch (RuntimeException var2) {
			throw GenUtil.makeThrowable(var2, "i.toString()");
		}
	}
}
