package dev.configflow.application.tag;

import dev.configflow.application.operation.OperationQueue;
import dev.configflow.application.vcs.VcsAccess;
import dev.configflow.domain.operation.ConsoleLevel;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationEvents;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.vcs.model.RevisionId;
import dev.configflow.domain.vcs.port.TagOperations;

import java.util.Objects;

public final class TagService
{
	private final VcsAccess access;
	private final OperationQueue queue;
	private final OperationEvents events;

	public TagService(VcsAccess access, OperationQueue queue, OperationEvents events)
	{
		this.access = Objects.requireNonNull(access, "access");
		this.queue = Objects.requireNonNull(queue, "queue");
		this.events = Objects.requireNonNull(events, "events");
	}

	/**
	 * Creates a tag.
	 *
	 * @param target
	 * 		revision to tag; blank means HEAD
	 * @param message
	 * 		annotation text; blank writes a lightweight tag
	 */
	public Operation create(RepositoryId id, String name, String target, String message)
	{
		String tagName = requireName(name);
		RevisionId revision = isBlank(target) ? null : new RevisionId(target.trim());
		String annotation = isBlank(message) ? null : message;
		VcsAccess.Opened<TagOperations> opened = access.open(id, TagOperations.class);

		return queue.submit(id, OperationType.TAG, context -> {
			context.log("tag " + (annotation == null ? "" : "-a ") + tagName + (revision == null ? "" : " " + revision.value()), ConsoleLevel.CMD);
			context.throwIfCancelled();
			opened.operations().create(opened.handle(), tagName, revision, annotation);
			// A tag is just a ref: the working tree and HEAD are untouched, so only the
			// ref views need refreshing.
			events.refsChanged(id);
		});
	}

	/** Deletes a tag. */
	public Operation delete(RepositoryId id, String name)
	{
		String tagName = requireName(name);
		VcsAccess.Opened<TagOperations> opened = access.open(id, TagOperations.class);

		return queue.submit(id, OperationType.TAG, context -> {
			context.log("tag -d " + tagName, ConsoleLevel.CMD);
			context.throwIfCancelled();
			opened.operations().delete(opened.handle(), tagName);
			events.refsChanged(id);
		});
	}

	private static String requireName(String name)
	{
		if(isBlank(name))
		{
			throw new IllegalArgumentException("'name' must not be blank");
		}
		return name.trim();
	}

	private static boolean isBlank(String value)
	{
		return value == null || value.isBlank();
	}
}
