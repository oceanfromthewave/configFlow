package dev.configflow.domain.operation;

/**
 * Thrown by a task that noticed it was cancelled and stopped early.
 *
 * <p>Exists so the queue can tell a deliberate stop from a failure: without it a
 * cancelled operation would be archived as {@code FAILED} with a confusing message.</p>
 */
public class OperationCancelledException extends RuntimeException {

    public OperationCancelledException(String message) {
        super(message);
    }
}
