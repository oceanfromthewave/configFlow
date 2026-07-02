package dev.configflow.domain.operation;

/**
 * Progress report of a running operation, streamed to the frontend via SSE.
 *
 * @param percent 0..100, or {@code null} when the total is unknown (indeterminate)
 * @param phase   current phase, e.g. {@code "Receiving objects"}
 * @param detail  free-form detail line, may be {@code null}
 */
public record OperationProgress(Integer percent, String phase, String detail) {

    public OperationProgress {
        if (percent != null && (percent < 0 || percent > 100)) {
            throw new IllegalArgumentException("percent must be within 0..100");
        }
    }

    /** Progress whose total amount of work is unknown. */
    public static OperationProgress indeterminate(String phase) {
        return new OperationProgress(null, phase, null);
    }

    /** True when no percentage can be reported. */
    public boolean isIndeterminate() {
        return percent == null;
    }
}
