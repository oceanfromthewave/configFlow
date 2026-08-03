package dev.configflow.infrastructure.git;

import dev.configflow.domain.vcs.exception.VcsException;
import dev.configflow.domain.vcs.exception.VcsPreconditionException;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.ResetMode;
import dev.configflow.domain.vcs.model.RevisionId;
import dev.configflow.domain.vcs.port.ResetOperations;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.GitAPIException;

final class GitReset implements ResetOperations
{
	private final GitRepositoryAccess access;

	GitReset(GitRepositoryAccess access)
	{
		this.access = access;
	}

	@Override
	public void reset(RepositoryHandle repo, RevisionId target, ResetMode mode)
	{
		try(Git git = access.open(repo))
		{
			String commit = GitRevisions.commit(git.getRepository(), target.value()).name();
			git.reset().setMode(translate(mode)).setRef(commit).call();
		}
		catch(CheckoutConflictException e)
		{
			throw new VcsPreconditionException("Local changes would be overwritten by reset in " + repo.localPath(), e);
		}
		catch(GitAPIException e)
		{
			throw new VcsException("Failed to reset " + repo.localPath() + " to " + target.value(), e);
		}
	}

	private static ResetCommand.ResetType translate(ResetMode mode)
	{
		return switch(mode)
		{
			case SOFT -> ResetCommand.ResetType.SOFT;
			case MIXED -> ResetCommand.ResetType.MIXED;
			case HARD -> ResetCommand.ResetType.HARD;
		};
	}
}
