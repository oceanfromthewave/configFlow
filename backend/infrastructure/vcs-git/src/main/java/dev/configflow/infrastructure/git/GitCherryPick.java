package dev.configflow.infrastructure.git;

import dev.configflow.domain.vcs.exception.MergeConflictException;
import dev.configflow.domain.vcs.exception.VcsException;
import dev.configflow.domain.vcs.exception.VcsPreconditionException;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.RevisionId;
import dev.configflow.domain.vcs.port.CherryPickOperations;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

import org.eclipse.jgit.api.CherryPickCommand;
import org.eclipse.jgit.api.CherryPickResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.UnmergedPathsException;
import org.eclipse.jgit.api.errors.WrongRepositoryStateException;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * Cherry-pick for Git: replays commits onto the current branch.
 */
final class GitCherryPick implements CherryPickOperations
{
	private final GitRepositoryAccess access;

	GitCherryPick(GitRepositoryAccess access)
	{
		this.access = access;
	}

	@Override
	public void cherryPick(RepositoryHandle repo, List<RevisionId> revisions)
	{
		try(Git git = access.open(repo))
		{
			CherryPickCommand command = git.cherryPick();
			for(RevisionId revision : revisions)
			{
				command.include(resolve(git.getRepository(), revision.value()));
			}
			check(git, command.call());
		}
		catch(WrongRepositoryStateException e)
		{
			throw new VcsPreconditionException("Finish the merge or cherry-pick already in progress in " + repo.localPath(), e);
		}
		catch(UnmergedPathsException e)
		{
			throw new VcsPreconditionException("Resolve the conflicted files in " + repo.localPath() + " first", e);
		}
		catch(NoHeadException e)
		{
			throw new VcsPreconditionException("Cannot cherry-pick onto a repository with no commits", e);
		}
		catch(GitAPIException e)
		{
			throw new VcsException("Failed to cherry-pick in " + repo.localPath(), e);
		}
	}

	/**
	 * Translates the result status.
	 *
	 * <p>{@code FAILED} is not a malformed request: JGit reports it when the index or the
	 * working tree is dirty, which the user clears by committing or stashing.</p>
	 */
	private static void check(Git git, CherryPickResult result)
	{
		switch(result.getStatus())
		{
			// The working tree is left conflicted on purpose, exactly as a conflicted merge
			// is: JGit has no cherry-pick --continue, so the user resolves and commits.
			case CONFLICTING -> throw new MergeConflictException(conflictedPaths(git));
			case FAILED -> throw new VcsPreconditionException(
					"Commit or stash your changes first; cherry-pick would overwrite " + result.getFailingPaths().keySet());
			default ->
			{
			}
		}
	}

	/**
	 * A conflicting {@link CherryPickResult} is a shared constant carrying no paths, so the working tree is the only place left to read them from.
	 */
	private static List<Path> conflictedPaths(Git git)
	{
		try
		{
			return git.status().call().getConflicting().stream().map(Path::of).toList();
		}
		catch(GitAPIException e)
		{
			// The paths only enrich the error message; failing to read them must not
			// replace the conflict with a different exception.
			return List.of();
		}
	}

	/**
	 * Resolves a user-supplied revision to the commit it names.
	 *
	 * <p>Peeling happens here rather than inside {@link CherryPickCommand}: JGit would wrap
	 * anything that is not a commit in an unchecked {@code JGitInternalException} — a 500 — while doing it up front turns a bad revision into a 400 that names
	 * itself.</p>
	 */
	private static ObjectId resolve(Repository repository, String revision)
	{
		try
		{
			ObjectId id = repository.resolve(revision);
			if(id == null)
			{
				throw new NoSuchElementException("Revision not found: " + revision);
			}
			try(RevWalk walk = new RevWalk(repository))
			{
				// parseCommit also peels an annotated tag down to the commit it points at.
				return walk.parseCommit(id).getId();
			}
		}
		catch(RevisionSyntaxException | AmbiguousObjectException e)
		{
			throw new IllegalArgumentException("Not a valid revision: " + revision, e);
		}
		catch(IncorrectObjectTypeException | MissingObjectException e)
		{
			throw new IllegalArgumentException("Revision does not name a commit: " + revision, e);
		}
		catch(IOException e)
		{
			throw new VcsException("Failed to read revision " + revision, e);
		}
	}
}
