package orsc;

import orsc.util.GenUtil;

import sun.audio.AudioPlayer;
import sun.audio.AudioStream;
import java.io.File;
import java.io.FileInputStream;

public class soundPlayer {
	public static void playSoundFile(String key) {
		try {
			if (mudclient.optionSoundDisabled) {
				System.out.println("playSoundFile(" + key + "): sound is disabled, skipping");
				System.out.flush();
				return;
			}
			// The cache is populated with lowercase filenames (mudclient.loadSounds()),
			// but the key here can come straight from a server packet with whatever
			// casing the server happens to use - lowercase it to match.
			String cacheKey = key.toLowerCase() + ".au";
			File sound = (File) mudclient.soundCache.get(cacheKey);
			if (sound == null) {
				System.out.println("playSoundFile(" + key + "): no cached file for " + cacheKey);
				System.out.flush();
				return;
			}
			try {
				System.out.println("playSoundFile(" + key + "): starting playback of " + sound.getAbsolutePath());
				System.out.flush();
				// javax.sound.sampled was added in Java 1.3; MRJ 2.2.5 (JDK 1.1
				// base) uses the classic sun.audio API instead, which Apple
				// ported natively into MRJClasses.zip.
				AudioStream audioStream = new AudioStream(new FileInputStream(sound));
				AudioPlayer.player.start(audioStream);
				System.out.println("playSoundFile(" + key + "): AudioPlayer.start() returned normally");
				System.out.flush();
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		} catch (RuntimeException var6) {
			throw GenUtil.makeThrowable(var6, "client.SC(" + "dummy" + ',' + (key != null ? "{...}" : "null") + ')');
		}
	}
}
