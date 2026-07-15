package orsc.util;

import java.util.Vector;

/**
 * ArrayList-compatible facade over Vector. MRJ 2.2.5's JDK 1.1 base has no
 * ArrayList/List at all, and its Vector only has the classic
 * addElement/elementAt/removeElementAt API - no add/get/remove/clear. This
 * lets existing ArrayList-style call sites keep using add/get/remove/clear
 * unchanged, just retyped from ArrayList to SimpleList.
 *
 * Iteration still goes through the inherited Vector.elements() (a real
 * java.util.Enumeration), since java.util.Iterator doesn't exist until 1.2
 * either.
 */
public class SimpleList extends Vector {

	public SimpleList() {
		super();
	}

	public SimpleList(int initialCapacity) {
		super(initialCapacity);
	}

	public boolean add(Object o) {
		addElement(o);
		return true;
	}

	public void add(int index, Object o) {
		insertElementAt(o, index);
	}

	public Object get(int index) {
		return elementAt(index);
	}

	public Object set(int index, Object o) {
		Object old = elementAt(index);
		setElementAt(o, index);
		return old;
	}

	public Object remove(int index) {
		Object old = elementAt(index);
		removeElementAt(index);
		return old;
	}

	public boolean remove(Object o) {
		return removeElement(o);
	}

	public void clear() {
		removeAllElements();
	}

	public boolean addAll(SimpleList other) {
		for (int i = 0; i < other.size(); i++) {
			addElement(other.elementAt(i));
		}
		return true;
	}

	/** Assumes a is already sized to size(), matching how all call sites here use it. */
	public Object[] toArray(Object[] a) {
		copyInto(a);
		return a;
	}

	/**
	 * java.util.Collections.sort(List, Comparator) was added in Java 1.2 -
	 * not present pre-1.2. Simple in-place insertion sort; these lists are
	 * small UI/gameplay lists, not performance-critical.
	 */
	public void sort(SimpleComparator c) {
		for (int i = 1; i < size(); i++) {
			Object key = elementAt(i);
			int j = i - 1;
			while (j >= 0 && c.compare(elementAt(j), key) > 0) {
				setElementAt(elementAt(j), j + 1);
				j--;
			}
			setElementAt(key, j + 1);
		}
	}
}
