package dev.configflow.domain.ai;

import java.util.List;
import java.util.Objects;

/**
 * AI code-review result for a diff.
 *
 * @param summary  one-paragraph overview
 * @param findings individual findings, each a human-readable line
 */
public record ReviewReport(String summary, List<String> findings) {

    public ReviewReport {
        Objects.requireNonNull(summary, "summary must not be null");
        findings = List.copyOf(findings == null ? List.of() : findings);
    }
}
