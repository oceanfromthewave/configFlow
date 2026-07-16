package dev.configflow.infrastructure.git;

import dev.configflow.domain.vcs.exception.VcsException;
import dev.configflow.domain.vcs.model.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class GitWorkingTree
{
	private final GitRepositoryAccess access;

	GitWorkingTree(GitRepositoryAccess access)
	{
		this.access = access;
	}

	WorkingTreeStatus status(RepositoryHandle repo)
	{
		try(Git git = access.open(repo))
		{
			Status status = git.status().call();
			Set<String> conflicting = status.getConflicting();

			// staged = index VS HEAD (what a commit would record)
			List<FileChange> staged = new ArrayList<>();
			collect(staged, status.getAdded(), ChangeType.ADDED, conflicting);
			collect(staged, status.getChanged(), ChangeType.MODIFIED, conflicting);
			collect(staged, status.getRemoved(), ChangeType.DELETED, conflicting);

			// unstaged = working tree vs index (not yet staged)
			List<FileChange> unstaged = new ArrayList<>();
			collect(unstaged, status.getModified(), ChangeType.MODIFIED, conflicting);
			collect(unstaged, status.getMissing(), ChangeType.DELETED, conflicting);
			collect(unstaged, status.getUntracked(), ChangeType.UNTRACKED, conflicting);

			List<ConflictedFile> conflicted = new ArrayList<>();
			for (String path : conflicting) {
				conflicted.add(ConflictedFile.unresolved(Path.of(path)));
			}

			return new WorkingTreeStatus(staged, unstaged, conflicted);
		} catch (GitAPIException e) {
			throw new VcsException("Failed to read status of " + repo.localPath(), e);
		}
	}

	private static void collect(List<FileChange> target, Set<String> paths,
			ChangeType type, Set<String> exclude) {
		for (String path : paths) {
			if (!exclude.contains(path)) {
				target.add(FileChange.of(Path.of(path), type));
			}
		}
	}
}