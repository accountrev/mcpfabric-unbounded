package dev.mcpfabric.supervision;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Finite authority for an opt-in supervised mode (#27). It is not wired into the mod yet.
 *
 * <p>A {@link Grant} is short-lived and cannot be renewed. It admits one action and, inside that
 * action, at most one cardinal input sample. Every admission is checked again at the point of the
 * effect: the grant must belong to the current context and the current generation and must not have
 * expired, so work queued before STOP, expiry or a context change cannot use it.
 *
 * <p>{@link #stop()} only fences new admissions. It does not interrupt or roll back an effect that
 * was already admitted, and the expiry bounds admission, not how long an admitted effect lasts.
 *
 * <p>Thread-safe: grants are issued and stopped from bridge threads and admitted on the game thread.
 */
public final class SupervisedAuthority {
	/** The longest a grant may live. */
	public static final long MAX_TTL_NANOS = TimeUnit.SECONDS.toNanos(5);

	private final LongSupplier clock;
	private long generation;
	private Object context;

	public SupervisedAuthority() {
		this(System::nanoTime);
	}

	/** {@code clock} returns nanoseconds, like {@link System#nanoTime()}. */
	public SupervisedAuthority(LongSupplier clock) {
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	/**
	 * Sets the context grants are bound to (an opaque value per world or session; {@code null} when
	 * there is none). A change fences every outstanding grant, even if the old context comes back.
	 */
	public synchronized void enterContext(Object context) {
		if (!Objects.equals(this.context, context)) {
			this.context = context;
			generation++;
		}
	}

	/** STOP: no grant issued before this call admits anything any more. */
	public synchronized void stop() {
		generation++;
	}

	/** Issues a grant for the current context that expires {@code ttlNanos} from now. */
	public synchronized Grant grant(long ttlNanos) {
		if (ttlNanos <= 0 || ttlNanos > MAX_TTL_NANOS) {
			throw new IllegalArgumentException("ttl must be in (0, " + MAX_TTL_NANOS + "] ns, got " + ttlNanos);
		}
		if (context == null) {
			throw new IllegalStateException("no context to grant authority in");
		}
		return new Grant(this, generation, context, clock.getAsLong() + ttlNanos);
	}

	/** Admits the grant's one action. */
	public synchronized Admission admitAction(Grant grant) {
		Admission check = check(grant);
		if (check != Admission.ADMITTED) {
			return check;
		}
		if (grant.actionAdmitted) {
			return Admission.USED;
		}
		grant.actionAdmitted = true;
		return Admission.ADMITTED;
	}

	/** Admits the one input sample of the action the grant already admitted. */
	public synchronized Admission admitInputSample(Grant grant, Cardinal direction) {
		Objects.requireNonNull(direction, "direction");
		Admission check = check(grant);
		if (check != Admission.ADMITTED) {
			return check;
		}
		if (!grant.actionAdmitted) {
			return Admission.NO_ACTION;
		}
		if (grant.sampleAdmitted) {
			return Admission.USED;
		}
		grant.sampleAdmitted = true;
		return Admission.ADMITTED;
	}

	private Admission check(Grant grant) {
		if (grant.owner != this) {
			throw new IllegalArgumentException("grant was issued by another authority");
		}
		if (!Objects.equals(grant.context, context)) {
			return Admission.CONTEXT_CHANGED;
		}
		if (grant.generation != generation) {
			return Admission.STOPPED;
		}
		if (clock.getAsLong() - grant.expiresAt >= 0) {
			return Admission.EXPIRED;
		}
		return Admission.ADMITTED;
	}

	/** Authority for one action. Its state changes only under the issuing authority's lock. */
	public static final class Grant {
		private final SupervisedAuthority owner;
		private final long generation;
		private final Object context;
		private final long expiresAt;
		private boolean actionAdmitted;
		private boolean sampleAdmitted;

		private Grant(SupervisedAuthority owner, long generation, Object context, long expiresAt) {
			this.owner = owner;
			this.generation = generation;
			this.context = context;
			this.expiresAt = expiresAt;
		}
	}
}
