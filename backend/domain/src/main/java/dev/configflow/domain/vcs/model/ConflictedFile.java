package dev.configflow.domain.vcs.model;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A file currently in conflicted state.
 *
 * <p>The base/mine/theirs contents are intentionally not part of this model; they are
 * lazily loaded through {@code ConflictOperations.threeWayContent} to keep status
 * queries cheap.</p>
 *
 * @param path       path relative to the repository root
 * @param resolution current resolution state chosen by the user
 */
public record ConflictedFile(Path path, Resolution resolution) {

    /** How (or whether) the conflict has been resolved. */
    public enum Resolution {
        UNRESOLVED,
        MINE,
        THEIRS,
        MANUAL
    }

    public ConflictedFile {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(resolution, "resolution must not be null");
    }

    /** Convenience factory for a freshly detected, unresolved conflict. */
    public static ConflictedFile unresolved(Path path) {
        return new ConflictedFile(path, Resolution.UNRESOLVED);
    }
}
