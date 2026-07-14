package orsc.buffers;

/**
 * Minimal stand-in for java.nio.ByteBuffer, which does not exist in Java 1.3
 * (java.nio was added in 1.4). Only implements the subset of behaviour this
 * codebase actually uses: big-endian get/put of byte/short/int, flip(),
 * remaining()/hasRemaining(), and bulk put of another buffer's remaining bytes.
 */
public class SimpleByteBuffer {

	private final byte[] data;
	private int position;
	private int limit;

	private SimpleByteBuffer(byte[] data, int position, int limit) {
		this.data = data;
		this.position = position;
		this.limit = limit;
	}

	public static SimpleByteBuffer wrap(byte[] array) {
		return new SimpleByteBuffer(array, 0, array.length);
	}

	public static SimpleByteBuffer allocate(int capacity) {
		return new SimpleByteBuffer(new byte[capacity], 0, capacity);
	}

	public int remaining() {
		return limit - position;
	}

	public boolean hasRemaining() {
		return position < limit;
	}

	public SimpleByteBuffer flip() {
		limit = position;
		position = 0;
		return this;
	}

	private void requireGet(int n) {
		if (position + n > limit) {
			throw new RuntimeException("buffer underflow");
		}
	}

	private void requirePut(int n) {
		if (position + n > limit) {
			throw new RuntimeException("buffer overflow");
		}
	}

	public byte get() {
		requireGet(1);
		return data[position++];
	}

	public short getShort() {
		requireGet(2);
		int b0 = data[position++] & 0xFF;
		int b1 = data[position++] & 0xFF;
		return (short) ((b0 << 8) | b1);
	}

	public int getInt() {
		requireGet(4);
		int b0 = data[position++] & 0xFF;
		int b1 = data[position++] & 0xFF;
		int b2 = data[position++] & 0xFF;
		int b3 = data[position++] & 0xFF;
		return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
	}

	public SimpleByteBuffer put(byte b) {
		requirePut(1);
		data[position++] = b;
		return this;
	}

	public SimpleByteBuffer putInt(int value) {
		requirePut(4);
		data[position++] = (byte) (value >> 24);
		data[position++] = (byte) (value >> 16);
		data[position++] = (byte) (value >> 8);
		data[position++] = (byte) value;
		return this;
	}

	public SimpleByteBuffer put(SimpleByteBuffer src) {
		int n = src.remaining();
		requirePut(n);
		System.arraycopy(src.data, src.position, data, position, n);
		position += n;
		src.position += n;
		return this;
	}
}
