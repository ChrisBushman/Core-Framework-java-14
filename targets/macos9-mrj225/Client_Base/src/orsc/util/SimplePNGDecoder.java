package orsc.util;

import java.io.ByteArrayOutputStream;
import java.util.zip.Inflater;

/**
 * Minimal pure-Java PNG decoder for MRJ 2.2.5, whose native Toolkit predates
 * PNG support in AWT and cannot decode it at all (Toolkit.createImage()
 * reports MediaTracker.ERRORED immediately, confirmed on real hardware).
 *
 * Handles what actually shows up here: non-interlaced PNGs at 8-bit depth for
 * grayscale/truecolor/grayscale+alpha/truecolor+alpha (whatever a plain
 * BufferedImage + ImageIO.write(..., "PNG", ...) produces - see
 * CaptchaGenerator.makeColourfulRSCLCaptcha() server-side), plus indexed-color
 * (palette) PNGs at 1/2/4/8-bit depth with an optional tRNS chunk - that's
 * what the server's prerendered sleepword images turned out to be saved as.
 *
 * java.util.zip.Inflater/Deflater have been part of java.util.zip since
 * JDK 1.1, so this works without any external dependency.
 */
public class SimplePNGDecoder {

	private static final byte[] SIGNATURE = {
		(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
	};

	public int width;
	public int height;
	public int[] pixels;

	public static SimplePNGDecoder decode(byte[] data) throws Exception {
		for (int i = 0; i < SIGNATURE.length; i++) {
			if (data[i] != SIGNATURE[i]) {
				throw new Exception("Not a PNG file (bad signature)");
			}
		}

		int pos = SIGNATURE.length;
		int width = 0;
		int height = 0;
		int bitDepth = 0;
		int colorType = 0;
		int interlace = 0;
		byte[] palette = null;
		byte[] paletteAlpha = null;
		ByteArrayOutputStream idat = new ByteArrayOutputStream();

		while (pos < data.length) {
			int length = readInt(data, pos);
			pos += 4;
			String type = new String(data, pos, 4, "ISO-8859-1");
			pos += 4;

			if (type.equals("IHDR")) {
				width = readInt(data, pos);
				height = readInt(data, pos + 4);
				bitDepth = data[pos + 8] & 0xFF;
				colorType = data[pos + 9] & 0xFF;
				interlace = data[pos + 12] & 0xFF;
			} else if (type.equals("PLTE")) {
				palette = new byte[length];
				System.arraycopy(data, pos, palette, 0, length);
			} else if (type.equals("tRNS")) {
				paletteAlpha = new byte[length];
				System.arraycopy(data, pos, paletteAlpha, 0, length);
			} else if (type.equals("IDAT")) {
				idat.write(data, pos, length);
			} else if (type.equals("IEND")) {
				break;
			}

			pos += length;
			pos += 4; // CRC
		}

		if (interlace != 0) {
			throw new Exception("Interlaced PNG not supported");
		}

		int channels;
		switch (colorType) {
			case 0: channels = 1; break; // grayscale
			case 2: channels = 3; break; // truecolor
			case 3: channels = 1; break; // indexed
			case 4: channels = 2; break; // grayscale+alpha
			case 6: channels = 4; break; // truecolor+alpha
			default: throw new Exception("Unsupported PNG color type: " + colorType);
		}
		if (colorType == 3) {
			if (bitDepth != 1 && bitDepth != 2 && bitDepth != 4 && bitDepth != 8) {
				throw new Exception("Unsupported PNG bit depth for indexed color: " + bitDepth);
			}
			if (palette == null) {
				throw new Exception("Indexed PNG missing PLTE chunk");
			}
		} else if (bitDepth != 8) {
			throw new Exception("Unsupported PNG bit depth " + bitDepth + " for color type " + colorType);
		}

		int bitsPerPixel = bitDepth * channels;
		int bpp = Math.max(1, (bitsPerPixel + 7) / 8);
		int rowBytes = (width * bitsPerPixel + 7) / 8;

		byte[] compressed = idat.toByteArray();
		byte[] raw = new byte[(rowBytes + 1) * height];
		Inflater inflater = new Inflater();
		inflater.setInput(compressed);
		int rawLength = 0;
		while (!inflater.finished() && rawLength < raw.length) {
			int n = inflater.inflate(raw, rawLength, raw.length - rawLength);
			if (n == 0) {
				break;
			}
			rawLength += n;
		}
		inflater.end();

		byte[] prevRow = new byte[rowBytes];
		byte[] currRow = new byte[rowBytes];
		int[] pixels = new int[width * height];
		int rawPos = 0;

		for (int y = 0; y < height; y++) {
			int filterType = raw[rawPos++] & 0xFF;
			System.arraycopy(raw, rawPos, currRow, 0, rowBytes);
			rawPos += rowBytes;

			unfilter(filterType, currRow, prevRow, bpp);

			int rowBase = y * width;
			for (int x = 0; x < width; x++) {
				int argb;
				if (bitDepth == 8) {
					int p = x * channels;
					switch (colorType) {
						case 0: {
							int v = currRow[p] & 0xFF;
							argb = 0xFF000000 | (v << 16) | (v << 8) | v;
							break;
						}
						case 2: {
							int r = currRow[p] & 0xFF;
							int g = currRow[p + 1] & 0xFF;
							int b = currRow[p + 2] & 0xFF;
							argb = 0xFF000000 | (r << 16) | (g << 8) | b;
							break;
						}
						case 3: {
							argb = paletteEntry(palette, paletteAlpha, currRow[p] & 0xFF);
							break;
						}
						case 4: {
							int v = currRow[p] & 0xFF;
							int a = currRow[p + 1] & 0xFF;
							argb = (a << 24) | (v << 16) | (v << 8) | v;
							break;
						}
						case 6: {
							int r = currRow[p] & 0xFF;
							int g = currRow[p + 1] & 0xFF;
							int b = currRow[p + 2] & 0xFF;
							int a = currRow[p + 3] & 0xFF;
							argb = (a << 24) | (r << 16) | (g << 8) | b;
							break;
						}
						default:
							argb = 0xFF000000;
					}
				} else {
					// colorType == 3 (indexed) at bit depth 1, 2, or 4
					argb = paletteEntry(palette, paletteAlpha, readSubByteSample(currRow, x, bitDepth));
				}
				pixels[rowBase + x] = argb;
			}

			byte[] tmp = prevRow;
			prevRow = currRow;
			currRow = tmp;
		}

		SimplePNGDecoder result = new SimplePNGDecoder();
		result.width = width;
		result.height = height;
		result.pixels = pixels;
		return result;
	}

	private static int paletteEntry(byte[] palette, byte[] paletteAlpha, int index) {
		int p = index * 3;
		int r = palette[p] & 0xFF;
		int g = palette[p + 1] & 0xFF;
		int b = palette[p + 2] & 0xFF;
		int a = 0xFF;
		if (paletteAlpha != null && index < paletteAlpha.length) {
			a = paletteAlpha[index] & 0xFF;
		}
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	private static int readSubByteSample(byte[] row, int x, int bitDepth) {
		int samplesPerByte = 8 / bitDepth;
		int byteIndex = x / samplesPerByte;
		int sampleIndexInByte = x % samplesPerByte;
		int shift = 8 - bitDepth - (sampleIndexInByte * bitDepth);
		int mask = (1 << bitDepth) - 1;
		return (row[byteIndex] >> shift) & mask;
	}

	private static void unfilter(int filterType, byte[] curr, byte[] prev, int bpp) {
		int length = curr.length;
		switch (filterType) {
			case 0: // None
				break;
			case 1: // Sub
				for (int i = bpp; i < length; i++) {
					curr[i] = (byte) (curr[i] + curr[i - bpp]);
				}
				break;
			case 2: // Up
				for (int i = 0; i < length; i++) {
					curr[i] = (byte) (curr[i] + prev[i]);
				}
				break;
			case 3: // Average
				for (int i = 0; i < length; i++) {
					int left = (i >= bpp) ? (curr[i - bpp] & 0xFF) : 0;
					int up = prev[i] & 0xFF;
					curr[i] = (byte) (curr[i] + ((left + up) / 2));
				}
				break;
			case 4: // Paeth
				for (int i = 0; i < length; i++) {
					int left = (i >= bpp) ? (curr[i - bpp] & 0xFF) : 0;
					int up = prev[i] & 0xFF;
					int upLeft = (i >= bpp) ? (prev[i - bpp] & 0xFF) : 0;
					curr[i] = (byte) (curr[i] + paethPredictor(left, up, upLeft));
				}
				break;
			default:
				break;
		}
	}

	private static int paethPredictor(int a, int b, int c) {
		int p = a + b - c;
		int pa = Math.abs(p - a);
		int pb = Math.abs(p - b);
		int pc = Math.abs(p - c);
		if (pa <= pb && pa <= pc) {
			return a;
		} else if (pb <= pc) {
			return b;
		} else {
			return c;
		}
	}

	private static int readInt(byte[] data, int offset) {
		return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
			| ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
	}
}
