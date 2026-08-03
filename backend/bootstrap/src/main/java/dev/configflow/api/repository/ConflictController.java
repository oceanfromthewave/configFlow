package dev.configflow.api.repository;

import dev.configflow.application.repository.RepositoryService;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.vcs.model.ConflictedFile;
import dev.configflow.domain.vcs.model.ThreeWayContent;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Conflict inspection and resolution (docs/07 §Conflicts).
 *
 * <p>All three endpoints answer synchronously: listing and reading the index stages are
 * reads, and resolving is a local index write. Nothing here goes through the operation
 * queue, so there is no 202 and no operation id.</p>
 */
@RestController
@RequestMapping("/api/v1/repositories/{id}/conflicts")
public class ConflictController
{
	private final RepositoryService repositoryService;

	public ConflictController(RepositoryService repositoryService)
	{
		this.repositoryService = repositoryService;
	}

	/** The three sides of one conflicted file; a side is null when it has no content there. */
	public record ThreeWayContentResponse(String base, String mine, String theirs)
	{
		static ThreeWayContentResponse from(ThreeWayContent content)
		{
			return new ThreeWayContentResponse(content.base(), content.mine(), content.theirs());
		}
	}

	/** POST body; {@code content} is required only when {@code resolution} is MANUAL. */
	public record ResolveRequest(String path, ConflictedFile.Resolution resolution, String content)
	{
	}

	@GetMapping
	public List<RepositoryController.ConflictedFileResponse> list(@PathVariable String id)
	{
		return repositoryService.listConflicts(RepositoryId.of(id)).stream().map(RepositoryController.ConflictedFileResponse::from).toList();
	}

	@GetMapping("/content")
	public ThreeWayContentResponse content(@PathVariable String id, @RequestParam(required = false) String path)
	{
		return ThreeWayContentResponse.from(repositoryService.threeWayContent(RepositoryId.of(id), RepositoryController.toPath(path)));
	}

	@PostMapping("/resolve")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void resolve(@PathVariable String id, @RequestBody(required = false) ResolveRequest body)
	{
		if(body == null)
		{
			throw new IllegalArgumentException("Request body must contain 'path' and 'resolution'");
		}
		repositoryService.resolve(RepositoryId.of(id), RepositoryController.toPath(body.path()), body.resolution(), body.content());
	}
}
