package dev.configflow.infrastructure.git;

import dev.configflow.domain.vcs.exception.VcsException;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import org.eclipse.jgit.api.Git;

import java.io.IOException;

final class GitRepositoryAccess
{
	Git open(RepositoryHandle handle)
	{
		try
		{
			return Git.open(handle.localPath().toFile());
		}
		catch(IOException e)
		{
			throw new VcsException("Failed to open Git Repository at" + handle.localPath(), e);
		}
	}
}
