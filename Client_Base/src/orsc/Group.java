package orsc;

import java.util.HashMap;

/**
 * @author Kenix
 */
class Group {
	private static final int OWNER = 0;
	private static final int ADMIN = 1;
	private static final int SUPER_MOD = 2;
	private static final int MOD = 3;
	private static final int DEV = 5;
	private static final int EVENT = 7;
	private static final int PLAYER_MOD = 8;
	private static final int TESTER = 9;
	private static final int USER = 10;

	static final int DEFAULT_GROUP = Group.USER;

	private static final HashMap GROUP_NAMES = new HashMap();

	static {
		GROUP_NAMES.put(new Integer(OWNER), "Owner");
		GROUP_NAMES.put(new Integer(ADMIN), "Admin");
		GROUP_NAMES.put(new Integer(SUPER_MOD), "Super Moderator");
		GROUP_NAMES.put(new Integer(MOD), "Moderator");
		GROUP_NAMES.put(new Integer(DEV), "Developer");
		GROUP_NAMES.put(new Integer(EVENT), "Event");
		GROUP_NAMES.put(new Integer(PLAYER_MOD), "Player Moderator");
		GROUP_NAMES.put(new Integer(TESTER), "Tester");
		GROUP_NAMES.put(new Integer(USER), "User");
	}

	private static String getNameColour(int groupID) {
		if (!Config.S_WANT_CUSTOM_RANK_DISPLAY)
			return "";

		switch (groupID) {
			case OWNER:
				return "@dcy@";
			case ADMIN:
				return "@gre@";
			case SUPER_MOD:
				return "@blu@";
			case MOD:
				return "@bl1@";
			case DEV:
				return "@red@";
			case EVENT:
				return "@eve@";
			case PLAYER_MOD:
			case TESTER:
			case USER:
			default:
				return "";
		}
	}

	private static String getNameSprite(int groupID) {
		return "";

		/*if (!Config.S_WANT_CUSTOM_RANK_DISPLAY)
			return "";

		switch (groupID) {
			case OWNER:
			case ADMIN:
				return "#adm#";
			case SUPER_MOD:
			case MOD:
				return "#mod#";
			case DEV:
				return "#dev#";
			case EVENT:
				return "#eve#";
			case USER:
			default:
				return "";
		}*/
	}

	static String getStaffPrefix(int groupID) {
		return getNameSprite(groupID) + getNameColour(groupID);
	}
}
