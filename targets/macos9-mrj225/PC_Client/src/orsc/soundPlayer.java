package orsc;

import orsc.util.GenUtil;

import sun.audio.AudioPlayer;
import sun.audio.AudioStream;
import java.io.File;
import java.io.FileInputStream;

public class soundPlayer {
	public static void playSoundFile(String key) {
		try {
			if (!mudclient.optionSoundDisabled) {
				File sound = (File) mudclient.soundCache.get(key + ".wav");
				if (sound == null)
					return;
				try {
					// javax.sound.sampled was added in Java 1.3; MRJ 2.2.5 (JDK 1.1
					// base) uses the classic sun.audio API instead, which Apple
					// ported natively into MRJClasses.zip.
					AudioStream audioStream = new AudioStream(new FileInputStream(sound));
					AudioPlayer.player.start(audioStream);
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}

		} catch (RuntimeException var6) {
			throw GenUtil.makeThrowable(var6, "client.SC(" + "dummy" + ',' + (key != null ? "{...}" : "null") + ')');
		}
	}
}
