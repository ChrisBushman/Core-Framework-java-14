package orsc.enumerations;

public final class MessageTab {
	public static final MessageTab ALL = new MessageTab(0);
	public static final MessageTab CHAT = new MessageTab(1);
	public static final MessageTab QUEST = new MessageTab(2);
	public static final MessageTab PRIVATE = new MessageTab(3);
	public static final MessageTab CLAN = new MessageTab(4);

	private static final MessageTab[] VALUES = {ALL, CHAT, QUEST, PRIVATE, CLAN};
	private static final MessageTab[] map;

	static {
		int cap = 0;
		for (int i = 0; i < VALUES.length; i++)
			cap = Math.max(1 + VALUES[i].rsID, cap);
		map = new MessageTab[cap];
		for (int i = 0; i < VALUES.length; i++)
			if (VALUES[i].rsID >= 0)
				map[VALUES[i].rsID] = VALUES[i];
	}

	private final int rsID;

	private MessageTab(int rsID) {
		this.rsID = rsID;
	}

	public static MessageTab lookup(int rsID) {
		if (rsID >= 0 && rsID < map.length)
			return map[rsID];
		return null;
	}
}
