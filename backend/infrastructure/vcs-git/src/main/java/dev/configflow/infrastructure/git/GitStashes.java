package dev.configflow.infrastructure.git;

import dev.configflow.domain.vcs.exception.StashDropAfterApplyException;
import dev.configflow.domain.vcs.exception.VcsException;
import dev.configflow.domain.vcs.exception.VcsPreconditionException;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.StashEntry;
import dev.configflow.domain.vcs.port.StashOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRefNameException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.api.errors.StashApplyFailureException;

final class GitStashes implements StashOperations
{
	private final GitRepositoryAccess access;

	GitStashes(GitRepositoryAccess access)
	{
		this.access = access;
	}

	@Override
	public List<StashEntry> list(RepositoryHandle repo)
	{
		try(Git git = access.open(repo))
		{
			Collection<RevCommit> stashes = git.stashList().call();
			List<StashEntry> entries = new ArrayList<>();
			int index = 0;
			for(RevCommit commit : stashes)
			{
				Instant time = Instant.ofEpochSecond(commit.getCommitTime());
				entries.add(new StashEntry(index++, commit.getShortMessage(), time));
			}
			return List.copyOf(entries);
		}
		catch(GitAPIException | JGitInternalException e)
		{
			throw new VcsException("Failed to list stashes in " + repo.localPath(), e);
		}
	}

	@Override
	public void save(RepositoryHandle repo, String message, boolean includeUntracked)
	{
		try(Git git = access.open(repo))
		{
			var cmd = git.stashCreate();
			if(message != null && !message.isBlank())
			{
				cmd.setIndexMessage(message);
				cmd.setWorkingDirectoryMessage(message);
			}
			cmd.setIncludeUntracked(includeUntracked);
			RevCommit commit = cmd.call();
			if(commit == null)
			{
				throw new VcsPreconditionException("No local changes to stash");
			}
		}
		catch(GitAPIException | JGitInternalException e)
		{
			throw new VcsException("Failed to save stash in " + repo.localPath(), e);
		}
	}

	@Override
	public void apply(RepositoryHandle repo, int index)
	{
		try(Git git = access.open(repo))
		{
			requireExists(git, repo, index);
			git.stashApply().setStashRef("stash@{" + index + "}").call();
		}
		catch(InvalidRefNameException | RevisionSyntaxException e)
		{
			throw new NoSuchElementException("Stash not found: stash@{" + index + "}");
		}
		catch(CheckoutConflictException e)
		{
			throw new VcsPreconditionException("Local changes would be overwritten by stash apply: " + String.join(", ", e.getConflictingPaths()), e);
		}
		catch(StashApplyFailureException e)
		{
			throw new VcsPreconditionException("Local changes conflict with stash apply in " + repo.localPath(), e);
		}
		catch(GitAPIException | JGitInternalException e)
		{
			throw new VcsException("Failed to apply stash@{" + index + "} in " + repo.localPath(), e);
		}
	}

	@Override
	public void pop(RepositoryHandle repo, int index)
	{
		try(Git git = access.open(repo))
		{
			requireExists(git, repo, index);
			git.stashApply().setStashRef("stash@{" + index + "}").call();
			try
			{
				git.stashDrop().setStashRef(index).call();
			}
			catch(GitAPIException | JGitInternalException e)
			{
				throw new StashDropAfterApplyException(
						"Stash applied but failed to drop stash@{" + index + "} in " + repo.localPath() + "; drop it manually to avoid re-applying", e);
			}
		}
		catch(InvalidRefNameException | RevisionSyntaxException e)
		{
			throw new NoSuchElementException("Stash not found: stash@{" + index + "}");
		}
		catch(CheckoutConflictException e)
		{
			throw new VcsPreconditionException("Local changes would be overwritten by stash pop: " + String.join(", ", e.getConflictingPaths()), e);
		}
		catch(StashApplyFailureException e)
		{
			throw new VcsPreconditionException("Local changes conflict with stash pop in " + repo.localPath(), e);
		}
		catch(GitAPIException | JGitInternalException e)
		{
			throw new VcsException("Failed to pop stash@{" + index + "} in " + repo.localPath(), e);
		}
	}

	@Override
	public void drop(RepositoryHandle repo, int index)
	{
		try(Git git = access.open(repo))
		{
			requireExists(git, repo, index);
			git.stashDrop().setStashRef(index).call();
		}
		catch(InvalidRefNameException | RevisionSyntaxException e)
		{
			throw new NoSuchElementException("Stash not found: stash@{" + index + "}");
		}
		catch(GitAPIException | JGitInternalException e)
		{
			throw new VcsException("Failed to drop stash@{" + index + "} in " + repo.localPath(), e);
		}
	}

	/** Bounds-checks {@code index} up front so an out-of-range value fails as "not found" rather than as a translated JGit engine error. */
	private static void requireExists(Git git, RepositoryHandle repo, int index) throws GitAPIException
	{
		if(index < 0)
		{
			throw new IllegalArgumentException("stash index must be non-negative: " + index);
		}
		int count = 0;
		for(RevCommit ignored : git.stashList().call())
		{
			count++;
		}
		if(index >= count)
		{
			throw new NoSuchElementException("Stash not found: stash@{" + index + "}");
		}
	}
}
