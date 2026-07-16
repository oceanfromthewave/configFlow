package dev.configflow.domain.vcs.exception;

import java.nio.file.Path;
import java.util.List;

/**
 * An integrating operation (merge, pull, rebase, cherry-pick, update) stopped
 * because of content conflicts. The working tree is left in conflicted state;
 * the application routes the user into the conflict-resolution flow.
 */
public class MergeConflictException extends VcsException {

    private final List<Path> conflictedPaths;

    public MergeConflictException(List<Path> conflictedPaths) {
        this(conflictedPaths, null);
    }

    public MergeConflictException(List<Path> conflictedPaths, Throwable cause) {
        super("Merge stopped due to conflicts in " + conflictedPaths.size() + " file(s)", cause);
        this.conflictedPaths = List.copyOf(conflictedPaths);
    }

    /** Repository-relative paths of the files left in conflicted state. */
    public List<Path> conflictedPaths() {
        return conflictedPaths;
    }
}
