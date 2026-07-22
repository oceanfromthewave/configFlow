package dev.configflow.api.repository;

import dev.configflow.application.repository.RepositoryService;
import dev.configflow.domain.repository.Repository;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.vcs.model.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Repository management API: list registered repositories, register a local working copy (VCS auto-detected) and mark one as opened. Thin controller — all
 * logic lives in the application {@link RepositoryService}; this layer only maps to and from DTOs.
 */
@RestController
@RequestMapping("/api/v1/repositories")
public class RepositoryController
{

	/** POST body for registering a local working copy. */
	public record RegisterRequest(String localPath)
	{
	}

	/** Repository as seen by the client (domain types flattened to JSON-friendly forms). */
	public record RepositoryResponse(String id, String name, String localPath, String remoteUrl, VcsType vcsType, String groupName, boolean favorite,
									 Instant createdAt, Instant lastOpenedAt)
	{

		static RepositoryResponse from(Repository repo)
		{
			return new RepositoryResponse(repo.id().asString(), repo.name(), repo.localPath().toString(), repo.remoteUrl(), repo.vcsType(), repo.groupName(),
					repo.favorite(), repo.createdAt(), repo.lastOpenedAt());
		}
	}

	/** One changed file, flattened for the client. */
	public record FileChangeResponse(String path, ChangeType type, String oldPath)
	{
		static FileChangeResponse from(FileChange change)
		{
			Path oldPath = change.oldPath();
			return new FileChangeResponse(change.path().toString(), change.type(), oldPath != null ? oldPath.toString() : null);
		}
	}

	/** One conflicted file, flattened for the client. */
	public record ConflictedFileResponse(String path, ConflictedFile.Resolution resolution)
	{
		static ConflictedFileResponse from(ConflictedFile file)
		{
			return new ConflictedFileResponse(file.path().toString(), file.resolution());
		}
	}

	/** Working-tree status: three buckets of changes. */
	public record WorkingTreeStatusResponse(List<FileChangeResponse> staged, List<FileChangeResponse> unstaged, List<ConflictedFileResponse> conflicted)
	{

		static WorkingTreeStatusResponse from(WorkingTreeStatus status)
		{
			return new WorkingTreeStatusResponse(status.staged().stream().map(FileChangeResponse::from).toList(),
					status.unstaged().stream().map(FileChangeResponse::from).toList(), status.conflicted().stream().map(ConflictedFileResponse::from).toList());
		}
	}

	/** POST body for stage/unstage: working-copy-relative paths */
	public record PathsRequest(List<String> paths)
	{

	}

	/** POST body for creating a commit. */
	public record CommitBody(String message, boolean amend)
	{

	}

	/** The commit that was just created. */
	public record CommitResponse(String revisionId)
	{

	}

	private final RepositoryService repositoryService;

	public RepositoryController(RepositoryService repositoryService)
	{
		this.repositoryService = repositoryService;
	}

	@GetMapping
	public List<RepositoryResponse> list()
	{
		return repositoryService.list().stream().map(RepositoryResponse::from).toList();
	}

	@PostMapping
	public RepositoryResponse register(@RequestBody RegisterRequest request)
	{
		if(request == null || request.localPath() == null || request.localPath().isBlank())
		{
			throw new IllegalArgumentException("Request body must contain a 'localPath' field");
		}
		Repository registered = repositoryService.register(Path.of(request.localPath()));
		return RepositoryResponse.from(registered);
	}

	@PostMapping("/{id}/open")
	public RepositoryResponse open(@PathVariable String id)
	{
		return RepositoryResponse.from(repositoryService.open(RepositoryId.of(id)));
	}

	@GetMapping("/{id}/status")
	public WorkingTreeStatusResponse status(@PathVariable String id)
	{
		return WorkingTreeStatusResponse.from(repositoryService.status(RepositoryId.of(id)));
	}

	@PostMapping("/{id}/stage")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void stage(@PathVariable String id, @RequestBody PathsRequest request)
	{
		repositoryService.stage(RepositoryId.of(id), toPaths(request));
	}

	@PostMapping("/{id}/unstage")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unstage(@PathVariable String id, @RequestBody PathsRequest request)
	{
		repositoryService.unstage(RepositoryId.of(id), toPaths(request));
	}

	@PostMapping("/{id}/commit")
	public CommitResponse commit(@PathVariable String id, @RequestBody CommitBody body)
	{
		if(body == null || body.message() == null)
		{
			throw new IllegalArgumentException("Request body must contain a 'message' field");
		}
		CommitRequest request = new CommitRequest(body.message(), body.amend(), List.of(), false);
		RevisionId created = repositoryService.commit(RepositoryId.of(id), request);
		return new CommitResponse(created.value());
	}

	/** Maps the JSON string paths onto {@link Path}, rejecting an empty selection. */
	private static List<Path> toPaths(PathsRequest request)
	{
		if(request == null || request.paths() == null || request.paths().isEmpty())
		{
			throw new IllegalArgumentException("Request body must contain a non-empty 'paths' array");
		}
		return request.paths().stream().map(path -> {
			if(path == null || path.isBlank())
			{
				throw new IllegalArgumentException("Path must not be blank");
			}
			return Path.of(path);
		}).toList();
	}
}