package orsc.enumerations;

public final class InputXAction {
	public static final InputXAction ACT_0 = new InputXAction(0);
	public static final InputXAction TRADE_OFFER = new InputXAction(1);
	public static final InputXAction TRADE_REMOVE = new InputXAction(2);
	public static final InputXAction BANK_WITHDRAW = new InputXAction(3);
	public static final InputXAction BANK_DEPOSIT = new InputXAction(4);
	public static final InputXAction SHOP_BUY = new InputXAction(5);
	public static final InputXAction SHOP_SELL = new InputXAction(6);
	public static final InputXAction DUEL_STAKE = new InputXAction(7);
	public static final InputXAction DUEL_REMOVE = new InputXAction(8);
	public static final InputXAction SKIP_TUTORIAL = new InputXAction(9);
	public static final InputXAction EXIT_BLACK_HOLE = new InputXAction(10);
	public static final InputXAction DROP_X = new InputXAction(11);
	public static final InputXAction TEAM_DUEL_STAKE_X = new InputXAction(12);
	public static final InputXAction TEAM_DUEL_REMOVE_X = new InputXAction(13);
	public static final InputXAction INVITE_CLAN_PLAYER = new InputXAction(14);
	public static final InputXAction KICK_CLAN_PLAYER = new InputXAction(15);
	public static final InputXAction CLAN_DELEGATE_LEADERSHIP = new InputXAction(16);
	public static final InputXAction CLAN_LEAVE = new InputXAction(17);
	public static final InputXAction INVITE_PARTY_PLAYER = new InputXAction(18);
	public static final InputXAction KICK_PARTY_PLAYER = new InputXAction(19);
	public static final InputXAction PARTY_DELEGATE_LEADERSHIP = new InputXAction(20);
	public static final InputXAction PARTY_LEAVE = new InputXAction(21);
	public static final InputXAction INCPOINTS_X = new InputXAction(22);
	public static final InputXAction REDUCEPOINTS_X = new InputXAction(23);
	public static final InputXAction SAVEPRESET_X = new InputXAction(24);
	public static final InputXAction LOADPRESET_X = new InputXAction(25);
	public static final InputXAction POINTS_TO_GP = new InputXAction(26);
	public static final InputXAction REDUCELEVELS_X = new InputXAction(27);
	public static final InputXAction INCLEVELS_X = new InputXAction(28);

	public final int id;

	private InputXAction(int id) {
		this.id = id;
	}

	public boolean requiresNumeric() {
		return (id >= TRADE_OFFER.id && id <= DUEL_REMOVE.id)
			|| id == EXIT_BLACK_HOLE.id
			|| id == DROP_X.id
			|| id == SAVEPRESET_X.id
			|| id == LOADPRESET_X.id
			|| id == INCPOINTS_X.id
			|| id == REDUCEPOINTS_X.id
			|| id == INCLEVELS_X.id
			|| id == REDUCELEVELS_X.id
			|| id == POINTS_TO_GP.id;
	}
}
