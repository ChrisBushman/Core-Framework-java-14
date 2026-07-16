package orsc.enumerations;

public final class MessageType {
	public static final MessageType GAME = new MessageType(0, "@whi@");
	public static final MessageType PRIVATE_RECIEVE = new MessageType(1, "@cya@");
	public static final MessageType PRIVATE_SEND = new MessageType(2, "@cya@");
	public static final MessageType QUEST = new MessageType(3, "@whi@");
	public static final MessageType CHAT = new MessageType(4, "@yel@");
	public static final MessageType FRIEND_STATUS = new MessageType(5, "@cya@");
	public static final MessageType TRADE = new MessageType(6, "@whi@");
	public static final MessageType INVENTORY = new MessageType(7, "@whi@");
	public static final MessageType GLOBAL_CHAT = new MessageType(8, "@yel@");
	public static final MessageType CLAN_CHAT = new MessageType(9, "@yel@");

	private static final MessageType[] VALUES = {
		GAME, PRIVATE_RECIEVE, PRIVATE_SEND, QUEST, CHAT,
		FRIEND_STATUS, TRADE, INVENTORY, GLOBAL_CHAT, CLAN_CHAT
	};
	private static final MessageType[] map;

	static {
		int cap = 0;
		for (int i = 0; i < VALUES.length; i++)
			cap = Math.max(1 + VALUES[i].rsID, cap);
		map = new MessageType[cap];
		for (int i = 0; i < VALUES.length; i++)
			if (VALUES[i].rsID >= 0)
				map[VALUES[i].rsID] = VALUES[i];
	}

	public final String color;
	final int rsID;

	private MessageType(int rsID, String color) {
		this.rsID = rsID;
		this.color = color;
	}

	public static MessageType lookup(int rsID) {
		if (rsID >= 0 && rsID < map.length)
			return map[rsID];
		return null;
	}
}
