package orsc;

import orsc.util.GenUtil;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import java.io.File;

public class soundPlayer {
	public static void playSoundFile(String key) {
		try {
			if (!mudclient.optionSoundDisabled) {
				File sound = (File) mudclient.soundCache.get(key + ".wav");
				if (sound == null)
					return;
				try {
					// PC sound code:
					javax.sound.sampled.AudioInputStream ais = AudioSystem.getAudioInputStream(sound);
					javax.sound.sampled.DataLine.Info clipInfo = new javax.sound.sampled.DataLine.Info(Clip.class, ais.getFormat());
					final Clip clip = (Clip) AudioSystem.getLine(clipInfo);
					clip.addLineListener(new LineListener() {
						public void update(LineEvent myLineEvent) {
							if (myLineEvent.getType() == LineEvent.Type.STOP)
								clip.close();
						}
					});
					clip.open(ais);
					clip.start();
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}

		} catch (RuntimeException var6) {
			throw GenUtil.makeThrowable(var6, "client.SC(" + "dummy" + ',' + (key != null ? "{...}" : "null") + ')');
		}
	}
}
