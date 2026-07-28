package dev.configflow.api.operation;

import dev.configflow.application.operation.OperationQueue;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationFailure;
import dev.configflow.domain.operation.OperationId;
import dev.configflow.domain.operation.OperationProgress;
import dev.configflow.domain.operation.OperationState;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operation queue API (docs/07 §2): list, inspect and cancel queued work.
 */
@RestController
@RequestMapping("/api/v1/operations")
public class OperationController
{

	/** Progress of a running operation; {@code percent} is null when indeterminate. */
	public record ProgressResponse(Integer percent, String phase, String detail)
	{

		static ProgressResponse from(OperationProgress progress)
		{
			return progress == null ? null : new ProgressResponse(progress.percent(), progress.phase(), progress.detail());
		}
	}

	/**
	 * Why an operation failed, shaped like the {@code error} of an {@code operation.completed} event so a client has one thing to read either way.
	 */
	public record FailureResponse(String code, String detail, Map<String, String> context)
	{

		static FailureResponse from(OperationFailure failure)
		{
			return failure == null ? null : new FailureResponse(failure.code(), failure.message(), failure.context());
		}
	}

	/** One operation as seen by the client. */
	public record OperationResponse(String operationId, String repositoryId, OperationType type, OperationState state, ProgressResponse progress,
									Instant startedAt, Instant finishedAt, FailureResponse error, List<String> logLines)
	{

		static OperationResponse from(Operation operation)
		{
			RepositoryId repositoryId = operation.repositoryId();
			return new OperationResponse(operation.id().asString(), repositoryId == null ? null : repositoryId.asString(), operation.type(), operation.state(),
					ProgressResponse.from(operation.progress()), operation.startedAt(), operation.finishedAt(), FailureResponse.from(operation.failure()),
					operation.logLines());
		}
	}

	private final OperationQueue queue;

	public OperationController(OperationQueue queue)
	{
		this.queue = queue;
	}

	@GetMapping
	public List<OperationResponse> list(@RequestParam(required = false) String repositoryId, @RequestParam(required = false) OperationState state)
	{
		RepositoryId repository = repositoryId == null || repositoryId.isBlank() ? null : RepositoryId.of(repositoryId);
		return queue.list(repository, state).stream().map(OperationResponse::from).toList();
	}

	@GetMapping("/{id}")
	public OperationResponse show(@PathVariable String id)
	{
		return queue.find(OperationId.of(id)).map(OperationResponse::from).orElseThrow(() -> new NoSuchElementException("Operation not found: " + id));
	}

	/**
	 * Asks an operation to stop.
	 *
	 * <p>204 means the request was recorded, not that the work has already unwound:
	 * cancellation is cooperative, so the operation stops at its next checkpoint.</p>
	 *
	 * @throws NoSuchElementException
	 * 		if there is no such operation, or it already finished
	 */
	@PostMapping("/{id}/cancel")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void cancel(@PathVariable String id)
	{
		OperationId operationId = OperationId.of(id);
		if(!queue.cancel(operationId))
		{
			throw new NoSuchElementException("No cancellable operation with id " + id + " (unknown or already finished)");
		}
	}

	@PostMapping("/{id}/retry")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public OperationResponse retry(@PathVariable String id)
	{
		return queue.retry(OperationId.of(id)).map(OperationResponse::from)
				.orElseThrow(() -> new NoSuchElementException("No retryable operation with id " + id + " (unknown, still running, or already archived)"));
	}
}
