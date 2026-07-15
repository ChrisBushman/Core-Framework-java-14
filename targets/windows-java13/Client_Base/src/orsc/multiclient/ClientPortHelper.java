package orsc.multiclient;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

import orsc.Config;

/**
 * Helper class holding the static file-IO methods that were originally
 * static default methods on the ClientPort interface (Java 8 only).
 */
public class ClientPortHelper {

	public static boolean saveHideIp(int preference) {
		try {
			FileOutputStream fileout = new FileOutputStream(Config.F_CACHE_DIR + File.separator + "hideIp.txt");
			OutputStreamWriter outputWriter = new OutputStreamWriter(fileout);
			outputWriter.write("" + preference);
			outputWriter.close();
			return true;
		} catch (Exception ignored) {
		}
		return false;
	}

	public static int loadHideIp() {
		try {
			FileInputStream in = new FileInputStream(Config.F_CACHE_DIR + File.separator + "hideIp.txt");
			InputStreamReader inputStreamReader = new InputStreamReader(in);
			BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
			StringBuffer sb = new StringBuffer();
			String line;
			while ((line = bufferedReader.readLine()) != null) {
				sb.append(line);
			}
			in.close();
			return Integer.parseInt(sb.toString());
		} catch (Exception ignored) {
		}
		return 0;
	}

	public static boolean saveCredentials(String creds) {
		try {
			FileOutputStream fileout = new FileOutputStream(Config.F_CACHE_DIR + File.separator + "credentials.txt");
			OutputStreamWriter outputWriter = new OutputStreamWriter(fileout);
			outputWriter.write(creds);
			outputWriter.close();
			return true;
		} catch (Exception ignored) {
		}
		return false;
	}

	public static String loadCredentials() {
		try {
			FileInputStream in = new FileInputStream(Config.F_CACHE_DIR + File.separator + "credentials.txt");
			InputStreamReader inputStreamReader = new InputStreamReader(in);
			BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
			StringBuffer sb = new StringBuffer();
			String line;
			while ((line = bufferedReader.readLine()) != null) {
				sb.append(line);
			}
			in.close();
			return sb.toString();
		} catch (Exception ignored) {
		}
		return "";
	}

	public static String loadIP() {
		try {
			FileInputStream in = new FileInputStream(Config.F_CACHE_DIR + File.separator + "ip.txt");
			InputStreamReader inputStreamReader = new InputStreamReader(in);
			BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
			StringBuffer sb = new StringBuffer();
			String line;
			while ((line = bufferedReader.readLine()) != null) {
				sb.append(line);
			}
			in.close();
			return sb.toString();
		} catch (Exception ignored) {
		}
		return "";
	}

	public static int loadPort() {
		try {
			FileInputStream in = new FileInputStream(Config.F_CACHE_DIR + File.separator + "port.txt");
			InputStreamReader inputStreamReader = new InputStreamReader(in);
			BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
			StringBuffer sb = new StringBuffer();
			String line;
			while ((line = bufferedReader.readLine()) != null) {
				sb.append(line);
			}
			in.close();
			return Integer.parseInt(sb.toString());
		} catch (Exception ignored) {
		}
		return 0;
	}
}
