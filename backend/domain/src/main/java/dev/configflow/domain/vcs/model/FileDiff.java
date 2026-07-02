package dev.configflow.domain.vcs.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Structured diff of a single file.
 *
 * @param path    path relative to the repository root (new side)
 * @param oldPath previous path for renames, otherwise {@code null}
 * @param type    kind of change
 * @param binary  true when the file is binary (hunks are empty then)
 * @param hunks   diff hunks, empty for binary files
 */
public record FileDiff(Path path, Path oldPath, ChangeType type, boolean binary, List<DiffHunk> hunks) {

    public FileDiff {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(type, "type must not be null");
        hunks = List.copyOf(hunks == null ? List.of() : hunks);
    }
}
