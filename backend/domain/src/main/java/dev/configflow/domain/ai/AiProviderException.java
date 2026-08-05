package dev.configflow.domain.ai;

import java.util.Objects;

/**
 * AI 제공자 호출이 실패했다.
 *
 * <p>원인별로 사용자가 취할 행동이 다르다 — 키를 고쳐야 하는지, 잠시 뒤 다시
 * 시도하면 되는지, 우리 쪽 문제인지. 그래서 하나의 예외에 {@link Reason}을 실어 API 계층이 상태 코드와 실패 코드를 갈라 쓸 수 있게 한다.</p>
 *
 * <p>제공자 응답 본문은 절대 메시지에 싣지 않는다. 이 메시지는 Problem Details의
 * {@code detail}로 클라이언트까지 나가므로, 업스트림 본문이 들어가면 그대로 유출된다.</p>
 */
public class AiProviderException extends RuntimeException
{
	/** 무엇이 잘못됐는지. API 계층이 이걸로 상태 코드를 고른다. */
	public enum Reason
	{
		/** 연결 실패·타임아웃. 재시도하면 성공할 수 있다. */
		NETWORK,

		/** 제공자가 자격 증명을 거부했다(401/403). 설정된 API 키를 고쳐야 한다. */
		AUTH,

		/** 사용량 한도(429). 잠시 뒤 재시도. */
		RATE_LIMITED,

		/** 제공자가 5xx를 냈거나, 응답이 우리가 아는 모양이 아니다. */
		PROVIDER_ERROR
	}

	private final Reason reason;

	public AiProviderException(Reason reason, String message)
	{
		this(reason, message, null);
	}

	public AiProviderException(Reason reason, String message, Throwable cause)
	{
		super(message, cause);
		this.reason = Objects.requireNonNull(reason, "reason must not be null");
	}

	public Reason reason()
	{
		return reason;
	}
}
