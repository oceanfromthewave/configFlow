package dev.configflow.api.revert;

import dev.configflow.application.revert.RevertService;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationState;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Revert (docs/07 §2). Answers {@code 202 Accepted}; a conflict surfaces later as a failed operation, not as a response here.
 */
@RestController
@RequestMapping("/api/v1/repositories/{id}/revert")
public class RevertController
{
	private final RevertService revertService;

	public RevertController(RevertService revertService)
	{
		this.revertService = revertService;
	}

	public record AcceptedResponse(String operationId, OperationType type, OperationState state)
	{
		static AcceptedResponse from(Operation operation)
		{
			return new AcceptedResponse(operation.id().asString(), operation.type(), operation.state());
		}
	}

	/** POST body; {@code revisions} are undone in the order given. */
	public record RevertRequest(List<String> revisions)
	{
	}

	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AcceptedResponse revert(@PathVariable String id, @RequestBody(required = false) RevertRequest body)
	{
		if(body == null)
		{
			throw new IllegalArgumentException("Request body must contain a 'revisions' field");
		}
		return AcceptedResponse.from(revertService.revert(RepositoryId.of(id), body.revisions()));
	}
}
