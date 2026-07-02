package dev.configflow.domain.vcs.port;

import dev.configflow.domain.operation.OperationHandle;
import dev.configflow.domain.vcs.model.RepositoryHandle;

/**
 * Optional port for rebasing (Git-only; requires the {@code REBASE} capability).
 */
public interface RebaseOperations {

    /** Starts rebasing the current branch onto {@code upstream} (or {@code onto} if given). */
    OperationHandle start(RepositoryHandle repo, String upstream, String onto);

    /** Continues a paused rebase after conflicts were resolved. */
    OperationHandle continueRebase(RepositoryHandle repo);

    /** Aborts the in-progress rebase and restores the pre-rebase state. */
    OperationHandle abort(RepositoryHandle repo);

    /** Skips the current commit and continues. */
    OperationHandle skip(RepositoryHandle repo);
}
