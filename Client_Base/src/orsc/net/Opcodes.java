package orsc.net;

public class Opcodes {
	public static final class In {
		private In() {}
	}

	public static final class Out {
		public static final Out PING = new Out(67);
		public static final Out WALK_TO_ENTITY = new Out(16);
		public static final Out WALK_TO_POINT = new Out(187);
		public static final Out CONFIRM_LOGOUT = new Out(31);
		public static final Out LOGOUT = new Out(102);
		public static final Out ADD_FRIEND = new Out(195);
		public static final Out ADD_IGNORE = new Out(132);
		public static final Out BLINK = new Out(59);
		public static final Out COMBAT_STYLE_CHANGED = new Out(29);
		public static final Out QUESTION_DIALOG_ANSWER = new Out(116);

		public static final Out PLAYER_APPEARANCE_CHANGE = new Out(235);
		public static final Out SOCIAL_ADD_IGNORE = new Out(132);
		public static final Out SOCIAL_ADD_DELAYED_IGNORE = new Out(194);
		public static final Out SOCIAL_ADD_FRIEND = new Out(195);
		public static final Out SOCIAL_SEND_PRIVATE_MESSAGE = new Out(218);
		public static final Out SOCIAL_REMOVE_FRIEND = new Out(167);
		public static final Out SOCIAL_REMOVE_IGNORE = new Out(241);

		public static final Out DUEL_FIRST_SETTINGS_CHANGED = new Out(8);
		public static final Out DUEL_FIRST_ACCEPTED = new Out(176);
		public static final Out DUEL_DECLINED = new Out(197);
		public static final Out DUEL_OFFER_ITEM = new Out(33);
		public static final Out DUEL_SECOND_ACCEPTED = new Out(77);

		public static final Out WALL_OBJECT_COMMAND1 = new Out(14);
		public static final Out WALL_OBJECT_COMMAND2 = new Out(127);
		public static final Out WALL_OBJECT_CAST = new Out(180);
		public static final Out WALL_USE_ITEM = new Out(161);

		public static final Out NPC_TALK_TO = new Out(153);
		public static final Out NPC_COMMAND1 = new Out(202);
		public static final Out NPC_COMMAND2 = new Out(203);
		public static final Out NPC_ATTACK1 = new Out(190);
		public static final Out NPC_CAST_SPELL = new Out(50);
		public static final Out NPC_USE_ITEM = new Out(135);

		public static final Out PLAYER_CAST_SPELL = new Out(229);
		public static final Out PLAYER_USE_ITEM = new Out(113);
		public static final Out PLAYER_ATTACK = new Out(171);
		public static final Out PLAYER_DUEL = new Out(103);
		public static final Out PLAYER_TRADE = new Out(142);
		public static final Out PLAYER_FOLLOW = new Out(165);

		public static final Out GROUND_ITEM_CAST_SPELL = new Out(249);
		public static final Out GROUND_ITEM_USE_ITEM = new Out(53);
		public static final Out GROUND_ITEM_TAKE = new Out(247);

		public static final Out ITEM_CAST_SPELL = new Out(4);
		public static final Out ITEM_USE_ITEM = new Out(91);
		public static final Out ITEM_UNEQUIP_FROM_EQUIPMENT = new Out(168);
		public static final Out ITEM_UNEQUIP_FROM_INVENTORY = new Out(170);
		public static final Out ITEM_EQUIP_FROM_INVENTORY = new Out(169);
		public static final Out ITEM_EQUIP_FROM_BANK = new Out(172);
		public static final Out ITEM_REMOVE_TO_BANK = new Out(173);
		public static final Out ITEM_COMMAND = new Out(90);
		public static final Out ITEM_DROP = new Out(246);

		public static final Out CAST_ON_SELF = new Out(137);
		public static final Out CAST_ON_LAND = new Out(158);

		public static final Out OBJECT_COMMAND1 = new Out(136);
		public static final Out OBJECT_COMMAND2 = new Out(79);
		public static final Out OBJECT_CAST = new Out(99);
		public static final Out OBJECT_USE_ITEM = new Out(115);

		public static final Out SHOP_CLOSE = new Out(166);
		public static final Out SHOP_BUY = new Out(236);
		public static final Out SHOP_SELL = new Out(221);

		public static final Out TRADE_ACCEPTED = new Out(55);
		public static final Out TRADE_DECLINED = new Out(230);
		public static final Out TRADE_OFFER = new Out(46);
		public static final Out TRADE_CONFIRM_ACCEPTED = new Out(104);

		public static final Out PRAYER_ACTIVATED = new Out(60);
		public static final Out PRAYER_DEACTIVATED = new Out(254);

		public static final Out GAME_SETTINGS_CHANGED = new Out(111);
		public static final Out CHAT_MESSAGE = new Out(216);
		public static final Out COMMAND = new Out(38);
		public static final Out PRIVACY_SETTINGS_CHANGED = new Out(64);
		public static final Out REPORT_ABUSE = new Out(206);
		public static final Out BANK_CLOSE = new Out(212);
		public static final Out BANK_WITHDRAW = new Out(22);
		public static final Out BANK_DEPOSIT = new Out(23);
		public static final Out BANK_DEPOSIT_ALL_FROM_INVENTORY = new Out(24);
		public static final Out BANK_DEPOSIT_ALL_FROM_EQUIPMENT = new Out(26);
		public static final Out BANK_SAVE_PRESET = new Out(27);
		public static final Out BANK_LOAD_PRESET = new Out(28);
		public static final Out INTERFACE_OPTIONS = new Out(199);
		public static final Out CHANGE_PASS = new Out(25);
		public static final Out CANCEL_RECOVERY_REQUEST = new Out(196);
		public static final Out CHANGE_RECOVERY = new Out(200);
		public static final Out SET_RECOVERY = new Out(208);
		public static final Out CHANGE_DETAILS = new Out(201);
		public static final Out SET_DETAILS = new Out(253);

		public static final Out SLEEPWORD_ENTERED = new Out(45);

		public static final Out ON_TUTORIAL_ISLAND = new Out(84);
		public static final Out ON_BLACK_HOLE = new Out(86);
		public static final Out NPC_DEFINITION_REQUEST = new Out(89);

		private int opcode;

		private Out(int opcode) {
			this.opcode = opcode;
		}

		public int getOpcode() {
			return opcode;
		}

		public void setOpcode(int opcode) {
			this.opcode = opcode;
		}
	}
}
