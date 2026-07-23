package dev.configflow.domain.operation;

import dev.configflow.domain.repository.RepositoryId;

/**
 * Outbound port for telling the world what an operation is doing.
 *
 * <p>The queue calls this; the bootstrap adapter turns each call into a server-sent
 * event. Keeping it a port is what lets the queue live in the framework-free application
 * layer, and lets tests assert on the emitted sequence without an HTTP connection.</p>
 *
 * <p>Implementations must be thread-safe and must not throw: they are invoked from
 * worker threads, and a dead SSE client is not a reason to fail the operation.</p>
 */
public interface OperationEvents {

    /** The operation reported progress. */
    void progress(OperationId id, OperationProgress progress);

    /** The operation reached a terminal state; {@code operation} is the final snapshot. */
    void completed(Operation operation);

    /** A console line produced while running (executed command or its output). */
    void consoleLine(RepositoryId repositoryId, OperationId id, String line, String level);

    /** Refs of the repository changed, so any cached ref list is stale. */
    void refsChanged(RepositoryId repositoryId);

    /** The working tree of the repository changed. */
    void workingTreeChanged(RepositoryId repositoryId);

    /** An implementation that discards everything, for tests and headless use. */
    static OperationEvents noop() {
        return new OperationEvents() {
            @Override
            public void progress(OperationId id, OperationProgress progress) {
            }

            @Override
            public void completed(Operation operation) {
            }

            @Override
            public void consoleLine(
                    RepositoryId repositoryId, OperationId id, String line, String level) {
            }

            @Override
            public void refsChanged(RepositoryId repositoryId) {
            }

            @Override
            public void workingTreeChanged(RepositoryId repositoryId) {
            }
        };
    }
}
