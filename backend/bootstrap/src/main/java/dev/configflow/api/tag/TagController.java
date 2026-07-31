package dev.configflow.api.tag;

import dev.configflow.application.tag.TagService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tag mutations (docs/07 §2). Both endpoints answer {@code 202 Accepted}; listing tags is part of the refs endpoint, not here.
 */
@RestController
@RequestMapping("/api/v1/repositories/{id}/tags")
public class TagController
{
	private final TagService tagService;

	public TagController(TagService tagService)
	{
		this.tagService = tagService;
	}

	public record AcceptedResponse(String operationId, OperationType type, OperationState state)
	{
		static AcceptedResponse from(Operation operation)
		{
			return new AcceptedResponse(operation.id().asString(), operation.type(), operation.state());
		}
	}

	/** POST body; {@code target} defaults to HEAD, a {@code message} makes it annotated. */
	public record CreateTagRequest(String name, String target, String message)
	{
	}

	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AcceptedResponse create(@PathVariable String id, @RequestBody(required = false) CreateTagRequest body)
	{
		if(body == null)
		{
			throw new IllegalArgumentException("Request body must contain a 'name' field");
		}
		Operation op = tagService.create(RepositoryId.of(id), body.name(), body.target(), body.message());
		return AcceptedResponse.from(op);
	}

	/**
	 * Deletes a tag.
	 *
	 * <p>The name is captured with {@code {*name}} because tag names contain slashes
	 * ({@code release/1.0}); a plain path variable stops at the first one.</p>
	 */
	@DeleteMapping("/{*name}")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AcceptedResponse delete(@PathVariable String id, @PathVariable String name)
	{
		Operation op = tagService.delete(RepositoryId.of(id), stripLeadingSlash(name));
		return AcceptedResponse.from(op);
	}

	/** {@code {*name}} keeps the separator, so {@code release/1.0} arrives as {@code /release/1.0}. */
	private static String stripLeadingSlash(String name)
	{
		return name != null && name.startsWith("/") ? name.substring(1) : name;
	}
}