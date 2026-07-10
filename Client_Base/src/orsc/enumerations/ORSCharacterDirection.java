package orsc.enumerations;

public final class ORSCharacterDirection {
	public static final ORSCharacterDirection NORTH = new ORSCharacterDirection(0, 0, -1, 128);
	public static final ORSCharacterDirection NORTH_WEST = new ORSCharacterDirection(1, 1, -1, 96);
	public static final ORSCharacterDirection WEST = new ORSCharacterDirection(2, 1, 0, 64);
	public static final ORSCharacterDirection SOUTH_WEST = new ORSCharacterDirection(3, 1, 1, 32);
	public static final ORSCharacterDirection SOUTH = new ORSCharacterDirection(4, 0, 1, 0);
	public static final ORSCharacterDirection SOUTH_EAST = new ORSCharacterDirection(5, -1, 1, 224);
	public static final ORSCharacterDirection EAST = new ORSCharacterDirection(6, -1, 0, 192);
	public static final ORSCharacterDirection NORTH_EAST = new ORSCharacterDirection(7, -1, -1, 160);
	public static final ORSCharacterDirection COMBAT_A = new ORSCharacterDirection(8, 0, 0, 128);
	public static final ORSCharacterDirection COMBAT_B = new ORSCharacterDirection(9, 0, 0, 0);

	private static final ORSCharacterDirection[] VALUES = {
		NORTH, NORTH_WEST, WEST, SOUTH_WEST, SOUTH, SOUTH_EAST, EAST, NORTH_EAST, COMBAT_A, COMBAT_B
	};
	private static final ORSCharacterDirection[] rsDir_Lookup;

	static {
		int max = 0;
		for (int i = 0; i < VALUES.length; i++)
			max = Math.max(max, VALUES[i].rsDir + 1);
		rsDir_Lookup = new ORSCharacterDirection[max];
		for (int i = 0; i < VALUES.length; i++)
			rsDir_Lookup[VALUES[i].rsDir] = VALUES[i];
	}

	public final int x0, z0;
	public final int rsDir;
	public final int rotation;

	private ORSCharacterDirection(int rsDir, int x0, int z0, int rotation) {
		this.rsDir = rsDir;
		this.x0 = x0;
		this.z0 = z0;
		this.rotation = rotation;
	}

	public static ORSCharacterDirection lookup(int rsDir) {
		if (rsDir >= 0 && rsDir < rsDir_Lookup.length)
			return rsDir_Lookup[rsDir];
		for (int i = 0; i < VALUES.length; i++)
			if (VALUES[i].rsDir == rsDir)
				return VALUES[i];
		System.out.println("Lookup fail: " + rsDir);
		return null;
	}
}
