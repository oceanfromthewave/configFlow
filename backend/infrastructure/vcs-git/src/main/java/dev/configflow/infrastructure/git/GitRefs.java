package dev.configflow.infrastructure.git;

import dev.configflow.domain.vcs.exception.VcsException;
import dev.configflow.domain.vcs.model.Author;
import dev.configflow.domain.vcs.model.RefLabel;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.Revision;
import dev.configflow.domain.vcs.model.RevisionId;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * Reading refs for Git: the branch/tag list and "what is in A but not in B".
 *
 * <p>Held by {@link GitVcsProvider}, which exposes these through the
 * {@code RefBrowseOperations} port.</p>
 */
final class GitRefs
{

	/** Points at the remote's default branch; a pointer, not a branch of its own. */
	private static final String REMOTE_HEAD_SUFFIX = "/" + Constants.HEAD;

	private final GitRepositoryAccess access;

	GitRefs(GitRepositoryAccess access)
	{
		this.access = access;
	}

	List<RefLabel> listRefs(RepositoryHandle repo)
	{
		try(Git git = access.open(repo))
		{
			Repository repository = git.getRepository();
			List<RefLabel> refs = new ArrayList<>();

			// An unborn branch has no commits, so there is nothing to point at yet.
			if(repository.resolve(Constants.HEAD) != null)
			{
				refs.add(new RefLabel(RefLabel.Kind.HEAD, repository.getBranch()));
			}

			collect(repository, Constants.R_HEADS, RefLabel.Kind.BRANCH, refs);
			collect(repository, Constants.R_REMOTES, RefLabel.Kind.REMOTE_BRANCH, refs);
			collect(repository, Constants.R_TAGS, RefLabel.Kind.TAG, refs);
			return refs;
		}
		catch(IOException e)
		{
			throw new VcsException("Failed to list refs of " + repo.localPath(), e);
		}
	}

	List<Revision> compare(RepositoryHandle repo, String base, String target)
	{
		try(Git git = access.open(repo))
		{
			Repository repository = git.getRepository();
			ObjectId baseId = require(repository, base);
			ObjectId targetId = require(repository, target);

			try(RevWalk walk = new RevWalk(repository))
			{
				walk.markStart(walk.parseCommit(targetId));
				// Excluding base and everything it reaches leaves exactly the difference.
				walk.markUninteresting(walk.parseCommit(baseId));

				List<Revision> ahead = new ArrayList<>();
				for(RevCommit commit : walk)
				{
					ahead.add(toRevision(commit));
				}
				return ahead;
			}
		}
		catch(MissingObjectException | IncorrectObjectTypeException e)
		{
			throw new NoSuchElementException("Ref not found in " + repo.localPath());
		}
		catch(RevisionSyntaxException e)
		{
			throw new IllegalArgumentException("Not a valid ref: " + e.getMessage(), e);
		}
		catch(AmbiguousObjectException e)
		{
			throw new IllegalArgumentException("Ambiguous ref: " + e.getMessage(), e);
		}
		catch(IOException e)
		{
			throw new VcsException("Failed to compare refs in " + repo.localPath(), e);
		}
	}

	private static void collect(Repository repository, String prefix, RefLabel.Kind kind, List<RefLabel> into) throws IOException
	{
		for(Ref ref : repository.getRefDatabase().getRefsByPrefix(prefix))
		{
			String name = ref.getName().substring(prefix.length());
			if(kind == RefLabel.Kind.REMOTE_BRANCH && name.endsWith(REMOTE_HEAD_SUFFIX))
			{
				continue;
			}
			into.add(new RefLabel(kind, name));
		}
	}

	private static ObjectId require(Repository repository, String ref) throws IOException
	{
		ObjectId id = repository.resolve(ref);
		if(id == null)
		{
			throw new NoSuchElementException("Ref not found: " + ref);
		}
		return id;
	}

	private static Revision toRevision(RevCommit commit)
	{
		List<RevisionId> parents = new ArrayList<>(commit.getParentCount());
		for(RevCommit parent : commit.getParents())
		{
			parents.add(new RevisionId(parent.getName()));
		}
		PersonIdent author = commit.getAuthorIdent();
		return new Revision(new RevisionId(commit.getName()), parents, new Author(author.getName(), author.getEmailAddress()), author.getWhenAsInstant(),
				commit.getFullMessage(), List.of());
	}
}
