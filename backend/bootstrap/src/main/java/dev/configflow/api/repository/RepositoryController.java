package dev.configflow.api.repository;

import dev.configflow.application.repository.RepositoryService;
import dev.configflow.domain.repository.Repository;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.vcs.model.*;

import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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
			return new FileChangeResponse(apiPath(change.path()), change.type(), apiPath(change.oldPath()));
		}
	}

	/** One conflicted file, flattened for the client. */
	public record ConflictedFileResponse(String path, ConflictedFile.Resolution resolution)
	{
		static ConflictedFileResponse from(ConflictedFile file)
		{
			return new ConflictedFileResponse(apiPath(file.path()), file.resolution());
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

	/** Author, flattened for the client. */
	public record AuthorResponse(String name, String email)
	{
		static AuthorResponse from(Author author)
		{
			return new AuthorResponse(author.name(), author.email());
		}
	}

	/** A branch/tag/HEAD decoration on a revision. */
	public record RefLabelResponse(RefLabel.Kind kind, String name)
	{
		static RefLabelResponse from(RefLabel label)
		{
			return new RefLabelResponse(label.kind(), label.name());
		}
	}

	/** One revision; ids are plain strings rather than nested objects. */
	public record RevisionResponse(String id, List<String> parents, AuthorResponse author, Instant timestamp, String message, List<RefLabelResponse> labels)
	{
		static RevisionResponse from(Revision revision)
		{
			return new RevisionResponse(revision.id().value(), revision.parents().stream().map(RevisionId::value).toList(),
					AuthorResponse.from(revision.author()), revision.timestamp(), revision.message(),
					revision.labels().stream().map(RefLabelResponse::from).toList());
		}
	}

	/** One page of history plus the cursor that fetches the next one. */
	public record HistoryPageResponse(List<RevisionResponse> items, String nextCursor)
	{
		static HistoryPageResponse from(Page<Revision> page)
		{
			return new HistoryPageResponse(page.items().stream().map(RevisionResponse::from).toList(), page.nextCursor());
		}
	}

	/** One hunk of a unified diff; lines keep their {@code ' '}/{@code '+'}/{@code '-'} prefix. */
	public record DiffHunkResponse(int oldStart, int oldCount, int newStart, int newCount, List<String> lines)
	{
		static DiffHunkResponse from(DiffHunk hunk)
		{
			return new DiffHunkResponse(hunk.oldStart(), hunk.oldCount(), hunk.newStart(), hunk.newCount(), hunk.lines());
		}
	}

	/** Structured diff of one file; {@code hunks} is empty for binary and unchanged files. */
	public record FileDiffResponse(String path, String oldPath, ChangeType type, boolean binary, List<DiffHunkResponse> hunks)
	{
		static FileDiffResponse from(FileDiff diff)
		{
			return new FileDiffResponse(apiPath(diff.path()), apiPath(diff.oldPath()), diff.type(), diff.binary(),
					diff.hunks().stream().map(DiffHunkResponse::from).toList());
		}
	}

	/** Branches, tags and the HEAD pointer of one repository. */
	public record RefListResponse(List<RefLabelResponse> refs)
	{
		static RefListResponse from(List<RefLabel> refs)
		{
			return new RefListResponse(refs.stream().map(RefLabelResponse::from).toList());
		}
	}

	/** Revisions present in one ref but not the other. */
	public record CompareResponse(List<RevisionResponse> revisions)
	{
		static CompareResponse from(List<Revision> revisions)
		{
			return new CompareResponse(revisions.stream().map(RevisionResponse::from).toList());
		}
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

	@PostMapping("/init")
	public RepositoryResponse init(@RequestBody(required = false) RegisterRequest request)
	{
		if(request == null || request.localPath() == null || request.localPath().isBlank())
		{
			throw new IllegalArgumentException("A 'localPath' is required");
		}
		return RepositoryResponse.from(repositoryService.initialize(Path.of(request.localPath().trim())));
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

	@GetMapping("/{id}/history")
	public HistoryPageResponse history(@PathVariable String id, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit,
			@RequestParam(required = false) String branch, @RequestParam(required = false) String author, @RequestParam(required = false) String message,
			@RequestParam(required = false) String path, @RequestParam(required = false) String from, @RequestParam(required = false) String to)
	{
		String trimmedPath = trimmed(path);
		HistoryQuery query = new HistoryQuery(trimmed(cursor), limit, trimmed(branch), trimmed(author), trimmed(message),
				trimmedPath != null ? Path.of(trimmedPath) : null, instant(from, "from"), instant(to, "to"));
		return HistoryPageResponse.from(repositoryService.history(RepositoryId.of(id), query));
	}

	@GetMapping("/{id}/commits/{revision}")
	public RevisionResponse show(@PathVariable String id, @PathVariable String revision)
	{
		return RevisionResponse.from(repositoryService.show(RepositoryId.of(id), new RevisionId(revision)));
	}

	@GetMapping("/{id}/commits/{revision}/diff")
	public FileDiffResponse diffInCommit(@PathVariable String id, @PathVariable String revision, @RequestParam String path)
	{
		return FileDiffResponse.from(repositoryService.diffInCommit(RepositoryId.of(id), new RevisionId(revision), toPath(path)));
	}

	@GetMapping("/{id}/commits/{revision}/changes")
	public List<FileChangeResponse> changes(@PathVariable String id, @PathVariable String revision)
	{
		return repositoryService.changesIn(RepositoryId.of(id), new RevisionId(revision)).stream().map(FileChangeResponse::from).toList();
	}

	/** Blank query parameters mean "no filter", not "match the empty string". */
	private static String trimmed(String value)
	{
		if(value == null)
		{
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/** Parses an ISO-8601 instant, reporting a bad value as a 400 rather than a 500. */
	private static Instant instant(String value, String field)
	{
		String trimmed = trimmed(value);
		if(trimmed == null)
		{
			return null;
		}
		try
		{
			return Instant.parse(trimmed);
		}
		catch(DateTimeParseException e)
		{
			throw new IllegalArgumentException("'" + field + "' must be an ISO-8601 instant, was: " + trimmed, e);
		}
	}

	/**
	 * Diff of one working-copy file.
	 *
	 * @param staged
	 *        {@code true} shows what a commit would record (HEAD against the index), {@code false} what is still unstaged (index against the working tree)
	 */
	@GetMapping("/{id}/diff")
	public FileDiffResponse diffWorking(@PathVariable String id, @RequestParam(required = false) String path,
			@RequestParam(defaultValue = "false") boolean staged)
	{
		return FileDiffResponse.from(repositoryService.diffWorking(RepositoryId.of(id), toPath(path), staged));
	}

	@GetMapping("/{id}/refs")
	public RefListResponse listRefs(@PathVariable String id)
	{
		return RefListResponse.from(repositoryService.listRefs(RepositoryId.of(id)));
	}

	@GetMapping("/{id}/compare")
	public CompareResponse compare(@PathVariable String id, @RequestParam(required = false) String base, @RequestParam(required = false) String target)
	{
		return CompareResponse.from(repositoryService.compare(RepositoryId.of(id), base, target));
	}

	/**
	 * Maps one client-supplied path onto {@link Path}, rejecting a blank value.
	 *
	 * <p>Declared optional on purpose: a missing required parameter makes Spring fail
	 * before the method runs, which would surface as a 500 instead of a 400.</p>
	 */
	private static Path toPath(String path)
	{
		String trimmed = trimmed(path);
		if(trimmed == null)
		{
			throw new IllegalArgumentException("A 'path' parameter is required");
		}
		return Path.of(trimmed);
	}

	/**
	 * Renders a path the way the API talks about paths: relative to the repository root and always with {@code /}, so a client never sees Windows separators.
	 */
	private static String apiPath(Path path)
	{
		return path == null ? null : path.toString().replace('\\', '/');
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