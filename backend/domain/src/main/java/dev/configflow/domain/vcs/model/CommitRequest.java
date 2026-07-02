package dev.configflow.domain.vcs.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Request to create a commit.
 *
 * @param message  commit message
 * @param amend    Git: amend the previous commit (requires {@code AMEND} capability)
 * @param paths    SVN: explicit path selection (SVN commits paths, not a staging area);
 *                 empty means "all modified paths"
 * @param keepLock SVN: keep locks after committing
 */
public record CommitRequest(String message, boolean amend, List<Path> paths, boolean keepLock) {

    public CommitRequest {
        Objects.requireNonNull(message, "message must not be null");
        paths = List.copyOf(paths == null ? List.of() : paths);
    }

    /** Simple whole-working-tree commit. */
    public static CommitRequest of(String message) {
        return new CommitRequest(message, false, List.of(), false);
    }
}
