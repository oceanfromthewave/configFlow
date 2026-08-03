package dev.configflow.infrastructure.git;

import dev.configflow.domain.vcs.exception.VcsException;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.util.NoSuchElementException;

final class GitRevisions
{
	private GitRevisions()
	{

	}

	static ObjectId commit(Repository repository, String revision)
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
				return walk.parseCommit(id).getId();
			}
		}
		catch(RevisionSyntaxException | AmbiguousObjectException e)
		{
			throw new IllegalArgumentException("Not a valid revision: " + revision, e);
		}
		catch(IncorrectObjectTypeException e)
		{
			throw new IllegalArgumentException("Revision does not name a commit: " + revision, e);
		}
		catch(MissingObjectException e)
		{
			throw new NoSuchElementException("Revision not found: " + revision);
		}
		catch(IOException e)
		{
			throw new VcsException("Failed to read revision " + revision, e);
		}
	}
}
