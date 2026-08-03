package dev.configflow.infrastructure.git;

import dev.configflow.domain.vcs.exception.MergeConflictException;
import dev.configflow.domain.vcs.exception.VcsException;
import dev.configflow.domain.vcs.exception.VcsPreconditionException;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.RevisionId;
import dev.configflow.domain.vcs.port.RevertOperations;

import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.RevertCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.UnmergedPathsException;
import org.eclipse.jgit.api.errors.WrongRepositoryStateException;

/**
 * Revert for Git: records the inverse of the given commits as new commits on the current branch.
 *
 * <p>Reverting rewrites nothing — undoing a pushed commit is itself a commit — so, unlike reset,
 * this is safe on shared history.</p>
 */
final class GitRevert implements RevertOperations
{
	private final GitRepositoryAccess access;

	GitRevert(GitRepositoryAccess access)
	{
		this.access = access;
	}

	@Override
	public void revert(RepositoryHandle repo, List<RevisionId> revisions)
	{
		try(Git git = access.open(repo))
		{
			RevertCommand command = git.revert();
			for(RevisionId revision : revisions)
			{
				command.include(GitRevisions.commit(git.getRepository(), revision.value()));
			}
			command.call();
			check(command);
		}
		catch(WrongRepositoryStateException e)
		{
			throw new VcsPreconditionException("Finish the merge or revert already in progress in " + repo.localPath(), e);
		}
		catch(UnmergedPathsException e)
		{
			throw new VcsPreconditionException("Resolve the conflicted files in " + repo.localPath() + " first", e);
		}
		catch(GitAPIException e)
		{
			throw new VcsException("Failed to revert in " + repo.localPath(), e);
		}
	}

	/**
	 * Translates the outcome left on the command.
	 *
	 * <p>{@code FAILED} is not a malformed request: JGit reports it when the working tree would be
	 * overwritten, which the user clears by committing or stashing. Everything else that stops the revert is a content conflict, left in the working tree for
	 * the user to resolve and commit.</p>
	 */
	private static void check(RevertCommand command)
	{
		MergeResult failure = command.getFailingResult();
		if(failure == null)
		{
			return;
		}
		if(failure.getMergeStatus() == MergeResult.MergeStatus.FAILED)
		{
			throw new VcsPreconditionException("Commit or stash your changes first; revert would overwrite " + failure.getFailingPaths().keySet());
		}
		List<String> unmerged = command.getUnmergedPaths();
		throw new MergeConflictException(unmerged == null ? List.of() : unmerged.stream().map(Path::of).toList());
	}
}
