package dev.configflow.infrastructure.git;

import dev.configflow.domain.vcs.exception.VcsException;
import dev.configflow.domain.vcs.model.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;

/**
 * Structured diffs for Git.
 *
 * <p>JGit only renders a unified diff as text, so this class parses that text back into
 * {@link DiffHunk}s. Keeping the parsing here means the API and the UI never have to understand {@code @@} headers.</p>
 */
final class GitDiff
{

	private final GitRepositoryAccess access;

	GitDiff(GitRepositoryAccess access)
	{
		this.access = access;
	}

	/**
	 * Diffs one working-copy file.
	 *
	 * @param staged
	 *        {@code true} compares HEAD against the index (what a commit would record), {@code false} compares the index against the working tree (what is not
	 * 		staged yet)
	 */
	FileDiff diffWorking(RepositoryHandle repo, Path path, boolean staged)
	{
		try(Git git = access.open(repo))
		{
			Repository repository = git.getRepository();
			AbstractTreeIterator oldSide;
			AbstractTreeIterator newSide;
			if(staged)
			{
				oldSide = headTree(repository);
				newSide = new org.eclipse.jgit.dircache.DirCacheIterator(repository.readDirCache());
			}
			else
			{
				oldSide = new org.eclipse.jgit.dircache.DirCacheIterator(repository.readDirCache());
				newSide = new FileTreeIterator(repository);
			}
			return diff(repository, oldSide, newSide, path);
		}
		catch(IOException e)
		{
			throw new VcsException("Failed to diff " + path + " in " + repo.localPath(), e);
		}
	}

	/** Diffs one file between two revisions. */
	FileDiff diffRevisions(RepositoryHandle repo, RevisionId from, RevisionId to, Path path)
	{
		try(Git git = access.open(repo))
		{
			Repository repository = git.getRepository();
			return diff(repository, treeOf(repository, from), treeOf(repository, to), path);
		}
		catch(MissingObjectException | IncorrectObjectTypeException e)
		{
			throw new NoSuchElementException("Revision not found in " + repo.localPath());
		}
		catch(RevisionSyntaxException e)
		{
			// Unchecked and not an IOException; AmbiguousObjectException is an IOException
			// and would land in the generic catch. Both are the caller mistyping a
			// revision, so they belong in the 400 family rather than a 500.
			throw new IllegalArgumentException("Not a valid revision: " + e.getMessage(), e);
		}
		catch(AmbiguousObjectException e)
		{
			throw new IllegalArgumentException("Ambiguous revision: " + e.getMessage(), e);
		}
		catch(IOException e)
		{
			throw new VcsException("Failed to diff " + path + " in " + repo.localPath(), e);
		}
	}

	List<FileChange> changesIn(RepositoryHandle repo, RevisionId revision)
	{
		try(Git git = access.open(repo))
		{
			Repository repository = git.getRepository();
			ObjectId commitId = repository.resolve(revision.value());
			if(commitId == null)
			{
				throw new NoSuchElementException("Revision not found: " + revision.value());
			}
			AbstractTreeIterator newSide;
			AbstractTreeIterator oldSide;
			try(RevWalk walk = new RevWalk(repository))
			{
				RevCommit commit = walk.parseCommit(commitId);
				newSide = treeOf(repository, commitId);
				oldSide = commit.getParentCount() == 0 ? new EmptyTreeIterator() : treeOf(repository, commit.getParent(0).getId());
			}
			return changeList(repository, oldSide, newSide);
		}
		catch(MissingObjectException | IncorrectObjectTypeException e)
		{
			throw new NoSuchElementException("Revision not found in " + repo.localPath());
		}
		catch(RevisionSyntaxException e)
		{
			throw new IllegalArgumentException("Not a valid revision: " + e.getMessage(), e);
		}
		catch(AmbiguousObjectException e)
		{
			throw new IllegalArgumentException("Ambiguous revision: " + e.getMessage(), e);
		}
		catch(IOException e)
		{
			throw new VcsException("Failed to list changes in " + revision.value() + " for " + repo.localPath(), e);
		}
	}

	/** Full file content as of a revision; empty when the file did not exist then. */
	String contentAt(RepositoryHandle repo, RevisionId revision, Path path)
	{
		try(Git git = access.open(repo))
		{
			Repository repository = git.getRepository();
			ObjectId commitId = repository.resolve(revision.value());
			if(commitId == null)
			{
				throw new NoSuchElementException("Revision not found: " + revision.value());
			}
			try(RevWalk walk = new RevWalk(repository);
					org.eclipse.jgit.treewalk.TreeWalk treeWalk = org.eclipse.jgit.treewalk.TreeWalk.forPath(repository, GitPaths.toGitPath(path),
							walk.parseCommit(commitId).getTree()))
			{
				if(treeWalk == null)
				{
					return "";
				}
				return new String(repository.open(treeWalk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
			}
		}
		catch(MissingObjectException | IncorrectObjectTypeException e)
		{
			throw new NoSuchElementException("Revision not found in " + repo.localPath());
		}
		catch(RevisionSyntaxException e)
		{
			throw new IllegalArgumentException("Not a valid revision: " + e.getMessage(), e);
		}
		catch(AmbiguousObjectException e)
		{
			throw new IllegalArgumentException("Ambiguous revision: " + e.getMessage(), e);
		}
		catch(IOException e)
		{
			throw new VcsException("Failed to read " + path + " in " + repo.localPath(), e);
		}
	}

	// --- rendering -------------------------------------------------------

	private static FileDiff diff(Repository repository, AbstractTreeIterator oldSide, AbstractTreeIterator newSide, Path path) throws IOException
	{
		String gitPath = GitPaths.toGitPath(path);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try(DiffFormatter formatter = new DiffFormatter(out))
		{
			formatter.setRepository(repository);
			formatter.setPathFilter(PathFilter.create(gitPath));
			List<DiffEntry> entries = formatter.scan(oldSide, newSide);
			if(entries.isEmpty())
			{
				// Unchanged file: an empty diff is the honest answer, not an error.
				return new FileDiff(path, null, ChangeType.MODIFIED, false, List.of());
			}
			DiffEntry entry = entries.get(0);
			if(isBinary(formatter, entry))
			{
				return new FileDiff(path, oldPathOf(entry), typeOf(entry), true, List.of());
			}
			formatter.format(entry);
			formatter.flush();
			List<DiffHunk> hunks = parseHunks(out.toString(StandardCharsets.UTF_8));
			return new FileDiff(path, oldPathOf(entry), typeOf(entry), false, hunks);
		}
	}

	private static List<FileChange> changeList(Repository repository, AbstractTreeIterator oldSide, AbstractTreeIterator newSide) throws IOException
	{
		List<FileChange> changes = new ArrayList<>();
		try(DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE))
		{
			formatter.setRepository(repository);
			formatter.setDetectRenames(true);
			for(DiffEntry entry : formatter.scan(oldSide, newSide))
			{
				changes.add(toFileChange(entry));
			}
		}
		return changes;
	}

	private static FileChange toFileChange(DiffEntry entry)
	{
		ChangeType type = typeOf(entry);
		Path path = type == ChangeType.DELETED ? Path.of(entry.getOldPath()) : Path.of(entry.getNewPath());
		return new FileChange(path, type, oldPathOf(entry), false, null);
	}

	/**
	 * JGit does not fail on binary content: it reports it through the patch type, and would otherwise render an unusable {@code Binary files differ} body.
	 */
	private static boolean isBinary(DiffFormatter formatter, DiffEntry entry) throws IOException
	{
		return formatter.toFileHeader(entry).getPatchType() != FileHeader.PatchType.UNIFIED;
	}

	/**
	 * Splits a unified diff into hunks.
	 *
	 * <p>Each hunk starts with {@code @@ -oldStart,oldCount +newStart,newCount @@}; the
	 * counts default to 1 when omitted, which is how Git writes single-line hunks.</p>
	 */
	private static List<DiffHunk> parseHunks(String unified)
	{
		List<DiffHunk> hunks = new ArrayList<>();
		int[] header = null;
		List<String> lines = new ArrayList<>();
		for(String line : unified.split("\n", -1))
		{
			if(line.startsWith("@@"))
			{
				if(header != null)
				{
					hunks.add(new DiffHunk(header[0], header[1], header[2], header[3], lines));
				}
				header = parseHunkHeader(line);
				lines = new ArrayList<>();
			}
			else if(header != null && (line.startsWith(" ") || line.startsWith("+") || line.startsWith("-")))
			{
				lines.add(line);
			}
		}
		if(header != null)
		{
			hunks.add(new DiffHunk(header[0], header[1], header[2], header[3], lines));
		}
		return hunks;
	}

	private static int[] parseHunkHeader(String line)
	{
		String ranges = line.substring(2, line.indexOf("@@", 2)).trim();
		String[] sides = ranges.split(" ");
		int[] oldSide = parseRange(sides[0].substring(1));
		int[] newSide = parseRange(sides[1].substring(1));
		return new int[] { oldSide[0], oldSide[1], newSide[0], newSide[1] };
	}

	private static int[] parseRange(String range)
	{
		int comma = range.indexOf(',');
		if(comma < 0)
		{
			return new int[] { Integer.parseInt(range), 1 };
		}
		return new int[] { Integer.parseInt(range.substring(0, comma)), Integer.parseInt(range.substring(comma + 1)) };
	}

	// --- tree helpers ----------------------------------------------------

	private static AbstractTreeIterator headTree(Repository repository) throws IOException
	{
		ObjectId head = repository.resolve(Constants.HEAD);
		// Unborn branch: everything staged counts as added against an empty tree.
		return head == null ? new EmptyTreeIterator() : treeOf(repository, head);
	}

	private static AbstractTreeIterator treeOf(Repository repository, RevisionId revision) throws IOException
	{
		ObjectId id = repository.resolve(revision.value());
		if(id == null)
		{
			throw new NoSuchElementException("Revision not found: " + revision.value());
		}
		return treeOf(repository, id);
	}

	private static AbstractTreeIterator treeOf(Repository repository, ObjectId commitId) throws IOException
	{
		try(RevWalk walk = new RevWalk(repository); ObjectReader reader = repository.newObjectReader())
		{
			CanonicalTreeParser parser = new CanonicalTreeParser();
			parser.reset(reader, walk.parseCommit(commitId).getTree());
			return parser;
		}
	}

	private static ChangeType typeOf(DiffEntry entry)
	{
		return switch(entry.getChangeType())
		{
			case ADD -> ChangeType.ADDED;
			case DELETE -> ChangeType.DELETED;
			case RENAME -> ChangeType.RENAMED;
			case COPY -> ChangeType.COPIED;
			case MODIFY -> ChangeType.MODIFIED;
		};
	}

	private static Path oldPathOf(DiffEntry entry)
	{
		String oldPath = entry.getOldPath();
		return DiffEntry.DEV_NULL.equals(oldPath) || oldPath.equals(entry.getNewPath()) ? null : Path.of(oldPath);
	}
}
