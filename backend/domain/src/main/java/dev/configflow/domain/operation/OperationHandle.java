package dev.configflow.domain.operation;

import java.util.Objects;

/**
 * Handle returned immediately when an asynchronous operation is accepted
 * ({@code 202 Accepted} model). Progress and completion arrive as events.
 *
 * @param id   identity of the accepted operation
 * @param type kind of operation
 */
public record OperationHandle(OperationId id, OperationType type) {

    public OperationHandle {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(type, "type must not be null");
    }
}
