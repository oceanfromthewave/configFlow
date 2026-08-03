package dev.configflow.api.svn;

import dev.configflow.application.svn.SvnService;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationState;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.vcs.model.RemoteEntry;

import java.nio.file.Path;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SVN-only endpoints (docs/07 §5): path locks and the repository browser.
 */
@RestController
@RequestMapping("/api/v1/repositories/{id}")
public class SvnController
{
	private final SvnService svnService;

	public SvnController(SvnService svnService)
	{
		this.svnService = svnService;
	}

	public record AcceptedResponse(String operationId, OperationType type, OperationState state)
	{
		static AcceptedResponse from(Operation operation)
		{
			return new AcceptedResponse(operation.id().asString(), operation.type(), operation.state());
		}
	}

	public record LockRequest(List<String> paths, String comment)
	{
	}

	public record UnlockRequest(List<String> paths, Boolean breakLock)
	{
	}

	public record RemoteEntryResponse(String name, boolean directory, long size, String lastChanged)
	{
		static RemoteEntryResponse from(RemoteEntry entry)
		{
			return new RemoteEntryResponse(entry.name(), entry.directory(), entry.size(), entry.lastChanged() == null ? null : entry.lastChanged().value());
		}
	}

	@PostMapping("/locks")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AcceptedResponse lock(@PathVariable String id, @RequestBody(required = false) LockRequest body)
	{
		if(body == null)
		{
			throw new IllegalArgumentException("Request body must contain 'paths'");
		}
		return AcceptedResponse.from(svnService.lock(RepositoryId.of(id), toPaths(body.paths()), body.comment()));
	}

	@DeleteMapping("/locks")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public AcceptedResponse unlock(@PathVariable String id, @RequestBody(required = false) UnlockRequest body)
	{
		if(body == null)
		{
			throw new IllegalArgumentException("Request body must contain 'paths'");
		}
		return AcceptedResponse.from(svnService.unlock(RepositoryId.of(id), toPaths(body.paths()), Boolean.TRUE.equals(body.breakLock())));
	}

	@GetMapping("/svn/browse")
	public List<RemoteEntryResponse> browse(@PathVariable String id, @RequestParam(required = false) String url,
			@RequestParam(required = false) String revision)
	{
		// Optional here so a missing 'url' reaches SvnService's own check and comes back
		// as our usual 400, instead of Spring's binding failure turning it into a 500.
		return svnService.browse(RepositoryId.of(id), url, revision).stream().map(RemoteEntryResponse::from).toList();
	}

	/** Rejects absolute paths and {@code ..} escapes: these name entries inside the working copy. */
	private static List<Path> toPaths(List<String> paths)
	{
		if(paths == null)
		{
			throw new IllegalArgumentException("At least one path is required");
		}
		return paths.stream().map(SvnController::toPath).toList();
	}

	private static Path toPath(String path)
	{
		if(path == null || path.isBlank())
		{
			throw new IllegalArgumentException("A path must not be blank");
		}
		Path relative = Path.of(path.trim());
		if(relative.isAbsolute() || relative.normalize().startsWith(".."))
		{
			throw new IllegalArgumentException("Path must stay inside the working copy: " + path);
		}
		return relative.normalize();
	}
}