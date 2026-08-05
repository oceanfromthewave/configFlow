package dev.configflow.application.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.application.repository.RepositoryService;
import dev.configflow.application.vcs.DefaultVcsProviderRegistry;
import dev.configflow.domain.ai.AiFeature;
import dev.configflow.domain.ai.AiProvider;
import dev.configflow.domain.ai.AiResult;
import dev.configflow.domain.ai.ConflictContext;
import dev.configflow.domain.ai.DiffContext;
import dev.configflow.domain.ai.MergeProposal;
import dev.configflow.domain.ai.ReviewReport;
import dev.configflow.domain.operation.WorkingTreeWatch;
import dev.configflow.domain.repository.Repository;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.repository.RepositoryStore;
import dev.configflow.domain.vcs.capability.VcsCapability;
import dev.configflow.domain.vcs.exception.VcsPreconditionException;
import dev.configflow.domain.vcs.model.ChangeType;
import dev.configflow.domain.vcs.model.DiffHunk;
import dev.configflow.domain.vcs.model.FileChange;
import dev.configflow.domain.vcs.model.FileDiff;
import dev.configflow.domain.vcs.model.IgnorePattern;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.RevisionId;
import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.model.WorkingTreeStatus;
import dev.configflow.domain.vcs.port.DiffOperations;
import dev.configflow.domain.vcs.port.VcsProvider;
import dev.configflow.domain.vcs.port.WorkingTreeOperations;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 커밋 메시지 생성 use case 테스트.
 *
 * <p>제공자에게 실제로 무엇이 넘어가는지가 핵심이다. diff 조립 형식과 시크릿 마스킹은
 * 외부로 나가는 페이로드라 회귀가 곧 유출이다.</p>
 */
class CommitMessageServiceTest
{
	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

	private final InMemoryRepositoryStore store = new InMemoryRepositoryStore();
	private final FakeProvider provider = new FakeProvider();
	private final RecordingAiProvider ai = new RecordingAiProvider();
	private final RepositoryService repositories = new RepositoryService(
			store,
			new DefaultVcsProviderRegistry(List.of(provider)),
			Clock.fixed(NOW, ZoneOffset.UTC),
			WorkingTreeWatch.noop());
	private final CommitMessageService service = new CommitMessageService(repositories, ai);

	private final RepositoryId id = register();

	private RepositoryId register()
	{
		Repository repository = Repository.register("repo", Paths.get("C:/repo"), null, VcsType.GIT, NOW);
		store.save(repository);
		return repository.id();
	}

	@Test
	void generate_sendsStagedDiffAndReturnsTheMessage()
	{
		stage(modified(Paths.get("src/app/Main.java")));
		ai.answer = "feat: add main entry point";

		assertEquals("feat: add main entry point", service.generate(id));
		assertTrue(ai.received.unifiedDiff().contains("diff --git a/src/app/Main.java b/src/app/Main.java"));
		assertTrue(ai.received.unifiedDiff().contains("@@ -1,2 +1,3 @@"));
		assertTrue(ai.received.unifiedDiff().contains("+added line"));
	}

	@Test
	void generate_masksSecretsBeforeTheyLeave()
	{
		provider.hunkLines = List.of(
				"+api_key = sk-abcdefghijklmnopqrstuvwx",
				"+AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE",
				"+password: hunter2");
		stage(modified(Paths.get("config/app.yml")));

		service.generate(id);

		String sent = ai.received.unifiedDiff();

		assertFalse(sent.contains("sk-abcdefghijklmnopqrstuvwx"));
		assertFalse(sent.contains("AKIAIOSFODNN7EXAMPLE"));
		assertFalse(sent.contains("hunter2"));
		assertTrue(sent.contains("***"));
	}

	@Test
	void generate_marksDeletedFileNewSideAsDevNull()
	{
		provider.diffType = ChangeType.DELETED;
		stage(new FileChange(Paths.get("old.txt"), ChangeType.DELETED, null, false, null));

		service.generate(id);

		assertTrue(ai.received.unifiedDiff().contains("+++ /dev/null"));
		assertTrue(ai.received.unifiedDiff().contains("--- a/old.txt"));
	}

	@Test
	void generate_marksAddedFileOldSideAsDevNull()
	{
		provider.diffType = ChangeType.ADDED;
		stage(new FileChange(Paths.get("new.txt"), ChangeType.ADDED, null, false, null));

		service.generate(id);

		assertTrue(ai.received.unifiedDiff().contains("--- /dev/null"));
		assertTrue(ai.received.unifiedDiff().contains("+++ b/new.txt"));
	}

	@Test
	void generate_truncatesDiffsPastTheCharacterLimit()
	{
		// CommitMessageService.MAX_DIFF_CHARS is private; mirror it here rather
		// than reflecting into the field.
		int maxDiffChars = 100_000;
		provider.hunkLines = List.of("+" + "x".repeat(maxDiffChars + 5_000));
		stage(modified(Paths.get("huge.txt")));

		service.generate(id);

		String sent = ai.received.unifiedDiff();
		assertTrue(sent.endsWith("[diff truncated]\n"));
		assertEquals(maxDiffChars + "\n[diff truncated]\n".length(), sent.length());
	}

	@Test
	void generate_rejectsWhenNothingIsStaged()
	{
		provider.status = WorkingTreeStatus.clean();

		assertThrows(VcsPreconditionException.class, () -> service.generate(id));
	}

	@Test
	void generate_rejectsWhenTheProviderCannotDoIt()
	{
		stage(modified(Paths.get("a.txt")));
		ai.features = Set.of();

		assertThrows(UnsupportedOperationException.class, () -> service.generate(id));
	}

	private static FileChange modified(Path path)
	{
		return FileChange.of(path, ChangeType.MODIFIED);
	}

	private void stage(FileChange change)
	{
		provider.status = new WorkingTreeStatus(List.of(change), List.of(), List.of(), false);
	}

	/** 넘어온 {@link DiffContext}를 붙잡아 두는 AI 제공자. */
	private static final class RecordingAiProvider implements AiProvider
	{
		Set<AiFeature> features = Set.of(AiFeature.COMMIT_MESSAGE);
		String answer = "chore: update";
		DiffContext received;

		@Override
		public String id()
		{
			return "recording";
		}

		@Override
		public Set<AiFeature> supportedFeatures()
		{
			return features;
		}

		@Override
		public AiResult<String> generateCommitMessage(DiffContext context)
		{
			received = context;
			return AiResult.of(answer);
		}

		@Override
		public AiResult<String> summarizeChanges(DiffContext context)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public AiResult<MergeProposal> resolveConflict(ConflictContext context)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public AiResult<ReviewReport> reviewCode(DiffContext context)
		{
			throw new UnsupportedOperationException();
		}
	}

	private static final class InMemoryRepositoryStore implements RepositoryStore
	{
		private final Map<RepositoryId, Repository> byId = new LinkedHashMap<>();

		@Override
		public void save(Repository repository)
		{
			byId.put(repository.id(), repository);
		}

		@Override
		public Optional<Repository> findById(RepositoryId id)
		{
			return Optional.ofNullable(byId.get(id));
		}

		@Override
		public Optional<Repository> findByLocalPath(Path localPath)
		{
			return byId.values().stream().filter(r -> r.localPath().equals(localPath)).findFirst();
		}

		@Override
		public List<Repository> findAll()
		{
			return new ArrayList<>(byId.values());
		}

		@Override
		public void delete(RepositoryId id)
		{
			byId.remove(id);
		}
	}

	private static final class FakeProvider implements VcsProvider, WorkingTreeOperations, DiffOperations
	{
		WorkingTreeStatus status = WorkingTreeStatus.clean();
		ChangeType diffType = ChangeType.MODIFIED;
		List<String> hunkLines = List.of(" context", "+added line");

		@Override
		public VcsType type()
		{
			return VcsType.GIT;
		}

		@Override
		public Set<VcsCapability> capabilities()
		{
			return Set.of(VcsCapability.STAGING);
		}

		@Override
		public boolean detect(Path localPath)
		{
			return true;
		}

		@Override
		public RepositoryHandle open(Path localPath)
		{
			return new RepositoryHandle(localPath, VcsType.GIT);
		}

		@Override
		public WorkingTreeStatus status(RepositoryHandle repo)
		{
			return status;
		}

		@Override
		public void stage(RepositoryHandle repo, List<Path> paths)
		{
		}

		@Override
		public void unstage(RepositoryHandle repo, List<Path> paths)
		{
		}

		@Override
		public void discard(RepositoryHandle repo, List<Path> paths)
		{
		}

		@Override
		public void ignore(RepositoryHandle repo, IgnorePattern pattern)
		{
		}

		@Override
		public FileDiff diffWorking(RepositoryHandle repo, Path path, boolean staged)
		{
			return new FileDiff(path, null, diffType, false, List.of(new DiffHunk(1, 2, 1, 3, hunkLines)));
		}

		@Override
		public FileDiff diffRevisions(RepositoryHandle repo, RevisionId from, RevisionId to, Path path)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public List<FileChange> changesIn(RepositoryHandle repo, RevisionId revision)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public FileDiff diffInCommit(RepositoryHandle repo, RevisionId revision, Path path)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public String contentAt(RepositoryHandle repo, RevisionId revision, Path path)
		{
			throw new UnsupportedOperationException();
		}
	}
}
