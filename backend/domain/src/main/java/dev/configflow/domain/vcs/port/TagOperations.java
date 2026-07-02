package dev.configflow.domain.vcs.port;

import dev.configflow.domain.operation.OperationHandle;
import dev.configflow.domain.vcs.model.RefLabel;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.RevisionId;
import java.util.List;

/**
 * Optional port for tag management (requires the {@code TAG} capability).
 */
public interface TagOperations {

    /** All tags. */
    List<RefLabel> list(RepositoryHandle repo);

    /** Creates a tag on {@code target}; {@code message != null} makes it annotated. */
    OperationHandle create(RepositoryHandle repo, String name, RevisionId target, String message);

    /** Deletes a tag. */
    OperationHandle delete(RepositoryHandle repo, String name);
}
