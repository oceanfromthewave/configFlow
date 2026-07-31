package dev.configflow.infrastructure.git;

import dev.configflow.domain.vcs.exception.MergeConflictException;
import dev.configflow.domain.vcs.exception.VcsException;
import dev.configflow.domain.vcs.exception.VcsPreconditionException;
import dev.configflow.domain.vcs.model.RepositoryHandle;

import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.WrongRepositoryStateException;

/**
 * Rebase for Git: start / continue / abort / skip.
 */
final class GitRebase
{
	private final GitRepositoryAccess access;

	GitRebase(GitRepositoryAccess access)
	{
		this.access = access;
	}

	void start(RepositoryHandle repo, String upstream)
	{
		try(Git git = access.open(repo))
		{
			check(git, git.rebase().setUpstream(upstream).call(), "rebase " + upstream);
		}
		catch(RefNotFoundException e)
		{
			throw new NoSuchElementException("Upstream not found: " + upstream);
		}
		catch(WrongRepositoryStateException e)
		{
			throw new VcsPreconditionException("A rebase is already in progress in " + repo.localPath(), e);
		}
		catch(GitAPIException e)
		{
			throw new VcsException("Failed to rebase onto " + upstream, e);
		}
	}

	void continueRebase(RepositoryHandle repo)
	{
		run(repo, RebaseCommand.Operation.CONTINUE, "rebase --continue");
	}

	void abort(RepositoryHandle repo)
	{
		run(repo, RebaseCommand.Operation.ABORT, "rebase --abort");
	}

	void skip(RepositoryHandle repo)
	{
		run(repo, RebaseCommand.Operation.SKIP, "rebase --skip");
	}

	private void run(RepositoryHandle repo, RebaseCommand.Operation operation, String what)
	{
		try(Git git = access.open(repo))
		{
			check(git, git.rebase().setOperation(operation).call(), what);
		}
		catch(WrongRepositoryStateException e)
		{
			throw new VcsPreconditionException("No rebase in progress in " + repo.localPath(), e);
		}
		catch(GitAPIException e)
		{
			throw new VcsException("Failed to run '" + what + "' in " + repo.localPath(), e);
		}
	}

	/**
	 * Translates the result status.
	 *
	 * <p>Only the failing statuses are enumerated on purpose: OK / UP_TO_DATE /
	 * FAST_FORWARD are successes, and ABORTED is the expected outcome of {@code --abort} even though its {@code isSuccessful()} returns false — a blanket
	 * success check would make every abort throw.</p>
	 */
	private static void check(Git git, RebaseResult result, String what)
	{
		switch(result.getStatus())
		{
			// The working tree is left conflicted on purpose: the user resolves it,
			// then calls continue (or skip / abort).
			case STOPPED, CONFLICTS -> throw new MergeConflictException(conflictedPaths(git, result));
			case UNCOMMITTED_CHANGES -> throw new VcsPreconditionException(
					"Commit or stash your changes before '" + what + "': " + String.join(", ", result.getUncommittedChanges()));
			case NOTHING_TO_COMMIT -> throw new VcsPreconditionException("Nothing left to commit; skip this commit or abort the rebase");
			case FAILED -> throw new VcsException("'" + what + "' failed: " + result.getFailingPaths());
			default ->
			{
			}
		}
	}

	/**
	 * JGit fills {@code getConflicts()} only for {@code CONFLICTS}; a {@code STOPPED} rebase carries none, so ask the working tree instead.
	 */
	private static List<Path> conflictedPaths(Git git, RebaseResult result)
	{
		List<String> conflicts = result.getConflicts();
		if(conflicts == null || conflicts.isEmpty())
		{
			try
			{
				conflicts = List.copyOf(git.status().call().getConflicting());
			}
			catch(GitAPIException e)
			{
				// The paths are a detail of the error message; failing to read them
				// must not replace the conflict with a different exception.
				return List.of();
			}
		}
		return conflicts.stream().map(Path::of).toList();
	}
}
