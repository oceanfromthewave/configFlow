package dev.configflow.domain.repository;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the {@link Repository} aggregate.
 *
 * <p>Implemented by the SQLite adapter in {@code infrastructure:persistence}.</p>
 */
public interface RepositoryStore {

    /** Inserts or updates (upsert by id) the given repository. */
    void save(Repository repository);

    /** Finds a repository by its identity. */
    Optional<Repository> findById(RepositoryId id);

    /** Finds a repository by its unique local working-copy path. */
    Optional<Repository> findByLocalPath(Path localPath);

    /** Returns all registered repositories, most recently opened first. */
    List<Repository> findAll();

    /** Removes the registration (never deletes anything from disk). */
    void delete(RepositoryId id);
}
