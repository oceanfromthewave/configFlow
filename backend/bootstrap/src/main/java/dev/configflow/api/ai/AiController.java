package dev.configflow.api.ai;

import dev.configflow.application.ai.CommitMessageService;
import dev.configflow.domain.repository.RepositoryId;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 어시스턴트 엔드포인트.
 *
 * <p>동기 응답이다. 외부 API 왕복이 있지만 작업 큐를 태울 만큼 길지 않고, 결과가
 * 즉시 커밋 입력창에 들어가야 해서 202/operation id를 쓰지 않는다.</p>
 */
@RestController
@RequestMapping("/api/v1/repositories/{id}/ai")
public class AiController
{
	private final CommitMessageService commitMessageService;

	public AiController(CommitMessageService commitMessageService)
	{
		this.commitMessageService = commitMessageService;
	}

	/** 생성된 커밋 메시지. */
	public record CommitMessageResponse(String message)
	{
	}

	@PostMapping("/commit-message")
	public CommitMessageResponse commitMessage(@PathVariable String id)
	{
		return new CommitMessageResponse(commitMessageService.generate(RepositoryId.of(id)));
	}
}
