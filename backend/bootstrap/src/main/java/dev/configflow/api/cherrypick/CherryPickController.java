package dev.configflow.api.cherrypick;

import dev.configflow.application.cherrypick.CherryPickService;
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
 * Cherry-pick (docs/07 §2). Answers {@code 202 Accepted}; a conflict surfaces later as a failed operation, not as a response here.
 */
@RestController
@RequestMapping("/api/v1/repositories/{id}/cherry-pick")
public class CherryPickController
{
	private final CherryPickService cherryPickService;

	public CherryPickController(CherryPickService cherryPickService)
	{
		this.cherryPickService = cherryPickService;
	}

	public record AcceptedResponse(String operationId, OperationType type, OperationState state)
	{
		static AcceptedResponse from(Operation operation)
		{
			return new AcceptedResponse(operation.id().asString(), operation.type(), operation.state());
		}
	}

	/** POST body; {@code revisions} are replayed in the order given. */
	public record CherryPickRequest(List<String> revisions)
	{
	}

	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AcceptedResponse cherryPick(@PathVariable String id, @RequestBody(required = false) CherryPickRequest body)
	{
		if(body == null)
		{
			throw new IllegalArgumentException("Request body must contain a 'revisions' field");
		}
		return AcceptedResponse.from(cherryPickService.cherryPick(RepositoryId.of(id), body.revisions()));
	}
}
