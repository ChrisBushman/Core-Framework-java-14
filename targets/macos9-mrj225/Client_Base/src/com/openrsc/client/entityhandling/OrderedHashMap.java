package com.openrsc.client.entityhandling;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Java 1.3 has no LinkedHashMap (added in 1.4). This preserves insertion
 * order for entrySet() iteration, which spell rune display order depends on.
 */
public class OrderedHashMap extends HashMap {

	private ArrayList insertionOrder = new ArrayList();

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

	public Set entrySet() {
		ArrayList entries = new ArrayList(insertionOrder.size());
		for (Iterator it = insertionOrder.iterator(); it.hasNext(); ) {
			Object key = it.next();
			entries.add(new Entry(key, get(key)));
		}
		return new OrderedEntrySet(entries);
	}

	public Object clone() {
		OrderedHashMap copy = (OrderedHashMap) super.clone();
		copy.insertionOrder = (ArrayList) insertionOrder.clone();
		return copy;
	}

	private static class Entry implements Map.Entry {
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

		public Object setValue(Object value) {
			throw new UnsupportedOperationException();
		}
	}

	private static class OrderedEntrySet extends AbstractSet {
		private final ArrayList entries;

		OrderedEntrySet(ArrayList entries) {
			this.entries = entries;
		}

		public Iterator iterator() {
			return entries.iterator();
		}

		public int size() {
			return entries.size();
		}
	}
}
