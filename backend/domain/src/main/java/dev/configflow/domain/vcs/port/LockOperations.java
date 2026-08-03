package dev.configflow.domain.vcs.port;

import dev.configflow.domain.vcs.model.RepositoryHandle;
import java.nio.file.Path;
import java.util.List;

/**
 * Optional port for path locking (SVN-only; requires the {@code LOCK} capability).
 *
 * <p>Plain synchronous calls that report through the monitor they are given, same as
 * {@link RemoteSyncOperations}: they talk to the network, so callers put them on the
 * operation queue, but that is the application's decision, not the provider's.</p>
 */
public interface LockOperations {

    /** Locks the given paths with an optional comment. */
    void lock(RepositoryHandle repo, List<Path> paths, String comment, OperationMonitor monitor);

    /** Unlocks the given paths; {@code breakLock} steals locks held by others. */
    void unlock(RepositoryHandle repo, List<Path> paths, boolean breakLock, OperationMonitor monitor);
}
