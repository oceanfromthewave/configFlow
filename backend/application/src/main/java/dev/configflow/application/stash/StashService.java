package dev.configflow.application.stash;

import dev.configflow.application.operation.ChangeNotice;
import dev.configflow.application.operation.OperationQueue;
import dev.configflow.application.vcs.VcsAccess;
import dev.configflow.domain.operation.ConsoleLevel;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationEvents;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.vcs.exception.StashDropAfterApplyException;
import dev.configflow.domain.vcs.model.StashEntry;
import dev.configflow.domain.vcs.port.StashOperations;

import java.util.List;
import java.util.Objects;

public class StashService
{
	private final VcsAccess access;
	private final OperationQueue queue;
	private final OperationEvents events;

	public StashService(VcsAccess access, OperationQueue queue, OperationEvents events)
	{
		this.access = Objects.requireNonNull(access, "access");
		this.queue = Objects.requireNonNull(queue, "queue");
		this.events = Objects.requireNonNull(events, "events");
	}

	public List<StashEntry> list(RepositoryId id)
	{
		VcsAccess.Opened<StashOperations> opened = access.open(id, StashOperations.class);
		return opened.operations().list(opened.handle());
	}

	public Operation save(RepositoryId id, String message, boolean includeUntracked)
	{
		VcsAccess.Opened<StashOperations> opened = access.open(id, StashOperations.class);
		return queue.submit(id, OperationType.STASH, context -> {
			context.log("stash save " + (message != null ? message : ""), ConsoleLevel.CMD);
			context.throwIfCancelled();
			opened.operations().save(opened.handle(), message, includeUntracked);
			events.workingTreeChanged(id);
		});
	}

	public Operation apply(RepositoryId id, int index)
	{
		requireValidIndex(index);
		VcsAccess.Opened<StashOperations> opened = access.open(id, StashOperations.class);
		return queue.submit(id, OperationType.STASH, context -> {
			context.log("stash apply stash@{" + index + "}", ConsoleLevel.CMD);
			context.throwIfCancelled();
			try
			{
				opened.operations().apply(opened.handle(), index);
			}
			finally
			{
				// apply가 충돌로 실패해도 스태시 내용은 이미 충돌 상태로 워킹 트리에 적용돼 있다.
				ChangeNotice.workingTree(events, id);
			}
		});
	}

	public Operation pop(RepositoryId id, int index)
	{
		requireValidIndex(index);
		VcsAccess.Opened<StashOperations> opened = access.open(id, StashOperations.class);
		return queue.submit(id, OperationType.STASH, context -> {
			context.log("stash pop stash@{" + index + "}", ConsoleLevel.CMD);
			context.throwIfCancelled();
			try
			{
				opened.operations().pop(opened.handle(), index);
			}
			finally
			{
				// pop이 어떻게 실패하든 워킹 트리는 이미 바뀌어 있다. 적용은 됐는데 drop이 실패한
				// 경우(StashDropAfterApplyException)도, 적용 자체가 충돌한 경우도 마찬가지다.
				ChangeNotice.workingTree(events, id);
			}
		});
	}

	public Operation drop(RepositoryId id, int index)
	{
		requireValidIndex(index);
		VcsAccess.Opened<StashOperations> opened = access.open(id, StashOperations.class);
		return queue.submit(id, OperationType.STASH, context -> {
			context.log("stash drop stash@{" + index + "}", ConsoleLevel.CMD);
			context.throwIfCancelled();
			opened.operations().drop(opened.handle(), index);
			events.workingTreeChanged(id);
		});
	}

	private static void requireValidIndex(int index)
	{
		if(index < 0)
		{
			throw new IllegalArgumentException("stash index must be non-negative: " + index);
		}
	}
}