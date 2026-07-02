package dev.configflow.domain.ai;

import java.util.Objects;

/**
 * Diff payload sent to an AI provider.
 *
 * <p>Privacy gate: callers must obtain user consent and pass the diff through the
 * secret-masking rules before constructing this context.</p>
 *
 * @param unifiedDiff the (already masked) unified diff text
 * @param branch      current branch name for extra context, may be {@code null}
 */
public record DiffContext(String unifiedDiff, String branch) {

    public DiffContext {
        Objects.requireNonNull(unifiedDiff, "unifiedDiff must not be null");
    }
}
