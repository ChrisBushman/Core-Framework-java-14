package orsc.enumerations;

public final class MenuItemAction {
	public static final MenuItemAction REPORT_ABUSE = new MenuItemAction(2833);
	public static final MenuItemAction PLAYER_FOLLOW = new MenuItemAction(2820);
	public static final MenuItemAction PLAYER_TRADE = new MenuItemAction(2810);
	public static final MenuItemAction PLAYER_PARTY_INVITE = new MenuItemAction(2850);
	public static final MenuItemAction PLAYER_DUEL = new MenuItemAction(2806);
	public static final MenuItemAction PLAYER_ATTACK_SIMILAR = new MenuItemAction(805);
	public static final MenuItemAction PLAYER_ATTACK_DIVERGENT = new MenuItemAction(2805);
	public static final MenuItemAction PLAYER_USE_ITEM = new MenuItemAction(810);
	public static final MenuItemAction PLAYER_CAST_SPELL = new MenuItemAction(800);
	public static final MenuItemAction CHAT_ADD_FRIEND = new MenuItemAction(2831);
	public static final MenuItemAction CHAT_ADD_IGNORE = new MenuItemAction(2832);
	public static final MenuItemAction CHAT_MESSAGE = new MenuItemAction(2830);
	public static final MenuItemAction CANCEL = new MenuItemAction(4000);
	public static final MenuItemAction WALL_CAST_SPELL = new MenuItemAction(300);
	public static final MenuItemAction WALL_USE_ITEM = new MenuItemAction(310);
	public static final MenuItemAction WALL_COMMAND1 = new MenuItemAction(320);
	public static final MenuItemAction WALL_COMMAND2 = new MenuItemAction(2300);
	public static final MenuItemAction WALL_EXAMINE = new MenuItemAction(3300);
	public static final MenuItemAction OBJECT_USE_ITEM = new MenuItemAction(410);
	public static final MenuItemAction OBJECT_COMMAND1 = new MenuItemAction(420);
	public static final MenuItemAction OBJECT_COMMAND2 = new MenuItemAction(2400);
	public static final MenuItemAction OBJECT_EXAMINE = new MenuItemAction(3400);
	public static final MenuItemAction OBJECT_CAST_SPELL = new MenuItemAction(400);
	public static final MenuItemAction GROUND_ITEM_CAST_SPELL = new MenuItemAction(200);
	public static final MenuItemAction GROUND_ITEM_TAKE = new MenuItemAction(220);
	public static final MenuItemAction GROUND_ITEM_EXAMINE = new MenuItemAction(3200);
	public static final MenuItemAction GROUND_ITEM_USE_ITEM = new MenuItemAction(210);
	public static final MenuItemAction NPC_CAST_SPELL = new MenuItemAction(700);
	public static final MenuItemAction NPC_ATTACK1 = new MenuItemAction(715);
	public static final MenuItemAction NPC_ATTACK2 = new MenuItemAction(2715);
	public static final MenuItemAction NPC_TALK_TO = new MenuItemAction(720);
	public static final MenuItemAction NPC_COMMAND1 = new MenuItemAction(725);
	public static final MenuItemAction NPC_COMMAND2 = new MenuItemAction(833);
	public static final MenuItemAction NPC_EXAMINE = new MenuItemAction(3700);
	public static final MenuItemAction NPC_USE_ITEM = new MenuItemAction(710);
	public static final MenuItemAction SELF_CAST_SPELL = new MenuItemAction(1000);
	public static final MenuItemAction LANDSCAPE_CAST_SPELL = new MenuItemAction(900);
	public static final MenuItemAction LANDSCAPE_WALK_HERE = new MenuItemAction(920);
	public static final MenuItemAction ITEM_CAST_SPELL = new MenuItemAction(600);
	public static final MenuItemAction ITEM_UNEQUIP_FROM_EQUIPMENT = new MenuItemAction(619);
	public static final MenuItemAction ITEM_UNEQUIP_FROM_INVENTORY = new MenuItemAction(620);
	public static final MenuItemAction ITEM_EQUIP_FROM_INVENTORY = new MenuItemAction(630);
	public static final MenuItemAction ITEM_COMMAND = new MenuItemAction(640);
	public static final MenuItemAction ITEM_COMMAND_ALL = new MenuItemAction(641);
	public static final MenuItemAction ITEM_COMMAND_EQUIPTAB = new MenuItemAction(642);
	public static final MenuItemAction ITEM_USE = new MenuItemAction(650);
	public static final MenuItemAction ITEM_USE_EQUIPTAB = new MenuItemAction(651);
	public static final MenuItemAction ITEM_DROP = new MenuItemAction(660);
	public static final MenuItemAction ITEM_DROP_X = new MenuItemAction(661);
	public static final MenuItemAction ITEM_DROP_ALL = new MenuItemAction(662);
	public static final MenuItemAction ITEM_DROP_EQUIPTAB = new MenuItemAction(663);
	public static final MenuItemAction ITEM_EXAMINE = new MenuItemAction(3600);
	public static final MenuItemAction ITEM_USE_ITEM = new MenuItemAction(610);
	public static final MenuItemAction TRADE_OFFER = new MenuItemAction(1);
	public static final MenuItemAction TRADE_REMOVE = new MenuItemAction(2);
	public static final MenuItemAction DUEL_STAKE = new MenuItemAction(3);
	public static final MenuItemAction DUEL_REMOVE = new MenuItemAction(4);
	public static final MenuItemAction DEV_ADD_NPC = new MenuItemAction(1337);
	public static final MenuItemAction DEV_REMOVE_NPC = new MenuItemAction(1338);
	public static final MenuItemAction DEV_ADD_OBJECT = new MenuItemAction(1339);
	public static final MenuItemAction DEV_REMOVE_OBJECT = new MenuItemAction(1340);
	public static final MenuItemAction DEV_ROTATE_OBJECT = new MenuItemAction(1341);
	public static final MenuItemAction MOD_SUMMON_PLAYER = new MenuItemAction(2835);
	public static final MenuItemAction MOD_GOTO_PLAYER = new MenuItemAction(2836);
	public static final MenuItemAction MOD_PUT_PLAYER_JAIL = new MenuItemAction(2837);
	public static final MenuItemAction MOD_KICK_PLAYER = new MenuItemAction(2838);
	public static final MenuItemAction MOD_CHECK_PLAYER = new MenuItemAction(2839);
	public static final MenuItemAction MOD_TELEPORT = new MenuItemAction(2840);
	public static final MenuItemAction MOD_RETURN_PLAYER = new MenuItemAction(2841);
	public static final MenuItemAction MOD_RELEASE_PLAYER_JAIL = new MenuItemAction(2842);
	public static final MenuItemAction CLAN_MENU_KICK = new MenuItemAction(1150);
	public static final MenuItemAction CLAN_PROMOTE = new MenuItemAction(1151);
	public static final MenuItemAction CLAN_RANK_ALLOW_KICK = new MenuItemAction(1152);
	public static final MenuItemAction CLAN_RANK_ALLOW_INVITE = new MenuItemAction(1153);
	public static final MenuItemAction CLAN_ACCEPT_REQUESTS = new MenuItemAction(1154);
	public static final MenuItemAction PARTY_MENU_KICK = new MenuItemAction(1155);
	public static final MenuItemAction PARTY_PROMOTE = new MenuItemAction(1156);
	public static final MenuItemAction PARTY_RANK_ALLOW_KICK = new MenuItemAction(1157);
	public static final MenuItemAction PARTY_RANK_ALLOW_INVITE = new MenuItemAction(1158);
	public static final MenuItemAction PARTY_ACCEPT_REQUESTS = new MenuItemAction(1159);

	private final int priority;

	private MenuItemAction(int priority) {
		this.priority = priority;
	}

	public int priority() {
		return priority;
	}
}
