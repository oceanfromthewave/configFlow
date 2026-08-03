package dev.configflow.application.svn;

import dev.configflow.application.operation.OperationContext;
import dev.configflow.application.operation.OperationQueue;
import dev.configflow.application.vcs.VcsAccess;
import dev.configflow.domain.operation.ConsoleLevel;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationEvents;
import dev.configflow.domain.operation.OperationProgress;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.vcs.model.RemoteEntry;
import dev.configflow.domain.vcs.model.RevisionId;
import dev.configflow.domain.vcs.port.LockOperations;
import dev.configflow.domain.vcs.port.OperationMonitor;
import dev.configflow.domain.vcs.port.RemoteBrowseOperations;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Use case: the two things only SVN offers — locking paths and browsing the server without a working copy.
 *
 * <p>Locks are queued because they are network calls that can be slow or refused;
 * browsing is not, because the caller is waiting on the tree it asked for.</p>
 */
public final class SvnService
{
	private final VcsAccess access;
	private final OperationQueue queue;
	private final OperationEvents events;

	public SvnService(VcsAccess access, OperationQueue queue, OperationEvents events)
	{
		this.access = Objects.requireNonNull(access, "access");
		this.queue = Objects.requireNonNull(queue, "queue");
		this.events = Objects.requireNonNull(events, "events");
	}

	/** Locks the given working-copy paths so nobody else can commit them. */
	public Operation lock(RepositoryId id, List<Path> paths, String comment)
	{
		List<Path> targets = requirePaths(paths);
		VcsAccess.Opened<LockOperations> opened = access.open(id, LockOperations.class);

		return queue.submit(id, OperationType.LOCK, context -> {
			context.log("svn lock " + describe(targets), ConsoleLevel.CMD);
			opened.operations().lock(opened.handle(), targets, comment, monitorFor(context));
			// A lock shows up in the file's status, so the working tree view is now stale.
			events.workingTreeChanged(id);
		});
	}

	/** Releases locks; {@code breakLock} steals ones held by someone else. */
	public Operation unlock(RepositoryId id, List<Path> paths, boolean breakLock)
	{
		List<Path> targets = requirePaths(paths);
		VcsAccess.Opened<LockOperations> opened = access.open(id, LockOperations.class);

		return queue.submit(id, OperationType.UNLOCK, context -> {
			context.log("svn unlock " + describe(targets) + (breakLock ? " --force" : ""), ConsoleLevel.CMD);
			opened.operations().unlock(opened.handle(), targets, breakLock, monitorFor(context));
			events.workingTreeChanged(id);
		});
	}

	/**
	 * Lists one directory of the repository as it exists on the server.
	 *
	 * <p>The repository id only selects which engine to ask; the {@code url} is what
	 * actually gets browsed, and it may point anywhere that engine can reach.</p>
	 */
	public List<RemoteEntry> browse(RepositoryId id, String url, String revision)
	{
		if(url == null || url.isBlank())
		{
			throw new IllegalArgumentException("A 'url' is required");
		}
		VcsAccess.Opened<RemoteBrowseOperations> opened = access.open(id, RemoteBrowseOperations.class);
		RevisionId at = revision == null || revision.isBlank() ? null : new RevisionId(revision.trim());
		return opened.operations().browse(url.trim(), at);
	}

	private static List<Path> requirePaths(List<Path> paths)
	{
		if(paths == null || paths.isEmpty())
		{
			throw new IllegalArgumentException("At least one path is required");
		}
		return List.copyOf(paths);
	}

	private static String describe(List<Path> paths)
	{
		return paths.size() == 1 ? paths.get(0).toString() : paths.size() + " paths";
	}

	private static OperationMonitor monitorFor(OperationContext context)
	{
		return new OperationMonitor()
		{
			@Override
			public void onProgress(OperationProgress progress)
			{
				context.progress(progress);
			}

			@Override
			public boolean isCancelled()
			{
				return context.isCancelled();
			}
		};
	}
}