package dev.configflow.infrastructure.git;

import dev.configflow.domain.vcs.exception.VcsException;
import dev.configflow.domain.vcs.model.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
			for(String path : conflicting)
			{
				conflicted.add(ConflictedFile.unresolved(Path.of(path)));
			}

			return new WorkingTreeStatus(staged, unstaged, conflicted);
		}
		catch(GitAPIException e)
		{
			throw new VcsException("Failed to read status of " + repo.localPath(), e);
		}
	}

	/** Stages the given working-tree paths into the index (git add). */
	void stage(RepositoryHandle repo, List<Path> paths) {
		if (paths.isEmpty()) {
			return;
		}
		try (Git git = access.open(repo)) {
			var add = git.add();
			for (Path path : paths) {
				add.addFilepattern(toGitPath(path));
			}
			// setUpdate(false) 이므로 신규 파일도 포함해 스테이징한다 (기본값).
			add.call();
		} catch (GitAPIException e) {
			throw new VcsException("Failed to stage paths in " + repo.localPath(), e);
		}
	}

	/** Removes the given paths from the index, keeping working-tree content (git reset). */
	void unstage(RepositoryHandle repo, List<Path> paths) {
		if (paths.isEmpty()) {
			return;
		}
		try (Git git = access.open(repo)) {
			var reset = git.reset();
			for (Path path : paths) {
				reset.addPath(toGitPath(path));
			}
			// mode 미지정 = MIXED: 인덱스만 HEAD로 되돌리고 작업트리 파일은 건드리지 않는다.
			reset.call();
		} catch (GitAPIException e) {
			throw new VcsException("Failed to unstage paths in " + repo.localPath(), e);
		}
	}

	/** Discards local modifications of tracked paths (destructive; UI confirms first). */
	void discard(RepositoryHandle repo, List<Path> paths) {
		if (paths.isEmpty()) {
			return;
		}
		try (Git git = access.open(repo)) {
			var checkout = git.checkout();
			for (Path path : paths) {
				checkout.addPath(toGitPath(path));
			}
			// 인덱스/HEAD의 내용으로 작업트리 파일을 덮어써 수정 이전으로 되돌린다.
			checkout.call();
		} catch (GitAPIException e) {
			throw new VcsException("Failed to discard paths in " + repo.localPath(), e);
		}
	}

	/** Appends a rule to the repository-root .gitignore, avoiding duplicates. */
	void ignore(RepositoryHandle repo, IgnorePattern pattern) {
		Path gitignore = repo.localPath().resolve(".gitignore");
		String rule = pattern.pattern();
		try {
			List<String> lines = Files.exists(gitignore)
					? Files.readAllLines(gitignore)
					: new ArrayList<>();
			if (lines.stream().map(String::strip).anyMatch(rule::equals)) {
				return; // 이미 있으면 중복 추가하지 않는다.
			}
			lines.add(rule);
			Files.write(gitignore, lines);
		} catch (IOException e) {
			throw new VcsException("Failed to update .gitignore in " + repo.localPath(), e);
		}
	}

	/** Converts an OS path (possibly with backslashes on Windows) to a Git path ('/'). */
	private static String toGitPath(Path path) {
		return path.toString().replace('\\', '/');
	}


	private static void collect(List<FileChange> target, Set<String> paths, ChangeType type, Set<String> exclude)
	{
		for(String path : paths)
		{
			if(!exclude.contains(path))
			{
				target.add(FileChange.of(Path.of(path), type));
			}
		}
	}
}