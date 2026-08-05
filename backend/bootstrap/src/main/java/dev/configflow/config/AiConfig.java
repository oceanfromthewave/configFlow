package dev.configflow.config;

import dev.configflow.application.ai.CommitMessageService;
import dev.configflow.application.credential.CredentialService;
import dev.configflow.application.repository.RepositoryService;
import dev.configflow.domain.ai.AiProvider;
import dev.configflow.infrastructure.ai.ClaudeAiProvider;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 어시스턴트 조립. 도메인 포트({@link AiProvider})와 구현을 잇는 컴포지션 루트다.
 *
 * <p>API 키는 OS 자격 증명 저장소에서 호출할 때마다 읽는다. 설정 화면에서 키를 바꾸면
 * 재시작 없이 다음 호출부터 적용된다.</p>
 */
@Configuration
public class AiConfig
{
	/** AI 키를 자격 증명 저장소에 넣을 때 쓰는 대상. 설정 화면도 같은 값으로 저장한다. */
	public static final String CLAUDE_KEY_HOST = "api.anthropic.com";

	private static final String CLAUDE_KEY_PROTOCOL = "https";

	@Bean
	public AiProvider aiProvider(CredentialService credentials, @Value("${configflow.ai.claude.api-key:}") String fallbackKey,
			@Value("${configflow.ai.claude.model:claude-opus-5}") String model)
	{
		return new ClaudeAiProvider(() -> resolveKey(credentials, fallbackKey), model);
	}

	/** 키체인 우선, 없으면 설정 프로퍼티. 프로퍼티는 설정 화면을 띄우지 않는 개발/CI 환경용 탈출구다. */
	private static String resolveKey(CredentialService credentials, String fallbackKey)
	{
		return credentials.secretFor(CLAUDE_KEY_HOST, CLAUDE_KEY_PROTOCOL, "").map(secret -> {
			try
			{
				// 헤더에 실으려면 String이어야 한다. 힙에 남는 복사본은 어쩔 수 없고 배열만 지운다.
				return new String(secret);
			}
			finally
			{
				Arrays.fill(secret, '\0');
			}
		}).orElse(fallbackKey);
	}

	@Bean
	public CommitMessageService commitMessageService(RepositoryService repositoryService, AiProvider aiProvider)
	{
		return new CommitMessageService(repositoryService, aiProvider);
	}
}
