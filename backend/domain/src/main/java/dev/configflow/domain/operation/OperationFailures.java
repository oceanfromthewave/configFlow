package dev.configflow.domain.operation;

import dev.configflow.domain.vcs.exception.MergeConflictException;
import dev.configflow.domain.vcs.exception.VcsAuthenticationRequiredException;
import dev.configflow.domain.vcs.exception.VcsNetworkException;
import dev.configflow.domain.vcs.exception.VcsPreconditionException;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The one place a failure is given its name.
 *
 * <p>The same failure reaches a client two ways: as an HTTP problem when it happens on
 * the request thread, and on the event stream when it happens to queued work minutes later. Both have to call it the same thing, or the frontend needs two
 * branches for one cause and they drift apart the first time either is edited.</p>
 */
public final class OperationFailures
{

	public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
	public static final String NOT_FOUND = "NOT_FOUND";
	public static final String CAPABILITY_NOT_SUPPORTED = "CAPABILITY_NOT_SUPPORTED";
	public static final String CONFLICT = "CONFLICT";
	public static final String MERGE_CONFLICT = "MERGE_CONFLICT";
	public static final String VCS_AUTH_REQUIRED = "VCS_AUTH_REQUIRED";
	public static final String VCS_NETWORK_ERROR = "VCS_NETWORK_ERROR";
	public static final String CANCELLED = "CANCELLED";
	public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

	/**
	 * Something failed and the name was not recorded — an operation archived before
	 * failures carried a code. Distinct from {@link #INTERNAL_ERROR}, which is a claim
	 * that the cause was unrecognised; this one only says nobody wrote it down.
	 */
	public static final String UNKNOWN = "UNKNOWN";

	private OperationFailures()
	{
	}

	/**
	 * Names the cause, and carries along whatever answering it requires.
	 *
	 * <p>Anything unrecognised is {@code INTERNAL_ERROR} rather than a guess: a client
	 * that cannot act on a failure should say so plainly instead of offering a remedy that will not work.</p>
	 */
	public static OperationFailure classify(Throwable error)
	{
		return switch(error)
		{
			// Host and protocol travel because the answer to this one is a question —
			// "which credentials?" — and it cannot be asked without them.
			case VcsAuthenticationRequiredException e ->
					new OperationFailure(VCS_AUTH_REQUIRED, describe(e), Map.of("host", e.host(), "protocol", e.protocol()));

			// The conflicted paths stay out of the context for now: they are a list, and
			// what the resolve flow needs from them is decided in M4 with the editor.
			case MergeConflictException e -> OperationFailure.of(MERGE_CONFLICT, describe(e));

			case VcsPreconditionException e -> OperationFailure.of(CONFLICT, describe(e));
			case VcsNetworkException e -> OperationFailure.of(VCS_NETWORK_ERROR, describe(e));
			case NoSuchElementException e -> OperationFailure.of(NOT_FOUND, describe(e));
			case UnsupportedOperationException e -> OperationFailure.of(CAPABILITY_NOT_SUPPORTED, describe(e));
			case IllegalArgumentException e -> OperationFailure.of(VALIDATION_ERROR, describe(e));

			case null -> OperationFailure.of(INTERNAL_ERROR, "Unknown failure");
			default -> OperationFailure.of(INTERNAL_ERROR, describe(error));
		};
	}

	/**
	 * A thrown exception does not always carry a message — an NPE usually does not — and an empty failure tells the user nothing at all. The class name is a
	 * poor message but it is a real one.
	 */
	private static String describe(Throwable error)
	{
		String message = error.getMessage();
		return message != null && !message.isBlank() ? message : error.getClass().getSimpleName();
	}
}