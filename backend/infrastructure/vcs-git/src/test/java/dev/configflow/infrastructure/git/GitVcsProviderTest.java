package dev.configflow.infrastructure.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.vcs.capability.VcsCapability;
import dev.configflow.domain.vcs.model.ChangeType;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.model.WorkingTreeStatus;
import dev.configflow.domain.vcs.port.VcsProvider;
import dev.configflow.domain.vcs.port.WorkingTreeOperations;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitVcsProviderTest {

	private final GitVcsProvider provider = new GitVcsProvider();

	@TempDir
	Path repoDir;

	@BeforeEach
	void initRepo() throws GitAPIException {
		Git.init().setDirectory(repoDir.toFile()).call().close();
	}

	@Test
	void declaresGitTypeAndStagingCapability() {
		assertEquals(VcsType.GIT, provider.type());
		assertTrue(provider.capabilities().contains(VcsCapability.STAGING));
	}

	@Test
	void detect_acceptsGitWorkingCopy_andRejectsPlainDirectory(@TempDir Path plainDir) {
		assertTrue(provider.detect(repoDir));
		assertFalse(provider.detect(plainDir));
	}

	@Test
	void open_rejectsDirectoryThatIsNotAGitRepository(@TempDir Path plainDir) {
		assertThrows(IllegalArgumentException.class, () -> provider.open(plainDir));
	}

	@Test
	void exposesWorkingTreeOperations_andStagesThroughThePort() throws IOException {
		// The application layer only ever sees the VcsProvider port.
		VcsProvider asPort = provider;
		RepositoryHandle repo = asPort.open(repoDir);
		Files.writeString(repoDir.resolve("app.txt"), "hello");

		// Capability check first, then narrow to the role interface.
		assertTrue(asPort.capabilities().contains(VcsCapability.STAGING));
		WorkingTreeOperations workingTree =
				assertInstanceOf(WorkingTreeOperations.class, asPort);

		workingTree.stage(repo, List.of(Path.of("app.txt")));

		WorkingTreeStatus status = workingTree.status(repo);
		assertTrue(status.staged().stream().anyMatch(
				c -> c.path().equals(Path.of("app.txt")) && c.type() == ChangeType.ADDED));
	}
}