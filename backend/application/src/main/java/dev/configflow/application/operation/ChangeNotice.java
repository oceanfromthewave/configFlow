package dev.configflow.application.operation;

import dev.configflow.domain.operation.OperationEvents;
import dev.configflow.domain.repository.RepositoryId;

/**
 * 작업이 무엇을 오래된 상태로 만들었는지 알린다.
 *
 * <p>큐에 올라간 작업은 실패하면서도 리포지토리를 바꿔놓는다 — 충돌로 멈춘 머지·리베이스·pull은
 * 워킹 트리를 충돌 상태로 남기고, 화면이 보여줘야 하는 것이 바로 그 상태다. 그래서 호출부는 이것을
 * {@code finally}에서 부른다.</p>
 *
 * <p>알림 실패는 삼킨다. {@code finally}에서 예외가 빠져나가면 작업 자신의 예외를 대체해버려서
 * 사용자가 실패 원인 대신 "알림 실패"를 보게 되기 때문이다. 갱신 통지는 최선 노력이지만 작업 결과는
 * 그렇지 않다.</p>
 */
public final class ChangeNotice
{
	private ChangeNotice()
	{
	}

	/** ref와 워킹 트리가 모두 오래된 상태가 됐다고 알린다. */
	public static void refsAndWorkingTree(OperationEvents events, RepositoryId id)
	{
		try
		{
			events.refsChanged(id);
			events.workingTreeChanged(id);
		}
		catch(RuntimeException ignored)
		{
			// 최선 노력. 이유는 클래스 주석 참고.
		}
	}

	/** 워킹 트리만 오래된 상태가 됐다고 알린다. */
	public static void workingTree(OperationEvents events, RepositoryId id)
	{
		try
		{
			events.workingTreeChanged(id);
		}
		catch(RuntimeException ignored)
		{
			// 최선 노력. 이유는 클래스 주석 참고.
		}
	}
}
