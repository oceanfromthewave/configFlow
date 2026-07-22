package dev.configflow.infrastructure.git;

import dev.configflow.domain.vcs.exception.VcsException;
import dev.configflow.domain.vcs.model.Author;
import dev.configflow.domain.vcs.model.CommitRequest;
import dev.configflow.domain.vcs.model.HistoryQuery;
import dev.configflow.domain.vcs.model.Page;
import dev.configflow.domain.vcs.model.RefLabel;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.Revision;
import dev.configflow.domain.vcs.model.RevisionId;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.AndRevFilter;
import org.eclipse.jgit.revwalk.filter.AuthorRevFilter;
import org.eclipse.jgit.revwalk.filter.CommitTimeRevFilter;
import org.eclipse.jgit.revwalk.filter.MessageRevFilter;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

/**
 * Commit creation and history reading for Git, delegating the low-level work to JGit. Held by {@link GitVcsProvider}, which exposes these operations through
 * the {@code CommitOperations} port.
 */
final class GitCommits
{

	private final GitRepositoryAccess access;

	GitCommits(GitRepositoryAccess access)
	{
		this.access = access;
	}

	/**
	 * Commits whatever is currently staged in the index.
	 *
	 * <p>Git selects content through its staging area (the {@code STAGING} capability),
	 * so {@link CommitRequest#paths()} is deliberately not used here: it exists for providers without an index, such as SVN, which commit an explicit path
	 * list.</p>
	 *
	 * @return the id of the created commit; note that amending produces a <em>new</em> id
	 */
	RevisionId commit(RepositoryHandle repo, CommitRequest request)
	{
		try(Git git = access.open(repo))
		{
			RevCommit created = git.commit().setMessage(request.message()).setAmend(request.amend()).call();
			return new RevisionId(created.getName());
		}
		catch(GitAPIException e)
		{
			throw new VcsException("Failed to commit in " + repo.localPath(), e);
		}
	}

	/**
	 * Walks history newest-first, one page at a time.
	 *
	 * <p>The cursor is the id of the first revision that did not fit on the previous
	 * page, so the next call simply starts the walk there. History is unbounded, so the walk is never materialised in full.</p>
	 */
	Page<Revision> history(RepositoryHandle repo, HistoryQuery query)
	{
		try(Git git = access.open(repo))
		{
			Repository repository = git.getRepository();
			ObjectId start = resolveStart(repository, query);
			if(start == null)
			{
				// Unborn branch (no commits yet) or an unknown ref: nothing to walk.
				return new Page<>(List.of(), null);
			}

			Map<AnyObjectId, Set<Ref>> refsById = repository.getAllRefsByPeeledObjectId();
			ObjectId headId = repository.resolve(Constants.HEAD);

			try(RevWalk walk = new RevWalk(repository))
			{
				walk.markStart(walk.parseCommit(start));
				applyFilters(walk, query);

				List<Revision> items = new ArrayList<>(query.limit());
				String nextCursor = null;
				for(RevCommit commit : walk)
				{
					if(items.size() == query.limit())
					{
						nextCursor = commit.getName();
						break;
					}
					items.add(toRevision(commit, refsById, headId));
				}
				return new Page<>(items, nextCursor);
			}
		}
		catch(IOException e)
		{
			throw new VcsException("Failed to read history of " + repo.localPath(), e);
		}
	}

	Revision show(RepositoryHandle repo, RevisionId id)
	{
		try(Git git = access.open(repo))
		{
			Repository repository = git.getRepository();
			ObjectId objectId = repository.resolve(id.value());
			if(objectId == null)
			{
				throw new NoSuchElementException("Revision not found: " + id.value() + " in " + repo.localPath());
			}
			try(RevWalk walk = new RevWalk(repository))
			{
				return toRevision(walk.parseCommit(objectId), repository.getAllRefsByPeeledObjectId(), repository.resolve(Constants.HEAD));
			}
		}
		catch(MissingObjectException | IncorrectObjectTypeException e)
		{
			// A well-formed but absent SHA parses fine, so resolve() returns non-null and
			// the miss only surfaces here — same "not found" answer as an unknown name.
			throw new NoSuchElementException("Revision not found: " + id.value() + " in " + repo.localPath());
		}
		catch(IOException e)
		{
			throw new VcsException("Failed to read revision " + id.value(), e);
		}
	}

	/** Cursor wins over branch, branch over HEAD. */
	private static ObjectId resolveStart(Repository repository, HistoryQuery query) throws IOException
	{
		if(query.cursor() != null)
		{
			return repository.resolve(query.cursor());
		}
		if(query.branch() != null)
		{
			return repository.resolve(query.branch());
		}
		return repository.resolve(Constants.HEAD);
	}

	private static void applyFilters(RevWalk walk, HistoryQuery query)
	{
		List<RevFilter> filters = new ArrayList<>();
		if(hasText(query.author()))
		{
			filters.add(AuthorRevFilter.create(Pattern.quote(query.author())));
		}
		if(hasText(query.messageContains()))
		{
			filters.add(MessageRevFilter.create(Pattern.quote(query.messageContains())));
		}
		if(query.from() != null && query.to() != null)
		{
			filters.add(CommitTimeRevFilter.between(query.from(), query.to()));
		}
		else if(query.from() != null)
		{
			filters.add(CommitTimeRevFilter.after(query.from()));
		}
		else if(query.to() != null)
		{
			filters.add(CommitTimeRevFilter.before(query.to()));
		}
		if(!filters.isEmpty())
		{
			walk.setRevFilter(filters.size() == 1 ? filters.get(0) : AndRevFilter.create(filters));
		}
		if(query.path() != null)
		{
			// ANY_DIFF keeps only revisions that actually changed the path.
			walk.setTreeFilter(AndTreeFilter.create(PathFilter.create(GitPaths.toGitPath(query.path())), TreeFilter.ANY_DIFF));
		}
	}

	private static Revision toRevision(RevCommit commit, Map<AnyObjectId, Set<Ref>> refsById, ObjectId headId)
	{
		List<RevisionId> parents = new ArrayList<>(commit.getParentCount());
		for(RevCommit parent : commit.getParents())
		{
			parents.add(new RevisionId(parent.getName()));
		}
		PersonIdent author = commit.getAuthorIdent();
		return new Revision(new RevisionId(commit.getName()), parents, new Author(author.getName(), author.getEmailAddress()), author.getWhenAsInstant(),
				commit.getFullMessage(), labelsFor(commit, refsById, headId));
	}

	/** Branch/tag/HEAD decorations pointing at this revision, for the commit graph. */
	private static List<RefLabel> labelsFor(RevCommit commit, Map<AnyObjectId, Set<Ref>> refsById, ObjectId headId)
	{
		List<RefLabel> labels = new ArrayList<>();
		if(headId != null && headId.equals(commit.getId()))
		{
			labels.add(new RefLabel(RefLabel.Kind.HEAD, Constants.HEAD));
		}
		Set<Ref> refs = refsById.get(commit.getId());
		if(refs == null)
		{
			return labels;
		}
		for(Ref ref : refs)
		{
			String name = ref.getName();
			if(name.startsWith(Constants.R_HEADS))
			{
				labels.add(new RefLabel(RefLabel.Kind.BRANCH, name.substring(Constants.R_HEADS.length())));
			}
			else if(name.startsWith(Constants.R_REMOTES))
			{
				labels.add(new RefLabel(RefLabel.Kind.REMOTE_BRANCH, name.substring(Constants.R_REMOTES.length())));
			}
			else if(name.startsWith(Constants.R_TAGS))
			{
				labels.add(new RefLabel(RefLabel.Kind.TAG, name.substring(Constants.R_TAGS.length())));
			}
		}
		return labels;
	}

	private static boolean hasText(String value)
	{
		return value != null && !value.isBlank();
	}
}
