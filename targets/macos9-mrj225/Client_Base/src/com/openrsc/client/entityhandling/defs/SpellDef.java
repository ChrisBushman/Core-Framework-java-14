package com.openrsc.client.entityhandling.defs;

import com.openrsc.client.entityhandling.OrderedHashMap;
import orsc.util.SimpleList;

public class SpellDef extends EntityDef {

	private int reqLevel;
	public int type;
	private int runeCount;
	private OrderedHashMap requiredRunes;

	public SpellDef(String name, String description, int level, int type, int runeCount, OrderedHashMap requiredRunes) {
		super(name, description);
		this.reqLevel = level;
		this.type = type;
		this.runeCount = runeCount;
		this.requiredRunes = requiredRunes;
	}

	public int getReqLevel() {
		return reqLevel;
	}

	public int getSpellType() {
		return type;
	}

	public int getRuneCount() {
		return runeCount;
	}

	public SimpleList getRunesRequired() {
		return requiredRunes.orderedEntries();
	}
}
