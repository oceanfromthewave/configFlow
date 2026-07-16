package dev.configflow.infrastructure.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.vcs.model.ChangeType;
import dev.configflow.domain.vcs.model.FileChange;
import dev.configflow.domain.vcs.model.IgnorePattern;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.model.WorkingTreeStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitWorkingTreeTest {

	private final GitWorkingTree workingTree = new GitWorkingTree(new GitRepositoryAccess());

	@TempDir
	Path repoDir;

	private RepositoryHandle handle;

	@BeforeEach
	void initRepo() throws GitAPIException {
		Git.init().setDirectory(repoDir.toFile()).call().close();
		handle = new RepositoryHandle(repoDir, VcsType.GIT);
	}

	@Test
	void cleanRepository_isClean() throws Exception {
		commitFile("README.md", "hello");

		WorkingTreeStatus status = workingTree.status(handle);

		assertTrue(status.isClean(), "freshly committed repo should be clean");
	}

	@Test
	void untrackedFile_isReportedAsUnstagedUntracked() throws Exception {
		writeFile("notes.txt", "draft");

		WorkingTreeStatus status = workingTree.status(handle);

		assertTrue(status.staged().isEmpty());
		assertEquals(1, status.unstaged().size());
		assertTrue(hasChange(status.unstaged(), "notes.txt", ChangeType.UNTRACKED));
	}

	@Test
	void stagedNewFile_isReportedAsStagedAdded() throws Exception {
		writeFile("app.txt", "v1");
		add("app.txt");

		WorkingTreeStatus status = workingTree.status(handle);

		assertTrue(hasChange(status.staged(), "app.txt", ChangeType.ADDED));
		assertTrue(status.unstaged().isEmpty());
	}

	@Test
	void modifiedTrackedFile_appearsUnstaged_thenStagedAfterAdd() throws Exception {
		commitFile("app.txt", "first version");
		writeFile("app.txt", "second version is longer");

		WorkingTreeStatus beforeAdd = workingTree.status(handle);
		assertTrue(hasChange(beforeAdd.unstaged(), "app.txt", ChangeType.MODIFIED));

		add("app.txt");
		WorkingTreeStatus afterAdd = workingTree.status(handle);
		assertTrue(hasChange(afterAdd.staged(), "app.txt", ChangeType.MODIFIED));
	}

	@Test
	void stage_movesUntrackedFileIntoStaged() throws Exception {
		writeFile("new.txt", "hello");

		workingTree.stage(handle, List.of(Path.of("new.txt")));

		WorkingTreeStatus status = workingTree.status(handle);
		assertTrue(hasChange(status.staged(), "new.txt", ChangeType.ADDED));
		assertTrue(status.unstaged().isEmpty());
	}

	@Test
	void unstage_movesStagedModificationBackToUnstaged() throws Exception {
		commitFile("app.txt", "first version");
		writeFile("app.txt", "second version is longer");
		add("app.txt"); // now staged as MODIFIED

		workingTree.unstage(handle, List.of(Path.of("app.txt")));

		WorkingTreeStatus status = workingTree.status(handle);
		assertTrue(status.staged().isEmpty());
		assertTrue(hasChange(status.unstaged(), "app.txt", ChangeType.MODIFIED));
	}

	@Test
	void discard_restoresCommittedContent() throws Exception {
		commitFile("app.txt", "first version");
		writeFile("app.txt", "unwanted change is longer");

		workingTree.discard(handle, List.of(Path.of("app.txt")));

		WorkingTreeStatus status = workingTree.status(handle);
		assertTrue(status.isClean());
		assertEquals("first version", Files.readString(repoDir.resolve("app.txt")));
	}

	@Test
	void ignore_addsRuleOnceAndHidesMatchingFile() throws Exception {
		writeFile("debug.log", "noise");
		assertTrue(hasChange(workingTree.status(handle).unstaged(), "debug.log", ChangeType.UNTRACKED));

		workingTree.ignore(handle, new IgnorePattern("*.log"));
		workingTree.ignore(handle, new IgnorePattern("*.log")); // dedup

		WorkingTreeStatus status = workingTree.status(handle);
		assertFalse(hasChange(status.unstaged(), "debug.log", ChangeType.UNTRACKED));

		List<String> lines = Files.readAllLines(repoDir.resolve(".gitignore"));
		long count = lines.stream().filter(l -> l.strip().equals("*.log")).count();
		assertEquals(1, count);
	}

	// --- fixture helpers -------------------------------------------------

	private void writeFile(String name, String content) throws IOException {
		Files.writeString(repoDir.resolve(name), content);
	}

	private void add(String name) throws Exception {
		try (Git git = Git.open(repoDir.toFile())) {
			git.add().addFilepattern(name).call();
		}
	}

	private void commitFile(String name, String content) throws Exception {
		writeFile(name, content);
		try (Git git = Git.open(repoDir.toFile())) {
			git.add().addFilepattern(name).call();
			git.commit()
					.setMessage("add " + name)
					.setAuthor("Test", "test@configflow.dev")
					.setCommitter("Test", "test@configflow.dev")
					.call();
		}
	}

	private static boolean hasChange(List<FileChange> changes, String path, ChangeType type) {
		return changes.stream().anyMatch(
				c -> c.path().equals(Path.of(path)) && c.type() == type);
	}
}
