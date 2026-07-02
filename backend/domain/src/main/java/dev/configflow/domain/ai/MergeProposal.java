package dev.configflow.domain.ai;

import java.util.Objects;

/**
 * AI-proposed resolution of a conflicted file. Always reviewed by the user before
 * being applied.
 *
 * @param mergedContent proposed full file content
 * @param rationale     short explanation of the choices made
 */
public record MergeProposal(String mergedContent, String rationale) {

    public MergeProposal {
        Objects.requireNonNull(mergedContent, "mergedContent must not be null");
    }
}
