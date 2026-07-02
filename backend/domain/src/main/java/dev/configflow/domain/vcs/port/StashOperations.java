package dev.configflow.domain.vcs.port;

import dev.configflow.domain.operation.OperationHandle;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.StashEntry;
import java.util.List;

/**
 * Optional port for stashing local changes (Git-only; requires the {@code STASH}
 * capability).
 */
public interface StashOperations {

    /** All stash entries, newest first. */
    List<StashEntry> list(RepositoryHandle repo);

    /** Saves the current working-tree changes as a new stash. */
    OperationHandle save(RepositoryHandle repo, String message, boolean includeUntracked);

    /** Applies stash {@code index} keeping the entry. */
    OperationHandle apply(RepositoryHandle repo, int index);

    /** Applies stash {@code index} and drops it on success. */
    OperationHandle pop(RepositoryHandle repo, int index);

    /** Deletes stash {@code index}. */
    OperationHandle drop(RepositoryHandle repo, int index);
}
