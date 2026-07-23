package dev.configflow.domain.vcs.port;

import dev.configflow.domain.vcs.model.CloneRequest;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import java.nio.file.Path;

/**
 * Port for acquiring repositories: clone/checkout from a remote or init locally.
 */
public interface RepositoryOperations {

    /**
     * Clones (Git) or checks out (SVN) a remote repository into
     * {@link CloneRequest#localPath()}.
     *
     * <p>Long-running and network-bound, so it reports through {@code monitor} and the
     * caller runs it on the operation queue.</p>
     *
     * @return a handle on the freshly created working copy
     */
    RepositoryHandle cloneRepository(CloneRequest request, OperationMonitor monitor);

    /** Initializes a brand-new local repository at {@code path}. */
    RepositoryHandle init(Path path);
}
