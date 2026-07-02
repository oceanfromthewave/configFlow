package dev.configflow.domain.operation;

/**
 * Lifecycle state of an {@link Operation}.
 */
public enum OperationState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    /** True when the operation has reached a final state. */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
