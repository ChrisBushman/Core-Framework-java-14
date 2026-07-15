package orsc.util;

/**
 * java.util.Comparator was added in Java 1.2 - not present in MRJ 2.2.5's
 * JDK 1.1 base. Same single-method shape as the real Comparator.
 */
public interface SimpleComparator {
	int compare(Object o1, Object o2);
}
