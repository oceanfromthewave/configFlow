package dev.configflow.application.ai;

import dev.configflow.application.repository.RepositoryService;
import dev.configflow.domain.ai.AiFeature;
import dev.configflow.domain.ai.AiProvider;
import dev.configflow.domain.ai.DiffContext;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.vcs.model.ChangeType;
import dev.configflow.domain.vcs.model.DiffHunk;
import dev.configflow.domain.vcs.model.FileChange;
import dev.configflow.domain.vcs.model.FileDiff;
import dev.configflow.domain.vcs.exception.VcsPreconditionException;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Use case: staged 변경으로 커밋 메시지를 생성한다.
 *
 * <p>{@link DiffContext} 규약대로 시크릿 마스킹은 여기(호출자)에서 끝낸다. AI 제공자에게는
 * 이미 마스킹된 diff만 넘어간다.</p>
 */
public final class CommitMessageService
{
	/** 프롬프트에 실어 보낼 diff 길이 상한. 넘으면 잘라서 보낸다. */
	private static final int MAX_DIFF_CHARS = 100_000;

	private static final Pattern KEY_VALUE_SECRET = Pattern.compile("(?i)(api[_-]?key|secret|token|password|passwd|authorization|bearer)(\\s*[:=]\\s*)\\S+");
	private static final Pattern SK_STYLE_KEY = Pattern.compile("sk-[A-Za-z0-9_\\-]{16,}");
	private static final Pattern AWS_ACCESS_KEY_ID = Pattern.compile("AKIA[0-9A-Z]{16}");

	private final RepositoryService repositories;
	private final AiProvider provider;

	public CommitMessageService(RepositoryService repositories, AiProvider provider)
	{
		this.repositories = Objects.requireNonNull(repositories, "repositories");
		this.provider = Objects.requireNonNull(provider, "provider");
	}

	/**
	 * staged 변경을 모아 커밋 메시지를 만든다.
	 *
	 * @throws UnsupportedOperationException
	 * 		활성 제공자가 커밋 메시지 생성을 지원하지 않을 때
	 * @throws VcsPreconditionException
	 * 		staged 변경이 없을 때
	 */
	public String generate(RepositoryId id)
	{
		if(!provider.supportedFeatures().contains(AiFeature.COMMIT_MESSAGE))
		{
			throw new UnsupportedOperationException("AI 제공자 '" + provider.id() + "'는 커밋 메시지 생성을 지원하지 않습니다");
		}

		List<FileChange> staged = repositories.status(id).staged();
		if(staged.isEmpty())
		{
			throw new VcsPreconditionException("staged 변경이 없습니다");
		}

		// 브랜치 컨텍스트는 이번 슬라이스 범위 밖이라 null로 둔다.
		return provider.generateCommitMessage(new DiffContext(mask(collectDiff(id, staged)), null)).value();
	}

	/** staged 파일을 하나씩 diff 떠서 유니파이드 텍스트로 잇는다. */
	private String collectDiff(RepositoryId id, List<FileChange> staged)
	{
		StringBuilder out = new StringBuilder();
		for(FileChange change : staged)
		{
			if(out.length() >= MAX_DIFF_CHARS)
			{
				break;
			}
			render(repositories.diffWorking(id, change.path(), true), out);
		}

		if(out.length() >= MAX_DIFF_CHARS)
		{
			out.setLength(MAX_DIFF_CHARS);
			out.append("\n[diff truncated]\n");
		}
		return out.toString();
	}

	/** 구조화된 {@link FileDiff}를 모델이 익숙한 유니파이드 diff 형식으로 되돌린다. */
	private static void render(FileDiff diff, StringBuilder out)
	{
		String newPath = normalize(diff.path());
		String oldPath = normalize(diff.oldPath() != null ? diff.oldPath() : diff.path());

		out.append("diff --git a/").append(oldPath).append(" b/").append(newPath).append('\n');
		out.append("--- ").append(diff.type() == ChangeType.ADDED ? "/dev/null" : "a/" + oldPath).append('\n');
		out.append("+++ ").append(diff.type() == ChangeType.DELETED ? "/dev/null" : "b/" + newPath).append('\n');

		if(diff.binary())
		{
			out.append("Binary files differ\n");
			return;
		}

		for(DiffHunk hunk : diff.hunks())
		{
			// 파일 하나가 상한을 넘겨도 전체를 메모리에 쌓지 않는다. 잘라내기는
			// 호출자가 하지만, 그 전에 이미 쌓인 양이 곧 힙 사용량이다.
			if(out.length() >= MAX_DIFF_CHARS)
			{
				return;
			}
			out.append("@@ -").append(hunk.oldStart()).append(',').append(hunk.oldCount()).append(" +").append(hunk.newStart()).append(',')
					.append(hunk.newCount()).append(" @@\n");
			for(String line : hunk.lines())
			{
				if(out.length() >= MAX_DIFF_CHARS)
				{
					return;
				}
				out.append(line).append('\n');
			}
		}

	}

	/** Windows 백슬래시를 diff 표기에 맞게 정규화한다. */
	private static String normalize(Path path)
	{
		return path.toString().replace('\\', '/');
	}

	/** 외부로 나가기 전 마지막 방어선: 키/토큰처럼 보이는 값을 가린다. */
	private static String mask(String diff)
	{
		String masked = KEY_VALUE_SECRET.matcher(diff).replaceAll("$1$2***");
		masked = SK_STYLE_KEY.matcher(masked).replaceAll("***");
		return AWS_ACCESS_KEY_ID.matcher(masked).replaceAll("***");
	}
}