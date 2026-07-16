package dev.configflow.domain.vcs.port;

import dev.configflow.domain.operation.OperationId;
import dev.configflow.domain.operation.OperationProgress;

/**
 * Callback through which a {@link VcsProvider} reports progress of a long-running
 * operation (clone, fetch, push, ...).
 *
 * <p>Providers receive a listener at construction time; the application layer
 * bridges it to operation events / SSE. Implementations must be cheap and
 * thread-safe: providers may invoke them from worker threads at a high rate.</p>
 */
@FunctionalInterface
public interface ProgressListener {

    /** Reports the latest progress of the operation identified by {@code operationId}. */
    void onProgress(OperationId operationId, OperationProgress progress);

    /** A listener that discards all progress reports. */
    static ProgressListener noop() {
        return (operationId, progress) -> {
        };
    }
}
