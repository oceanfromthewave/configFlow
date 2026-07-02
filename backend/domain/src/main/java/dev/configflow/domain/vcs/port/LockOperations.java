package dev.configflow.domain.vcs.port;

import dev.configflow.domain.operation.OperationHandle;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import java.nio.file.Path;
import java.util.List;

/**
 * Optional port for path locking (SVN-only; requires the {@code LOCK} capability).
 */
public interface LockOperations {

    /** Locks the given paths with an optional comment. */
    OperationHandle lock(RepositoryHandle repo, List<Path> paths, String comment);

    /** Unlocks the given paths; {@code breakLock} steals locks held by others. */
    OperationHandle unlock(RepositoryHandle repo, List<Path> paths, boolean breakLock);
}
