package dev.configflow.domain.operation;

import dev.configflow.domain.repository.RepositoryId;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for finished-operation history (console/log panel, audit).
 *
 * <p>Implemented by the SQLite adapter in {@code infrastructure:persistence}.
 * Implementations may cap the stored log size per operation.</p>
 */
public interface OperationHistoryStore {

    /** Inserts or updates (upsert by id) the given operation snapshot. */
    void save(Operation operation);

    /** Finds one archived operation. */
    Optional<Operation> findById(OperationId id);

    /** Returns the most recent operations of a repository, newest first. */
    List<Operation> findRecent(RepositoryId repositoryId, int limit);
}
