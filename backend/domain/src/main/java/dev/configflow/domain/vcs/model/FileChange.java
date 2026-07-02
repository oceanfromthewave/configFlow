package dev.configflow.domain.vcs.model;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One changed file, either in the working tree or inside a revision.
 *
 * @param path      path relative to the repository root
 * @param type      kind of change
 * @param oldPath   previous path for renames/copies, otherwise {@code null}
 * @param locked    SVN: whether the file is currently locked
 * @param lockOwner SVN: owner of the lock, {@code null} when not locked
 */
public record FileChange(Path path, ChangeType type, Path oldPath, boolean locked, String lockOwner) {

    public FileChange {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(type, "type must not be null");
    }

    /** Convenience factory for the common (non-rename, non-locked) case. */
    public static FileChange of(Path path, ChangeType type) {
        return new FileChange(path, type, null, false, null);
    }
}
