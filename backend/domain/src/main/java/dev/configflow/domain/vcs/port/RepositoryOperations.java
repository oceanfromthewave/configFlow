package dev.configflow.domain.vcs.port;

import dev.configflow.domain.operation.OperationHandle;
import dev.configflow.domain.vcs.model.CloneRequest;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import java.nio.file.Path;

/**
 * Port for acquiring repositories: clone/checkout from a remote or init locally.
 */
public interface RepositoryOperations {

    /**
     * Clones (Git) or checks out (SVN) a remote repository. Long-running: returns an
     * accepted operation whose progress is streamed as events.
     */
    OperationHandle cloneRepository(CloneRequest request);

    /** Initializes a brand-new local repository at {@code path}. */
    RepositoryHandle init(Path path);
}
