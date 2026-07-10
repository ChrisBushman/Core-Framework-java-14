package orsc.enumerations;

import orsc.util.GenUtil;

public final class GameModeWhere {
	public static final GameModeWhere OFFICE_BETA = new GameModeWhere("INTBETA", "office", "_intbeta", 6);
	public static final GameModeWhere OFFICE_WTI = new GameModeWhere("WTI", "office", "_wti", 5);
	public static final GameModeWhere LOCAL = new GameModeWhere("LOCAL", "", "local", 4);
	public static final GameModeWhere OFFICE_WIP = new GameModeWhere("WTWIP", "office", "_wip", 3);
	public static final GameModeWhere OFFICE_QA = new GameModeWhere("WTQA", "office", "_qa", 2);
	public static final GameModeWhere OFFICE_RC = new GameModeWhere("WTRC", "office", "_rc", 1);
	public static final GameModeWhere LIVE = new GameModeWhere("LIVE", "", "", 0);

	public final int val;

	private GameModeWhere(String var1, String var2, String var3, int val) {
		try {
			this.val = val;
		} catch (RuntimeException var6) {
			throw GenUtil.makeThrowable(var6, "v.<init>(" + (var1 != null ? "{...}" : "null") + ','
				+ (var2 != null ? "{...}" : "null") + ',' + (var3 != null ? "{...}" : "null") + ',' + val + ')');
		}
	}

	public static boolean validGameModeWhere(GameModeWhere mode) {
		try {
			return OFFICE_RC == mode || OFFICE_QA == mode || OFFICE_WIP == mode
				|| mode == OFFICE_WTI || OFFICE_BETA == mode;
		} catch (RuntimeException var3) {
			throw GenUtil.makeThrowable(var3, "ia.A(" + (mode != null ? "{...}" : "null") + ',' + "dummy" + ')');
		}
	}

	public static GameModeWhere[] gameModesWhere() {
		try {
			return new GameModeWhere[]{GameModeWhere.LIVE, GameModeWhere.OFFICE_RC,
				GameModeWhere.OFFICE_QA, GameModeWhere.OFFICE_WIP, GameModeWhere.LOCAL,
				GameModeWhere.OFFICE_WTI, GameModeWhere.OFFICE_BETA};
		} catch (RuntimeException var2) {
			throw GenUtil.makeThrowable(var2, "i.C(" + "dummy" + ')');
		}
	}

	public static GameModeWhere lookupModeWhere(int val) {
		try {
			GameModeWhere[] var2 = GameModeWhere.gameModesWhere();
			for (int i = 0; i < var2.length; i++) {
				if (val == var2[i].val) {
					return var2[i];
				}
			}
			return null;
		} catch (RuntimeException var5) {
			throw GenUtil.makeThrowable(var5, "ub.B(" + val + ',' + "dummy" + ')');
		}
	}

	public final String toString() {
		try {
			throw new IllegalStateException();
		} catch (RuntimeException var2) {
			throw GenUtil.makeThrowable(var2, "v.toString()");
		}
	}
}
