package dev.configflow.api.repository;

import dev.configflow.application.branch.BranchService;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationState;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Branch mutations (docs/07 §2). Every endpoint answers {@code 202 Accepted} with the queued operation; progress and completion arrive over SSE.
 */
@RestController
@RequestMapping("/api/v1/repositories")
public class BranchController
{

	/** The 202 body: enough for the client to follow the operation. */
	public record AcceptedResponse(String operationId, OperationType type, OperationState state)
	{

		static AcceptedResponse from(Operation operation)
		{
			return new AcceptedResponse(operation.id().asString(), operation.type(), operation.state());
		}
	}

	/** POST body for switching branches. */
	public record CheckoutRequest(String ref)
	{
	}

	/** POST body for creating a branch. */
	public record CreateBranchRequest(String name, String startPoint, Boolean checkout)
	{
	}

	public record MergeRequest(String source, boolean fastForwardOnly, boolean squash)
	{

	}

	private final BranchService branchService;

	public BranchController(BranchService branchService)
	{
		this.branchService = branchService;
	}

	@PostMapping("/{id}/checkout")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AcceptedResponse checkout(@PathVariable String id, @RequestBody(required = false) CheckoutRequest request)
	{
		String ref = request == null ? null : request.ref();
		return AcceptedResponse.from(branchService.checkout(RepositoryId.of(id), ref));
	}

	@PostMapping("/{id}/branches")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AcceptedResponse createBranch(@PathVariable String id, @RequestBody(required = false) CreateBranchRequest request)
	{
		if(request == null)
		{
			throw new IllegalArgumentException("Request body must contain a 'name' field");
		}
		return AcceptedResponse.from(
				branchService.createBranch(RepositoryId.of(id), request.name(), request.startPoint(), Boolean.TRUE.equals(request.checkout())));
	}

	@PostMapping("/{id}/merge")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AcceptedResponse merge(@PathVariable String id, @RequestBody(required = false) MergeRequest request)
	{
		if(request == null)
		{
			throw new IllegalArgumentException("Request body must contain a 'source' field");
		}
		return AcceptedResponse.from(branchService.merge(RepositoryId.of(id), request.source(),
				request.fastForwardOnly(), request.squash()));
	}

	/**
	 * Deletes a branch.
	 *
	 * <p>The name is captured with {@code {*name}} because branch names contain slashes
	 * ({@code feature/x}); a plain path variable stops at the first one.</p>
	 */
	@DeleteMapping("/{id}/branches/{*name}")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AcceptedResponse deleteBranch(@PathVariable String id, @PathVariable String name, @RequestParam(defaultValue = "false") boolean remote,
			@RequestParam(defaultValue = "false") boolean force)
	{
		return AcceptedResponse.from(branchService.deleteBranch(RepositoryId.of(id), stripLeadingSlash(name), remote, force));
	}

	/** {@code {*name}} keeps the separator, so {@code feature/x} arrives as {@code /feature/x}. */
	private static String stripLeadingSlash(String name)
	{
		return name != null && name.startsWith("/") ? name.substring(1) : name;
	}
}
