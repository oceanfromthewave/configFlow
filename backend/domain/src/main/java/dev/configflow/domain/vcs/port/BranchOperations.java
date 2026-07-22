package dev.configflow.domain.vcs.port;

import dev.configflow.domain.operation.OperationHandle;
import dev.configflow.domain.vcs.model.MergeRequest;
import dev.configflow.domain.vcs.model.RefLabel;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.Revision;

import java.util.List;

/**
 * Port for branch management: list, create, delete, checkout, merge, compare.
 */
public interface BranchOperations
{

	/** All local branches, remote branches and tags as a flat ref list. */
	List<RefLabel> listRefs(RepositoryHandle repo);

	/** Creates a branch; optionally checks it out immediately. */
	OperationHandle createBranch(RepositoryHandle repo, String name, String startPoint, boolean checkout);

	/** Deletes a local ({@code remote=false}) or remote branch. */
	OperationHandle deleteBranch(RepositoryHandle repo, String name, boolean remote, boolean force);

	/** Switches the working tree to the given ref. */
	OperationHandle checkout(RepositoryHandle repo, String ref);

	/** Merges {@code request.source()} into the current branch. */
	OperationHandle merge(RepositoryHandle repo, MergeRequest request);

	/** Revisions contained in {@code target} but not in {@code base}. */
	List<Revision> compare(RepositoryHandle repo, String base, String target);
}
