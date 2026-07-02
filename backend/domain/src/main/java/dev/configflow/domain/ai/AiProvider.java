package dev.configflow.domain.ai;

import java.util.Set;

/**
 * Port for AI assistance providers (Claude, OpenAI, ...).
 *
 * <p>v1 ships only the interface plus a no-op implementation; the UI keeps its AI
 * entry points disabled while {@link #supportedFeatures()} is empty. Any data sent to
 * a provider must first pass the privacy gate (user consent + secret masking).</p>
 */
public interface AiProvider {

    /** Stable provider id, e.g. {@code "claude"}, {@code "openai"}, {@code "noop"}. */
    String id();

    /** Features this provider supports; drives conditional UI. */
    Set<AiFeature> supportedFeatures();

    /** Generates a commit message for the given diff. */
    AiResult<String> generateCommitMessage(DiffContext context);

    /** Summarizes the given changes in prose. */
    AiResult<String> summarizeChanges(DiffContext context);

    /** Proposes a resolution for a conflicted file. */
    AiResult<MergeProposal> resolveConflict(ConflictContext context);

    /** Reviews the given diff and reports findings. */
    AiResult<ReviewReport> reviewCode(DiffContext context);
}
