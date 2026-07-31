package dev.configflow.domain.vcs.port;

import dev.configflow.domain.vcs.model.RepositoryHandle;

/**
 * Optional port for rebasing (Git-only; requires the {@code REBASE} capability).
 *
 * <p>Scoped to a plain {@code git rebase <upstream>}: JGit's {@code RebaseCommand}
 * has no {@code --onto} equivalent, so that flag is deliberately absent.</p>
 */
public interface RebaseOperations
{

	/** Starts rebasing the current branch onto {@code upstream}. */
	void start(RepositoryHandle repo, String upstream);

	/** Continues a paused rebase after conflicts were resolved. */
	void continueRebase(RepositoryHandle repo);

	/** Aborts the in-progress rebase and restores the pre-rebase state. */
	void abort(RepositoryHandle repo);

	/** Skips the current commit and continues. */
	void skip(RepositoryHandle repo);
}