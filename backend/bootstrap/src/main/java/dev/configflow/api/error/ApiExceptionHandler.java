package dev.configflow.api.error;

import dev.configflow.domain.vcs.exception.MergeConflictException;
import dev.configflow.domain.vcs.exception.VcsAuthenticationRequiredException;
import dev.configflow.domain.vcs.exception.VcsNetworkException;
import dev.configflow.domain.vcs.exception.VcsPreconditionException;
import dev.configflow.domain.operation.OperationFailures;
import java.net.URI;
import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps exceptions to RFC 9457 Problem Details responses (docs/07 §1).
 *
 * <p>Every body carries a stable machine-readable {@code code} property the frontend
 * branches on, plus a {@code urn:configflow:error:*} type URI. The codes come from
 * {@link OperationFailures} rather than literals here, because the same failure also
 * reaches the client on the event stream when it happens to queued work — one
 * vocabulary, or the frontend needs two branches for one cause.</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler
{

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(NoSuchElementException.class)
	public ProblemDetail handleNotFound(NoSuchElementException e)
	{
		return problem(HttpStatus.NOT_FOUND, "not-found", OperationFailures.NOT_FOUND, "Resource not found", e.getMessage());
	}

	/**
	 * A malformed body or an unparseable query parameter is the caller's mistake, not ours: without this it would fall through to the catch-all and be reported
	 * as 500.
	 */
	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class })
	public ProblemDetail handleMalformedRequest(Exception e)
	{
		return problem(HttpStatus.BAD_REQUEST, "validation", OperationFailures.VALIDATION_ERROR, "Invalid request", "The request could not be read: " + e.getMessage());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ProblemDetail handleBadRequest(IllegalArgumentException e)
	{
		return problem(HttpStatus.BAD_REQUEST, "validation", OperationFailures.VALIDATION_ERROR, "Invalid request", e.getMessage());
	}

	@ExceptionHandler(UnsupportedOperationException.class)
	public ProblemDetail handleCapabilityNotSupported(UnsupportedOperationException e)
	{
		return problem(HttpStatus.BAD_REQUEST, "capability-not-supported", OperationFailures.CAPABILITY_NOT_SUPPORTED, "Operation not supported", e.getMessage());
	}

	/**
	 * The working copy blocks the request — uncommitted changes in the way, an unmerged branch, a lock held elsewhere. Nothing is wrong with the request and
	 * nothing is wrong with the server, so neither 400 nor 500 fits: the user has to change the repository's state, or repeat with force.
	 */
	@ExceptionHandler(VcsPreconditionException.class)
	public ProblemDetail handleConflict(VcsPreconditionException e)
	{
		return problem(HttpStatus.CONFLICT, "conflict", OperationFailures.CONFLICT, "Repository state blocks this operation", e.getMessage());
	}

	/** Content conflicts get their own code: the frontend routes them into the resolve flow rather than showing a plain error. */
	@ExceptionHandler(MergeConflictException.class)
	public ProblemDetail handleMergeConflict(MergeConflictException e)
	{
		ProblemDetail problem = problem(HttpStatus.CONFLICT, "merge-conflict", OperationFailures.MERGE_CONFLICT, "Merge stopped due to conflicts", e.getMessage());
		problem.setProperty("conflictedPaths", e.conflictedPaths().stream().map(path -> path.toString().replace('\\', '/')).toList());
		return problem;
	}

	/**
	 * Credentials are missing or were rejected. 401 rather than 403 because the answer is
	 * "identify yourself again", and the host/protocol travel along so the frontend can
	 * ask for the right ones instead of a generic prompt.
	 */
	@ExceptionHandler(VcsAuthenticationRequiredException.class)
	public ProblemDetail handleAuthRequired(VcsAuthenticationRequiredException e)
	{
		ProblemDetail problem = problem(HttpStatus.UNAUTHORIZED, "vcs-auth-required", OperationFailures.VCS_AUTH_REQUIRED, "Authentication required", e.getMessage());
		problem.setProperty("context", Map.of("host", e.host(), "protocol", e.protocol()));
		return problem;
	}

	/** The remote could not be reached. Ours is the proxy here, so 502 rather than 500. */
	@ExceptionHandler(VcsNetworkException.class)
	public ProblemDetail handleNetwork(VcsNetworkException e)
	{
		return problem(HttpStatus.BAD_GATEWAY, "vcs-network", OperationFailures.VCS_NETWORK_ERROR, "Could not reach the remote", e.getMessage());
	}

	@ExceptionHandler(Exception.class)
	public ProblemDetail handleInternal(Exception e)
	{
		log.error("Unhandled exception in API request", e);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal", OperationFailures.INTERNAL_ERROR, "Internal server error", "An unexpected error occurred");
	}

	private static ProblemDetail problem(HttpStatus status, String typeSlug, String code, String title, String detail)
	{
		ProblemDetail problem = ProblemDetail.forStatus(status);
		problem.setType(URI.create("urn:configflow:error:" + typeSlug));
		problem.setTitle(title);
		problem.setDetail(detail);
		problem.setProperty("code", code);
		return problem;
	}
}
