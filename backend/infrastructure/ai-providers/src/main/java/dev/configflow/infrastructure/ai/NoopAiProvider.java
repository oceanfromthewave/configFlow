package dev.configflow.infrastructure.ai;

import dev.configflow.domain.ai.AiFeature;
import dev.configflow.domain.ai.AiProvider;
import dev.configflow.domain.ai.AiResult;
import dev.configflow.domain.ai.ConflictContext;
import dev.configflow.domain.ai.DiffContext;
import dev.configflow.domain.ai.MergeProposal;
import dev.configflow.domain.ai.ReviewReport;
import java.util.Set;

/**
 * v1 AI provider: supports nothing.
 *
 * <p>Keeps the UI's AI entry points present-but-disabled (empty feature set) and lets
 * the API answer {@code CAPABILITY_NOT_SUPPORTED} without null checks. Real providers
 * (Claude, OpenAI) replace this in v2.</p>
 */
public final class NoopAiProvider implements AiProvider {

    @Override
    public String id() {
        return "noop";
    }

    @Override
    public Set<AiFeature> supportedFeatures() {
        return Set.of();
    }

    @Override
    public AiResult<String> generateCommitMessage(DiffContext context) {
        throw unsupported();
    }

    @Override
    public AiResult<String> summarizeChanges(DiffContext context) {
        throw unsupported();
    }

    @Override
    public AiResult<MergeProposal> resolveConflict(ConflictContext context) {
        throw unsupported();
    }

    @Override
    public AiResult<ReviewReport> reviewCode(DiffContext context) {
        throw unsupported();
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("No AI provider is configured");
    }
}
