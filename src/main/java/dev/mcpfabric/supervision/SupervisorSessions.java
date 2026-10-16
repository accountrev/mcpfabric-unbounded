package dev.mcpfabric.supervision;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The one supervisor session of the bridge (#27). A session is opened over the authenticated bridge
 * and its id stays with the MCP server that opened it. Every supervised request names the session
 * and a sequence number that must grow, so a request from an older session, or one replayed with a
 * sequence number already seen, is refused.
 *
 * <p>Opening a session takes over from the previous one, and opening or closing one fences every
 * outstanding grant through {@code fence}. Thread-safe.
 */
public final class SupervisorSessions {
	public enum Check {
		OK,
		/** The request names no session. */
		NO_SESSION,
		/** The session was closed or another one took over. */
		STALE_SESSION,
		/** The sequence number is not larger than the last one accepted. */
		REPLAYED
	}

	private final SecureRandom random = new SecureRandom();
	private final Runnable fence;
	private byte[] current;
	private long lastSeq;

	/** {@code fence} runs whenever a session opens or closes (it stops the authority). */
	public SupervisorSessions(Runnable fence) {
		this.fence = fence;
	}

	/** Opens a new session, ending the previous one. Returns its id (32 hex digits). */
	public String open() {
		String id;
		synchronized (this) {
			byte[] bytes = new byte[16];
			random.nextBytes(bytes);
			id = HexFormat.of().formatHex(bytes);
			current = id.getBytes(StandardCharsets.US_ASCII);
			lastSeq = 0;
		}
		fence.run();
		return id;
	}

	/** Closes the session if {@code id} is the current one. */
	public boolean close(String id) {
		synchronized (this) {
			if (!matches(id)) return false;
			current = null;
		}
		fence.run();
		return true;
	}

	public synchronized boolean active() {
		return current != null;
	}

	/** Checks a request and, when it is accepted, remembers its sequence number. */
	public synchronized Check check(String id, long seq) {
		if (id == null || id.isEmpty()) return Check.NO_SESSION;
		if (!matches(id)) return Check.STALE_SESSION;
		if (seq <= lastSeq) return Check.REPLAYED;
		lastSeq = seq;
		return Check.OK;
	}

	private boolean matches(String id) {
		return current != null && id != null
				&& MessageDigest.isEqual(current, id.getBytes(StandardCharsets.US_ASCII));
	}
}
