package dev.configflow.api.rebase;

import dev.configflow.application.rebase.RebaseService;
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
 * Rebase control (docs/07 §2). Every endpoint answers {@code 202 Accepted}; a conflict surfaces later as a failed operation, not as a response here.
 */
@RestController
@RequestMapping("/api/v1/repositories/{id}/rebase")
public class RebaseController
{
	private final RebaseService rebaseService;

	public RebaseController(RebaseService rebaseService)
	{
		this.rebaseService = rebaseService;
	}

	public record AcceptedResponse(String operationId, OperationType type, OperationState state)
	{
		static AcceptedResponse from(Operation operation)
		{
			return new AcceptedResponse(operation.id().asString(), operation.type(), operation.state());
		}
	}

	/** POST body; {@code upstream} is the ref the current branch is replayed onto. */
	public record StartRebaseRequest(String upstream)
	{
	}

	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AcceptedResponse start(@PathVariable String id, @RequestBody(required = false) StartRebaseRequest body)
	{
		if(body == null)
		{
			throw new IllegalArgumentException("Request body must contain an 'upstream' field");
		}
		return AcceptedResponse.from(rebaseService.start(RepositoryId.of(id), body.upstream()));
	}

	@PostMapping("/continue")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AcceptedResponse continueRebase(@PathVariable String id)
	{
		return AcceptedResponse.from(rebaseService.continueRebase(RepositoryId.of(id)));
	}

	@PostMapping("/abort")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AcceptedResponse abort(@PathVariable String id)
	{
		return AcceptedResponse.from(rebaseService.abort(RepositoryId.of(id)));
	}

	@PostMapping("/skip")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AcceptedResponse skip(@PathVariable String id)
	{
		return AcceptedResponse.from(rebaseService.skip(RepositoryId.of(id)));
	}
}
