package dev.configflow.application.branch;

import dev.configflow.application.operation.ChangeNotice;
import dev.configflow.application.operation.OperationQueue;
import dev.configflow.application.vcs.VcsAccess;
import dev.configflow.domain.operation.ConsoleLevel;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationEvents;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.vcs.model.MergeRequest;
import dev.configflow.domain.vcs.port.BranchOperations;

import java.util.Objects;

/**
 * Use case: branch mutations, executed through the operation queue.
 *
 * <p>Everything here answers immediately with a {@code QUEUED} operation. Validation and
 * port resolution still happen <em>before</em> submitting: a request that cannot possibly work should be rejected with a 4xx now, not accepted and failed
 * asynchronously a second later.</p>
 */
public final class BranchService
{

	private final VcsAccess access;
	private final OperationQueue queue;
	private final OperationEvents events;

	public BranchService(VcsAccess access, OperationQueue queue, OperationEvents events)
	{
		this.access = Objects.requireNonNull(access, "access");
		this.queue = Objects.requireNonNull(queue, "queue");
		this.events = Objects.requireNonNull(events, "events");
	}

	/**
	 * Switches the working tree to {@code ref}.
	 *
	 * @throws IllegalArgumentException
	 * 		if the ref is blank
	 * @throws NoSuchElementException
	 * 		if no repository has the given id
	 * @throws UnsupportedOperationException
	 * 		if the provider cannot change branches
	 */
	public Operation checkout(RepositoryId id, String ref)
	{
		String target = requireRef(ref, "ref");
		VcsAccess.Opened<BranchOperations> opened = access.open(id, BranchOperations.class);

		return queue.submit(id, OperationType.CHECKOUT, context -> {
			context.log("checkout " + target, ConsoleLevel.CMD);
			context.throwIfCancelled();
			opened.operations().checkout(opened.handle(), target);
			// Both the head and the file states move, so nothing cached about this
			// repository survives a checkout.
			events.refsChanged(id);
			events.workingTreeChanged(id);
		});
	}

	/** Creates a branch, optionally checking it out. */
	public Operation createBranch(RepositoryId id, String name, String startPoint, boolean checkout)
	{
		String branchName = requireRef(name, "name");
		String from = startPoint == null || startPoint.isBlank() ? null : startPoint.trim();
		VcsAccess.Opened<BranchOperations> opened = access.open(id, BranchOperations.class);

		return queue.submit(id, OperationType.BRANCH_CREATE, context -> {
			context.log("branch " + branchName + (from == null ? "" : " " + from), ConsoleLevel.CMD);
			context.throwIfCancelled();
			opened.operations().createBranch(opened.handle(), branchName, from, checkout);
			events.refsChanged(id);
			if(checkout)
			{
				events.workingTreeChanged(id);
			}
		});
	}

	/** Deletes a local or remote branch. */
	public Operation deleteBranch(RepositoryId id, String name, boolean remote, boolean force)
	{
		String branchName = requireRef(name, "name");
		VcsAccess.Opened<BranchOperations> opened = access.open(id, BranchOperations.class);

		return queue.submit(id, OperationType.BRANCH_DELETE, context -> {
			// -D is the destructive one; the console should not claim otherwise.
			context.log("branch " + (force ? "-D " : "-d ") + branchName, ConsoleLevel.CMD);
			context.throwIfCancelled();
			opened.operations().deleteBranch(opened.handle(), branchName, remote, force);
			events.refsChanged(id);
		});
	}

	public Operation merge(RepositoryId id, String source, boolean fastForwardOnly, boolean squash)
	{
		String ref = requireRef(source, "source");
		MergeRequest request = new MergeRequest(ref, fastForwardOnly, squash);
		VcsAccess.Opened<BranchOperations> opened = access.open(id, BranchOperations.class);

		return queue.submit(id, OperationType.MERGE, context -> {
			context.log("merge " + ref + (fastForwardOnly ? " --ff-only" : "") + (squash ? " --squash" : ""), ConsoleLevel.CMD);
			context.throwIfCancelled();
			try
			{
				opened.operations().merge(opened.handle(), request);
			}
			finally
			{
				// 머지는 HEAD를 전진시키고 워킹 트리를 다시 쓴다 — 또는 충돌 상태로 남긴다.
				// 충돌로 실패한 경우가 바로 화면이 갱신돼야 하는 경우이므로, 어느 쪽이든 알린다.
				ChangeNotice.refsAndWorkingTree(events, id);
			}
		});
	}

	private static String requireRef(String ref, String field)
	{
		if(ref == null || ref.isBlank())
		{
			throw new IllegalArgumentException("'" + field + "' must not be blank");
		}
		return ref.trim();
	}
}
