package dev.configflow.api.event;

import dev.configflow.domain.operation.OperationEvents;
import dev.configflow.domain.operation.WorkingTreeWatch;
import dev.configflow.domain.repository.RepositoryId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Watches working copies with a single {@link WatchService} and turns bursts of file events into one {@code workingTreeChanged} per repository.
 *
 * <p>Only changes made outside ConfigFlow need this; our own operations already fire the
 * event directly.</p>
 */
public final class FileSystemWorkingTreeWatch implements WorkingTreeWatch, AutoCloseable
{
	/** Directories the VCS owns: watching them would fire on every one of our own commits. */
	private static final Set<String> IGNORED = Set.of(".git", ".svn");

	/** How long the tree must be quiet before a burst becomes one refresh. */
	private static final long DEBOUNCE_MILLIS = 500;

	private static final Logger log = LoggerFactory.getLogger(FileSystemWorkingTreeWatch.class);

	private final OperationEvents events;
	private final WatchService service;
	private final Map<WatchKey, RepositoryId> watched = new ConcurrentHashMap<>();
	/** Repositories still wanted. Cancelling keys is not enough: events already queued outlive it. */
	private final Set<RepositoryId> active = ConcurrentHashMap.newKeySet();
	private final Thread poller;
	private volatile boolean running = true;

	public FileSystemWorkingTreeWatch(OperationEvents events)
	{
		this.events = Objects.requireNonNull(events, "events");
		try
		{
			this.service = FileSystems.getDefault().newWatchService();
		}
		catch(IOException e)
		{
			throw new UncheckedIOException("Cannot create a file system watch service", e);
		}
		this.poller = Thread.ofPlatform().daemon().name("working-tree-watch").start(this::pollLoop);
	}

	@Override
	public void watch(RepositoryId id, Path localPath)
	{
		Objects.requireNonNull(id, "id");
		active.add(id);
		register(id, localPath);
	}

	/** Registration without claiming the repository, so a late event cannot revive an unwatched one. */
	private void register(RepositoryId id, Path root)
	{
		try
		{
			registerTree(id, root);
		}
		catch(IOException e)
		{
			// An unwatchable repository still works; it just needs a manual refresh.
			log.warn("Cannot watch working tree of {}: {}", id.asString(), e.toString());
		}
	}

	@Override
	public void unwatch(RepositoryId id)
	{
		active.remove(id);
		watched.entrySet().removeIf(entry -> {
			if(!entry.getValue().equals(id))
			{
				return false;
			}
			entry.getKey().cancel();
			return true;
		});
	}

	// ponytail: registers every directory of the tree up front — a 100k-directory
	// working copy pays for that at startup. Switch to a native watcher if it bites.
	private void registerTree(RepositoryId id, Path root) throws IOException
	{
		Files.walkFileTree(root, new SimpleFileVisitor<Path>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
			{
				Path name = dir.getFileName();
				if(name != null && IGNORED.contains(name.toString()))
				{
					return FileVisitResult.SKIP_SUBTREE;
				}
				// Re-registering a directory returns its existing key, so this stays idempotent.
				watched.put(dir.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY), id);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private void pollLoop()
	{
		Set<RepositoryId> pending = new HashSet<>();
		while(running)
		{
			try
			{
				// Block while idle, debounce while a burst is in flight.
				WatchKey key = pending.isEmpty() ? service.take() : service.poll(DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
				if(key == null)
				{
					// Anything unwatched while the burst settled is no longer ours to report.
					pending.stream().filter(active::contains).forEach(events::workingTreeChanged);
					pending.clear();
					continue;
				}
				RepositoryId id = watched.get(key);
				if(id != null && active.contains(id) && drain(id, key))
				{
					pending.add(id);
				}
				if(!key.reset())
				{
					watched.remove(key);
				}
			}
			catch(ClosedWatchServiceException | InterruptedException e)
			{
				return;
			}
			catch(RuntimeException e)
			{
				log.warn("Working tree watch loop failed to handle an event", e);
			}
		}
	}

	/** @return whether anything worth reporting changed under {@code key}'s directory */
	private boolean drain(RepositoryId id, WatchKey key)
	{
		Path dir = (Path) key.watchable();
		boolean changed = false;
		for(WatchEvent<?> event : key.pollEvents())
		{
			if(event.kind() == OVERFLOW)
			{
				// Events were dropped, so assume the worst rather than miss a change.
				changed = true;
				continue;
			}
			Path name = (Path) event.context();
			if(IGNORED.contains(name.toString()))
			{
				continue;
			}
			changed = true;
			Path child = dir.resolve(name);
			if(event.kind() == ENTRY_CREATE && Files.isDirectory(child))
			{
				register(id, child);
			}
		}
		return changed;
	}

	@Override
	public void close() throws IOException
	{
		running = false;
		service.close();
		poller.interrupt();
	}
}