package dev.mcpfabric.supervision;

/** The outcome of asking a {@link SupervisedAuthority} to admit an action or an input sample. */
public enum Admission {
	ADMITTED,
	/** The grant was issued in another context (world, session) than the current one. */
	CONTEXT_CHANGED,
	/** STOP, or a context change since the grant was issued, fenced it off. */
	STOPPED,
	EXPIRED,
	/** The effect is about to act on another target than the grant names. */
	TARGET_MISMATCH,
	/** The grant already admitted its action, or its input sample. */
	USED,
	/** An input sample was asked for before the grant admitted its action. */
	NO_ACTION;

	public boolean admitted() {
		return this == ADMITTED;
	}
}
