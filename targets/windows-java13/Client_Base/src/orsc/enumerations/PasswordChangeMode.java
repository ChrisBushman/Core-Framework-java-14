package orsc.enumerations;

public final class PasswordChangeMode {
	public static final PasswordChangeMode NONE = new PasswordChangeMode();
	public static final PasswordChangeMode OLD_PASSWORD = new PasswordChangeMode();
	public static final PasswordChangeMode NEW_PASSWORD = new PasswordChangeMode();
	public static final PasswordChangeMode CONFIRM_PASSWORD = new PasswordChangeMode();
	public static final PasswordChangeMode PASSWORD_MISMATCH = new PasswordChangeMode();
	public static final PasswordChangeMode PASSWORD_REQ_SENT = new PasswordChangeMode();
	public static final PasswordChangeMode NEED_LONGER_PASSWORD = new PasswordChangeMode();
	public static final PasswordChangeMode PASSWORD_NOT_EQ_USER = new PasswordChangeMode();
	private PasswordChangeMode() {}
}
