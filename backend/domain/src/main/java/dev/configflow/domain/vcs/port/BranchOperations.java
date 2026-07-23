package dev.configflow.domain.vcs.port;

import dev.configflow.domain.vcs.model.MergeRequest;
import dev.configflow.domain.vcs.model.RepositoryHandle;

/**
 * Port for changing branches: create, delete, checkout, merge.
 *
 * <p>Plain synchronous calls. They are slow and rewrite the working tree, so callers put
 * them on the operation queue — but that is the application's decision, not the engine's:
 * a provider only knows how to do the work, and making it hand back an
 * {@code OperationHandle} would mean every provider had to know about queueing.</p>
 *
 * <p>Reading refs has none of these constraints and lives in
 * {@link RefBrowseOperations}.</p>
 */
public interface BranchOperations {

    /** Creates a branch; optionally checks it out immediately. */
    void createBranch(RepositoryHandle repo, String name, String startPoint, boolean checkout);

    /** Deletes a local ({@code remote=false}) or remote branch. */
    void deleteBranch(RepositoryHandle repo, String name, boolean remote, boolean force);

    /** Switches the working tree to the given ref. */
    void checkout(RepositoryHandle repo, String ref);

    /** Merges {@code request.source()} into the current branch. */
    void merge(RepositoryHandle repo, MergeRequest request);
}
