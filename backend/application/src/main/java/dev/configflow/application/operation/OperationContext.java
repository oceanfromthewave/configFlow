package dev.configflow.application.operation;

import dev.configflow.domain.operation.ConsoleLevel;
import dev.configflow.domain.operation.OperationCancelledException;
import dev.configflow.domain.operation.OperationId;
import dev.configflow.domain.operation.OperationProgress;

/**
 * What a running task can say and ask while the queue executes it.
 *
 * <p>Handed to {@link OperationTask#run(OperationContext)}. Every method is safe to call
 * from the worker thread the task runs on.</p>
 */
public interface OperationContext {

    /** Identity of the operation being executed. */
    OperationId operationId();

    /** Reports progress; delivered to clients as {@code operation.progress}. */
    void progress(OperationProgress progress);

    /** Convenience for a phase whose total amount of work is unknown. */
    default void phase(String phase) {
        progress(OperationProgress.indeterminate(phase));
    }

    /** Appends a console line, delivered as {@code console.line} and archived. */
    void log(String line, ConsoleLevel level);

    /**
     * True once someone asked for this operation to stop.
     *
     * <p>Cancellation is cooperative: nothing interrupts the thread, so a task that never
     * asks runs to completion and is recorded as having succeeded.</p>
     */
    boolean isCancelled();

    /** Stops the task if cancellation was requested; a no-op otherwise. */
    default void throwIfCancelled() {
        if (isCancelled()) {
            throw new OperationCancelledException("Operation " + operationId().asString()
                    + " was cancelled");
        }
    }
}
