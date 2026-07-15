package com.openrsc.client.entityhandling;

import orsc.util.SimpleList;

import java.util.Hashtable;

/**
 * MRJ 2.2.5 (JDK 1.1 base) has no HashMap/Map/Set/Iterator at all - only the
 * classic Hashtable, which already has get/put/remove/containsKey/size/clear
 * matching the modern API directly. This preserves insertion order (which
 * spell rune display order depends on) via orderedEntries(), since there's
 * no entrySet()/Set to return here.
 */
public class OrderedHashMap extends Hashtable {

	private SimpleList insertionOrder = new SimpleList();

	public Object put(Object key, Object value) {
		if (!containsKey(key)) {
			insertionOrder.add(key);
		}
		return super.put(key, value);
	}

	public void clear() {
		super.clear();
		insertionOrder.clear();
	}

	public SimpleList orderedEntries() {
		SimpleList entries = new SimpleList(insertionOrder.size());
		for (int i = 0; i < insertionOrder.size(); i++) {
			Object key = insertionOrder.get(i);
			entries.add(new Entry(key, get(key)));
		}
		return entries;
	}

	public Object clone() {
		OrderedHashMap copy = (OrderedHashMap) super.clone();
		copy.insertionOrder = (SimpleList) insertionOrder.clone();
		return copy;
	}

	public static class Entry {
		private final Object key;
		private final Object value;

		Entry(Object key, Object value) {
			this.key = key;
			this.value = value;
		}

		public Object getKey() {
			return key;
		}

		public Object getValue() {
			return value;
		}
	}
}
