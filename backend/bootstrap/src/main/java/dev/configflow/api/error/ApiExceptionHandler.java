package dev.configflow.api.error;

import java.net.URI;
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
 * branches on, plus a {@code urn:configflow:error:*} type URI.</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler
{

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(NoSuchElementException.class)
	public ProblemDetail handleNotFound(NoSuchElementException e)
	{
		return problem(HttpStatus.NOT_FOUND, "not-found", "NOT_FOUND", "Resource not found", e.getMessage());
	}

	/**
	 * A malformed body or an unparseable query parameter is the caller's mistake, not ours: without this it would fall through to the catch-all and be reported
	 * as 500.
	 */
	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class })
	public ProblemDetail handleMalformedRequest(Exception e)
	{
		return problem(HttpStatus.BAD_REQUEST, "validation", "VALIDATION_ERROR", "Invalid request", "The request could not be read: " + e.getMessage());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ProblemDetail handleBadRequest(IllegalArgumentException e)
	{
		return problem(HttpStatus.BAD_REQUEST, "validation", "VALIDATION_ERROR", "Invalid request", e.getMessage());
	}

	@ExceptionHandler(UnsupportedOperationException.class)
	public ProblemDetail handleCapabilityNotSupported(UnsupportedOperationException e)
	{
		return problem(HttpStatus.BAD_REQUEST, "capability-not-supported", "CAPABILITY_NOT_SUPPORTED", "Operation not supported", e.getMessage());
	}

	@ExceptionHandler(Exception.class)
	public ProblemDetail handleInternal(Exception e)
	{
		log.error("Unhandled exception in API request", e);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "INTERNAL_ERROR", "Internal server error", "An unexpected error occurred");
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
