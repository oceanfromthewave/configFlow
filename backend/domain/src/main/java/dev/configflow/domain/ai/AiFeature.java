package dev.configflow.domain.ai;

/**
 * Features an {@link AiProvider} may offer. The UI enables its AI entry points
 * based on the active provider's supported feature set (empty in v1).
 */
public enum AiFeature {
    COMMIT_MESSAGE,
    CHANGE_SUMMARY,
    CONFLICT_RESOLUTION,
    CODE_REVIEW
}
