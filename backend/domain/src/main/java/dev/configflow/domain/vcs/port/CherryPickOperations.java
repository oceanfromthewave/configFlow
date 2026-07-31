package dev.configflow.domain.vcs.port;

import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.RevisionId;

import java.util.List;

/**
 * Optional port for replaying individual commits onto the current branch (requires the {@code CHERRY_PICK} capability).
 *
 * <p>There is no continue/abort pair here on purpose: unlike rebase, JGit exposes no
 * porcelain for resuming a cherry-pick, so a conflict is left for the user to resolve and commit — exactly as a conflicted merge already is.</p>
 */
public interface CherryPickOperations
{

	/** Replays {@code revisions}, in the given order, onto the current branch. */
	void cherryPick(RepositoryHandle repo, List<RevisionId> revisions);
}
