package dev.configflow.domain.vcs.port;

import dev.configflow.domain.operation.OperationProgress;

/**
 * A long-running provider call's conversation with whoever started it: report progress
 * outward, ask whether to stop.
 *
 * <p>The two belong together because they happen at the same moments. An engine reports
 * between chunks of work, and between chunks is precisely where it can stop safely —
 * abandoning a transfer anywhere else leaves a half-written pack behind. JGit's own
 * {@code ProgressMonitor} pairs them for the same reason.</p>
 *
 * <p>Passed per call rather than held by the provider: a provider has no idea which
 * operation it is running under, and the caller — which submitted the work — does.</p>
 *
 * <p>Implementations must be cheap and thread-safe. A clone reports thousands of times,
 * and providers may call this from their own worker threads.</p>
 */
public interface OperationMonitor {

    /** Reports the latest progress. */
    void onProgress(OperationProgress progress);

    /** True once the caller wants the work to stop at its next safe point. */
    boolean isCancelled();

    /** A monitor that discards progress and never cancels. */
    static OperationMonitor noop() {
        return new OperationMonitor() {
            @Override
            public void onProgress(OperationProgress progress) {
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };
    }
}
