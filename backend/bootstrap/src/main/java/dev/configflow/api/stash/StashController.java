package dev.configflow.api.stash;

import dev.configflow.application.stash.StashService;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationState;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.vcs.model.StashEntry;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/repositories/{id}/stashes")
public class StashController
{
	private final StashService stashService;

	public StashController(StashService stashService)
	{
		this.stashService = stashService;
	}

	public record AcceptedResponse(String operationId, OperationType type, OperationState state)
	{
		static AcceptedResponse from(Operation operation)
		{
			return new AcceptedResponse(operation.id().asString(), operation.type(), operation.state());
		}
	}

	public record SaveStashRequest(String message, Boolean includeUntracked)
	{
	}

	public record StashEntryResponse(int index, String message, Instant createdAt)
	{
	}

	@GetMapping
	public List<StashEntryResponse> list(@PathVariable String id)
	{
		List<StashEntry> stashes = stashService.list(RepositoryId.of(id));
		return stashes.stream().map(s -> new StashEntryResponse(s.index(), s.message(), s.createdAt())).toList();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AcceptedResponse save(@PathVariable String id, @RequestBody(required = false) SaveStashRequest body)
	{
		String msg = body != null ? body.message() : null;
		boolean untracked = body != null && Boolean.TRUE.equals(body.includeUntracked());
		Operation op = stashService.save(RepositoryId.of(id), msg, untracked);
		return AcceptedResponse.from(op);
	}

	@PostMapping("/{index}/apply")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AcceptedResponse apply(@PathVariable String id, @PathVariable int index)
	{
		Operation op = stashService.apply(RepositoryId.of(id), index);
		return AcceptedResponse.from(op);
	}

	@PostMapping("/{index}/pop")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AcceptedResponse pop(@PathVariable String id, @PathVariable int index)
	{
		Operation op = stashService.pop(RepositoryId.of(id), index);
		return AcceptedResponse.from(op);
	}

	@DeleteMapping("/{index}")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AcceptedResponse drop(@PathVariable String id, @PathVariable int index)
	{
		Operation op = stashService.drop(RepositoryId.of(id), index);
		return AcceptedResponse.from(op);
	}
}