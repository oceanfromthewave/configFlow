package dev.configflow.application.repository;

import dev.configflow.domain.repository.Repository;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.repository.RepositoryStore;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.WorkingTreeStatus;
import dev.configflow.domain.vcs.port.VcsProvider;
import dev.configflow.domain.vcs.port.VcsProviderRegistry;
import dev.configflow.domain.vcs.port.WorkingTreeOperations;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Use case: register, list and inspect repositories.
 *
 * <p>Pure Java (no framework types). It orchestrates two domain ports — the
 * {@link RepositoryStore} for app metadata and the {@link VcsProviderRegistry} for the
 * live VCS engine — and never touches JGit or SQLite directly.</p>
 */
public final class RepositoryService {

	private final RepositoryStore repositoryStore;
	private final VcsProviderRegistry providers;
	private final Clock clock;

	public RepositoryService(
			RepositoryStore repositoryStore, VcsProviderRegistry providers, Clock clock) {
		this.repositoryStore = Objects.requireNonNull(repositoryStore, "repositoryStore");
		this.providers = Objects.requireNonNull(providers, "providers");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	/**
	 * Registers the working copy at {@code localPath}, auto-detecting its VCS.
	 *
	 * @throws IllegalArgumentException if the path is not a supported working copy, or is
	 *                                  already registered
	 */
	public Repository register(Path localPath) {
		Path canonical = localPath.toAbsolutePath().normalize();

		VcsProvider provider = providers.detect(canonical).orElseThrow(() ->
				new IllegalArgumentException("Not a supported repository: " + canonical));

		repositoryStore.findByLocalPath(canonical).ifPresent(existing -> {
			throw new IllegalArgumentException("Repository already registered: " + canonical);
		});

		Repository repository = Repository.register(
				deriveName(canonical), canonical, null, provider.type(), clock.instant());
		repositoryStore.save(repository);
		return repository;
	}

	/** All registered repositories, most recently opened first. */
	public List<Repository> list() {
		return repositoryStore.findAll();
	}

	/** Marks a repository as opened now (updates its last-opened time). */
	public Repository open(RepositoryId id) {
		Repository repository = require(id).opened(clock.instant());
		repositoryStore.save(repository);
		return repository;
	}

	/**
	 * Reads the live working-tree status of a registered repository.
	 *
	 * @throws NoSuchElementException        if no repository has the given id
	 * @throws UnsupportedOperationException if the provider exposes no working tree
	 */
	public WorkingTreeStatus status(RepositoryId id) {
		Repository repository = require(id);
		VcsProvider provider = providers.byType(repository.vcsType()).orElseThrow(() ->
				new IllegalStateException("No provider registered for " + repository.vcsType()));
		if (!(provider instanceof WorkingTreeOperations workingTree)) {
			throw new UnsupportedOperationException(
					repository.vcsType() + " has no working-tree operations");
		}
		RepositoryHandle handle = provider.open(repository.localPath());
		return workingTree.status(handle);
	}

	private Repository require(RepositoryId id) {
		return repositoryStore.findById(id).orElseThrow(() ->
				new NoSuchElementException("Repository not found: " + id.asString()));
	}

	private static String deriveName(Path localPath) {
		Path fileName = localPath.getFileName();
		return fileName != null ? fileName.toString() : localPath.toString();
	}
}
