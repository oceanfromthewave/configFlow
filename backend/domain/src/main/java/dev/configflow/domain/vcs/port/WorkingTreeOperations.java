package dev.configflow.domain.vcs.port;

import dev.configflow.domain.vcs.model.IgnorePattern;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.WorkingTreeStatus;
import java.nio.file.Path;
import java.util.List;

/**
 * Port for inspecting and manipulating the working tree.
 */
public interface WorkingTreeOperations {

    /** Current staged/unstaged/conflicted state of the working tree. */
    WorkingTreeStatus status(RepositoryHandle repo);

    /** Stages the given paths (requires the {@code STAGING} capability). */
    void stage(RepositoryHandle repo, List<Path> paths);

    /** Unstages the given paths (requires the {@code STAGING} capability). */
    void unstage(RepositoryHandle repo, List<Path> paths);

    /** Discards local modifications of the given paths (destructive; UI confirms first). */
    void discard(RepositoryHandle repo, List<Path> paths);

    /** Adds an ignore rule (.gitignore / svn:ignore). */
    void ignore(RepositoryHandle repo, IgnorePattern pattern);
}
