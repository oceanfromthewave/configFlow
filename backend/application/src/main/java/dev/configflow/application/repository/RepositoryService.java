package dev.configflow.application.repository;

import dev.configflow.domain.repository.Repository;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.repository.RepositoryStore;
import dev.configflow.domain.vcs.capability.VcsCapability;
import dev.configflow.domain.vcs.model.*;
import dev.configflow.domain.vcs.port.CommitOperations;
import dev.configflow.domain.vcs.port.VcsProvider;
import dev.configflow.domain.vcs.port.VcsProviderRegistry;
import dev.configflow.domain.vcs.port.WorkingTreeOperations;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Use case: register, list and inspect repositories, and drive the staging/commit flow.
 *
 * <p>Pure Java (no framework types). It orchestrates two domain ports — the
 * {@link RepositoryStore} for app metadata and the {@link VcsProviderRegistry} for the live VCS engine — and never touches JGit or SQLite directly.</p>
 */
public final class RepositoryService
{

	private final RepositoryStore repositoryStore;
	private final VcsProviderRegistry providers;
	private final Clock clock;

	public RepositoryService(RepositoryStore repositoryStore, VcsProviderRegistry providers, Clock clock)
	{
		this.repositoryStore = Objects.requireNonNull(repositoryStore, "repositoryStore");
		this.providers = Objects.requireNonNull(providers, "providers");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	/**
	 * Registers the working copy at {@code localPath}, auto-detecting its VCS.
	 *
	 * @throws IllegalArgumentException
	 * 		if the path is not a supported working copy, or is already registered
	 */
	public Repository register(Path localPath)
	{
		Path canonical = localPath.toAbsolutePath().normalize();

		VcsProvider provider = providers.detect(canonical).orElseThrow(() -> new IllegalArgumentException("Not a supported repository: " + canonical));

		repositoryStore.findByLocalPath(canonical).ifPresent(existing -> {
			throw new IllegalArgumentException("Repository already registered: " + canonical);
		});

		Repository repository = Repository.register(deriveName(canonical), canonical, null, provider.type(), clock.instant());
		repositoryStore.save(repository);
		return repository;
	}

	/** All registered repositories, most recently opened first. */
	public List<Repository> list()
	{
		return repositoryStore.findAll();
	}

	/** Marks a repository as opened now (updates its last-opened time). */
	public Repository open(RepositoryId id)
	{
		Repository repository = require(id).opened(clock.instant());
		repositoryStore.save(repository);
		return repository;
	}

	/**
	 * Reads the live working-tree status of a registered repository.
	 *
	 * @throws NoSuchElementException
	 * 		if no repository has the given id
	 * @throws UnsupportedOperationException
	 * 		if the provider exposes no working tree
	 */
	public WorkingTreeStatus status(RepositoryId id)
	{
		Opened<WorkingTreeOperations> opened = openWith(id, WorkingTreeOperations.class);
		return opened.operations().status(opened.handle());
	}

	/** Stages the given working-copy-relative paths. */
	public void stage(RepositoryId id, List<Path> paths)
	{
		List<Path> safePaths = requirePaths(paths);
		Opened<WorkingTreeOperations> opened = openWith(id, WorkingTreeOperations.class);
		opened.operations().stage(opened.handle(), safePaths);
	}

	/** Removes the given working-copy-relative paths from the staging area. */
	public void unstage(RepositoryId id, List<Path> paths)
	{
		List<Path> safePaths = requirePaths(paths);
		Opened<WorkingTreeOperations> opened = openWith(id, WorkingTreeOperations.class);
		opened.operations().unstage(opened.handle(), safePaths);
	}

	/**
	 * Creates a commit from whatever the provider considers selected content.
	 *
	 * @throws IllegalArgumentException
	 * 		if the message is blank
	 * @throws UnsupportedOperationException
	 * 		if amending is requested but unsupported
	 */
	public RevisionId commit(RepositoryId id, CommitRequest request)
	{
		Objects.requireNonNull(request, "request");
		if(request.message().isBlank())
		{
			throw new IllegalArgumentException("Commit message must not be blank");
		}
		Opened<CommitOperations> opened = openWith(id, CommitOperations.class);
		if(request.amend() && !opened.provider().capabilities().contains(VcsCapability.AMEND))
		{
			throw new UnsupportedOperationException(opened.provider().type() + " does not support amending");
		}
		return opened.operations().commit(opened.handle(), request);
	}

	/** Hard ceiling on one history page: the client picks the size, we cap the cost. */
	public static final int MAX_HISTORY_LIMIT = 200;

	/**
	 * Reads one page of history, newest first.
	 *
	 * @throws IllegalArgumentException
	 * 		if the requested page is larger than {@link #MAX_HISTORY_LIMIT}
	 * @throws UnsupportedOperationException
	 * 		if the provider cannot read history
	 */
	public Page<Revision> history(RepositoryId id, HistoryQuery query)
	{
		Objects.requireNonNull(query, "query");
		if(query.limit() > MAX_HISTORY_LIMIT)
		{
			throw new IllegalArgumentException("limit must not exceed " + MAX_HISTORY_LIMIT + ", was " + query.limit());
		}
		Opened<CommitOperations> opened = openWith(id, CommitOperations.class);
		return opened.operations().history(opened.handle(), query);
	}

	/**
	 * Loads a single revision.
	 *
	 * @throws NoSuchElementException
	 * 		if the repository or the revision does not exist
	 */
	public Revision show(RepositoryId id, RevisionId revision)
	{
		Objects.requireNonNull(revision, "revision");
		Opened<CommitOperations> opened = openWith(id, CommitOperations.class);
		return opened.operations().show(opened.handle(), revision);
	}

	/**
	 * Resolves a registered repository into a live handle plus the requested operation port.
	 *
	 * @throws NoSuchElementException
	 * 		if no repository has the given id
	 * @throws UnsupportedOperationException
	 * 		if its provider does not implement {@code port}
	 */
	private <T> Opened<T> openWith(RepositoryId id, Class<T> port)
	{
		Repository repository = require(id);
		VcsProvider provider = providers.byType(repository.vcsType())
				.orElseThrow(() -> new IllegalStateException("No provider registered for " + repository.vcsType()));
		if(!port.isInstance(provider))
		{
			throw new UnsupportedOperationException(repository.vcsType() + " has no " + port.getSimpleName());
		}
		return new Opened<>(provider, provider.open(repository.localPath()), port.cast(provider));
	}

	/** A repository resolved down to one live operation port. */
	private record Opened<T>(VcsProvider provider, RepositoryHandle handle, T operations)
	{
	}

	/**
	 * Rejects anything that could point outside the working copy: the paths come from a client, and both absolute paths and {@code ..} segments would escape
	 * it.
	 */
	private static List<Path> requirePaths(List<Path> paths)
	{
		if(paths == null || paths.isEmpty())
		{
			throw new IllegalArgumentException("At least one path is required");
		}
		for(Path path : paths)
		{
			if(path.isAbsolute() || path.normalize().startsWith(".."))
			{
				throw new IllegalArgumentException("Path must be inside the working copy: " + path);
			}
		}
		return List.copyOf(paths);
	}

	private Repository require(RepositoryId id)
	{
		return repositoryStore.findById(id).orElseThrow(() -> new NoSuchElementException("Repository not found: " + id.asString()));
	}

	private static String deriveName(Path localPath)
	{
		Path fileName = localPath.getFileName();
		return fileName != null ? fileName.toString() : localPath.toString();
	}
}
