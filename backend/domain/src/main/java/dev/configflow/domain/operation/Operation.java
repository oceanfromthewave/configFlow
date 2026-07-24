package dev.configflow.domain.operation;

import dev.configflow.domain.repository.RepositoryId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A unit of work submitted to the operation queue (clone, fetch, merge, ...).
 *
 * <p>Operations are executed sequentially per repository (never two at once on the
 * same working copy) and their state transitions are streamed to the frontend via
 * SSE. Finished operations are archived in {@code operation_history}.</p>
 *
 * @param id           operation identity
 * @param repositoryId repository the operation runs on, {@code null} for repository-less
 *                     work such as the initial clone target registration
 * @param type         kind of operation
 * @param state        current lifecycle state
 * @param progress     latest progress report, may be {@code null} before start
 * @param startedAt    when execution started, {@code null} while queued
 * @param finishedAt   when a terminal state was reached, {@code null} until then
 * @param failure      why it did not succeed, {@code null} when it did - or when the user cancelled it deliberately, witch needs no explanation
 * @param logLines     console log lines (executed commands and their output)
 */
public record Operation(
        OperationId id,
        RepositoryId repositoryId,
        OperationType type,
        OperationState state,
        OperationProgress progress,
        Instant startedAt,
        Instant finishedAt,
        OperationFailure failure,
        List<String> logLines) {

    public Operation {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(state, "state must not be null");
        logLines = List.copyOf(logLines == null ? List.of() : logLines);
    }

    /** Creates a freshly queued operation. */
    public static Operation queued(RepositoryId repositoryId, OperationType type) {
        return new Operation(
                OperationId.newId(), repositoryId, type, OperationState.QUEUED,
                null, null, null, null, List.of());
    }
}
