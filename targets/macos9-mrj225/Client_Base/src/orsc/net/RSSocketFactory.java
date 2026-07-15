package orsc.net;

import java.io.IOException;
import java.net.Socket;

import orsc.util.GenUtil;

final class RSSocketFactory extends RSSocketFactory_Base {

	RSSocketFactory() {
	}

	public final Socket open() throws IOException {
		try {
			return this.openRaw();
		} catch (RuntimeException var2) {
			throw GenUtil.makeThrowable(var2, "gb.D(" + "dummy" + ')');
		}
	}
}
