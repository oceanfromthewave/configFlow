package dev.configflow.infrastructure.git;

import dev.configflow.domain.vcs.exception.VcsException;
import dev.configflow.domain.vcs.exception.VcsPreconditionException;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.RevisionId;
import dev.configflow.domain.vcs.port.TagOperations;

import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TagCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidTagNameException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * Tag mutations for Git: create and delete.
 *
 * <p>Held by {@link GitVcsProvider}, which exposes these through the
 * {@code TagOperations} port. Listing tags belongs to {@link GitRefs}.</p>
 */
final class GitTags implements TagOperations
{
	private final GitRepositoryAccess access;

	GitTags(GitRepositoryAccess access)
	{
		this.access = access;
	}

	@Override
	public void create(RepositoryHandle repo, String name, RevisionId target, String message)
	{
		try(Git git = access.open(repo))
		{
			TagCommand tag = git.tag().setName(name);
			// A message is what makes a tag annotated. JGit defaults to annotated, so
			// without this a plain tag would silently gain a tag object of its own.
			boolean annotated = message != null && !message.isBlank();
			tag.setAnnotated(annotated);
			if(annotated)
			{
				tag.setMessage(message);
			}
			if(target != null)
			{
				tag.setObjectId(resolve(git.getRepository(), target.value()));
			}
			tag.call();
		}
		catch(RefAlreadyExistsException e)
		{
			throw new VcsPreconditionException("Tag already exists: " + name, e);
		}
		catch(InvalidTagNameException | RevisionSyntaxException e)
		{
			throw new IllegalArgumentException("Not a valid tag name: " + name, e);
		}
		catch(NoHeadException e)
		{
			// Nothing to point at yet. The user commits first and retries.
			throw new VcsPreconditionException("Cannot tag a repository with no commits", e);
		}
		catch(AmbiguousObjectException e)
		{
			// Subclass of IOException, so it has to be caught before the general case
			// below or a user's ambiguous short SHA would be reported as a 500.
			throw new IllegalArgumentException("Ambiguous revision: " + target.value(), e);
		}
		catch(IOException e)
		{
			throw new VcsException("Failed to read " + repo.localPath(), e);
		}
		catch(GitAPIException e)
		{
			throw new VcsException("Failed to create tag " + name, e);
		}
	}

	@Override
	public void delete(RepositoryHandle repo, String name)
	{
		try(Git git = access.open(repo))
		{
			List<String> deleted = git.tagDelete().setTags(name).call();
			if(deleted.isEmpty())
			{
				// JGit reports "deleted nothing" by returning an empty list rather than
				// failing, so without this a typo would be reported as a success.
				throw new NoSuchElementException("Tag not found: " + name);
			}
		}
		catch(GitAPIException e)
		{
			throw new VcsException("Failed to delete tag " + name, e);
		}
	}

	/**
	 * JGit's tag command takes a parsed {@link RevObject}, not a raw id: an annotated tag records the type of what it points at, so the object has to be looked
	 * up first.
	 */
	private static RevObject resolve(Repository repository, String revision) throws IOException
	{
		ObjectId id = repository.resolve(revision);
		if(id == null)
		{
			throw new NoSuchElementException("Revision not found: " + revision);
		}
		try(RevWalk walk = new RevWalk(repository))
		{
			// Parsed eagerly: the returned object keeps its id and type after the walk
			// closes, which is all the tag command needs.
			return walk.parseAny(id);
		}
	}
}
