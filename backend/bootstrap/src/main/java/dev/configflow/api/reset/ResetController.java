package dev.configflow.api.reset;

import dev.configflow.application.reset.ResetService;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationState;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reset (docs/07 §2). Answers {@code 202 Accepted}; the reset itself runs on the operation queue.
 */
@RestController
@RequestMapping("/api/v1/repositories/{id}/reset")
public class ResetController
{
	private final ResetService resetService;

	public ResetController(ResetService resetService)
	{
		this.resetService = resetService;
	}

	public record AcceptedResponse(String operationId, OperationType type, OperationState state)
	{
		static AcceptedResponse from(Operation operation)
		{
			return new AcceptedResponse(operation.id().asString(), operation.type(), operation.state());
		}
	}

	/** POST body; {@code mode} is one of {@code soft}, {@code mixed} (default), {@code hard}. */
	public record ResetRequest(String target, String mode)
	{
	}

	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AcceptedResponse reset(@PathVariable String id, @RequestBody(required = false) ResetRequest body)
	{
		if(body == null)
		{
			throw new IllegalArgumentException("Request body must contain a 'target' field");
		}
		return AcceptedResponse.from(resetService.reset(RepositoryId.of(id), body.target(), body.mode()));
	}
}