package dev.configflow.config;

import dev.configflow.application.ai.CommitMessageService;
import dev.configflow.application.repository.RepositoryService;
import dev.configflow.domain.ai.AiProvider;
import dev.configflow.infrastructure.ai.ClaudeAiProvider;
import dev.configflow.infrastructure.ai.NoopAiProvider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 어시스턴트 조립. 도메인 포트({@link AiProvider})와 구현을 잇는 컴포지션 루트다.
 *
 * <p>API 키는 이번 슬라이스에서 설정 프로퍼티로만 받는다. 키 관리 UI와
 * CredentialStore 연동은 다음 슬라이스에서 붙인다.</p>
 */
@Configuration
public class AiConfig
{
	/**
	 * 키가 설정돼 있으면 Claude, 아니면 no-op. 미설정 상태에서도 앱이 뜨고 API가 CAPABILITY_NOT_SUPPORTED로 답할 수 있게 예외 대신 대체 구현을 고른다.
	 */
	@Bean
	public AiProvider aiProvider(@Value("${configflow.ai.claude.api-key:}") String apiKey, @Value("${configflow.ai.claude.model:claude-opus-5}") String model)
	{
		return apiKey.isBlank() ? new NoopAiProvider() : new ClaudeAiProvider(apiKey, model);
	}

	@Bean
	public CommitMessageService commitMessageService(RepositoryService repositoryService, AiProvider aiProvider)
	{
		return new CommitMessageService(repositoryService, aiProvider);
	}
}
