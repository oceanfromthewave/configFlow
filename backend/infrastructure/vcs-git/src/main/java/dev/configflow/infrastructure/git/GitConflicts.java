package dev.configflow.infrastructure.git;

import dev.configflow.domain.vcs.exception.VcsException;
import dev.configflow.domain.vcs.model.ConflictedFile;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.ThreeWayContent;
import dev.configflow.domain.vcs.port.ConflictOperations;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Conflict inspection and resolution backed by the Git index.
 *
 * <p>A conflicted path keeps three index entries — stage 1 (common ancestor), stage 2
 * ("ours"/mine) and stage 3 ("theirs"). Resolving collapses them back into a single stage 0 entry, which is what makes the path leave
 * {@link #listConflicts}.</p>
 */
final class GitConflicts implements ConflictOperations
{
	private final GitRepositoryAccess access;

	GitConflicts(GitRepositoryAccess access)
	{
		this.access = access;
	}

	@Override
	public List<ConflictedFile> listConflicts(RepositoryHandle repo)
	{
		try(Git git = access.open(repo))
		{
			// Git never records which side the user picked: a resolved path leaves the
			// conflicting set entirely, so everything still listed here is UNRESOLVED.
			return git.status().call().getConflicting().stream().sorted().map(p -> ConflictedFile.unresolved(Path.of(p))).toList();
		}
		catch(GitAPIException e)
		{
			throw new VcsException("Failed to list conflicts in " + repo.localPath(), e);
		}
	}

	@Override
	public ThreeWayContent threeWayContent(RepositoryHandle repo, Path path)
	{
		String wanted = GitPaths.toGitPath(path);
		try(Git git = access.open(repo))
		{
			Repository repository = git.getRepository();
			DirCache cache = repository.readDirCache();
			String base = null;
			String mine = null;
			String theirs = null;
			boolean conflicted = false;
			for(int i = 0; i < cache.getEntryCount(); i++)
			{
				DirCacheEntry entry = cache.getEntry(i);
				int stage = entry.getStage();
				if(stage == DirCacheEntry.STAGE_0 || !entry.getPathString().equals(wanted))
				{
					continue;
				}
				conflicted = true;
				// Any side may be absent: an add/add conflict has no ancestor, and a
				// delete/modify conflict has no content on the side that deleted it.
				String content = blob(repository, entry);
				switch(stage)
				{
					case DirCacheEntry.STAGE_1 -> base = content;
					case DirCacheEntry.STAGE_2 -> mine = content;
					case DirCacheEntry.STAGE_3 -> theirs = content;
				}
			}
			if(!conflicted)
			{
				throw new NoSuchElementException(wanted + " is not conflicted in " + repo.localPath());
			}
			return new ThreeWayContent(base, mine, theirs);
		}
		catch(LargeObjectException e)
		{
			throw new VcsException("Conflicted file " + wanted + " is too large to load", e);
		}
		catch(IOException e)
		{
			throw new VcsException("Failed to read the index of " + repo.localPath(), e);
		}
	}

	@Override
	public void resolve(RepositoryHandle repo, Path path, ConflictedFile.Resolution resolution, String manualContent)
	{
		String wanted = GitPaths.toGitPath(path);
		if(resolution == null || resolution == ConflictedFile.Resolution.UNRESOLVED)
		{
			throw new IllegalArgumentException("A resolution of MINE, THEIRS or MANUAL is required for " + wanted);
		}
		if(resolution == ConflictedFile.Resolution.MANUAL && manualContent == null)
		{
			throw new IllegalArgumentException("MANUAL resolution requires the merged content of " + wanted);
		}
		try(Git git = access.open(repo))
		{
			// Without this guard MANUAL would happily overwrite a file that was never in
			// conflict, and MINE/THEIRS would fail deep inside JGit instead of as a 404.
			if(!git.status().call().getConflicting().contains(wanted))
			{
				throw new NoSuchElementException(wanted + " is not conflicted in " + repo.localPath());
			}
			if(resolution == ConflictedFile.Resolution.MANUAL)
			{
				Files.writeString(repo.localPath().resolve(path), manualContent, StandardCharsets.UTF_8);
			}
			else
			{
				CheckoutCommand.Stage stage = resolution == ConflictedFile.Resolution.MINE ? CheckoutCommand.Stage.OURS : CheckoutCommand.Stage.THEIRS;
				git.checkout().setStage(stage).addPath(wanted).call();
			}
			// Staging is what actually clears the conflict: it collapses stages 1/2/3
			// into one stage 0 entry, exactly like `git add` on the command line.
			git.add().addFilepattern(wanted).call();
		}
		catch(IOException e)
		{
			throw new VcsException("Failed to write the resolved content of " + wanted + " in " + repo.localPath(), e);
		}
		catch(GitAPIException e)
		{
			throw new VcsException("Failed to resolve " + wanted + " in " + repo.localPath(), e);
		}
	}

	/** Loads one index stage's blob as UTF-8 text. */
	private static String blob(Repository repository, DirCacheEntry entry) throws IOException
	{
		return new String(repository.open(entry.getObjectId(), Constants.OBJ_BLOB).getBytes(), StandardCharsets.UTF_8);
	}
}