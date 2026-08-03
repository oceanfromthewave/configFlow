package dev.configflow.infrastructure.svn;

import dev.configflow.domain.operation.OperationCancelledException;
import dev.configflow.domain.operation.OperationProgress;
import dev.configflow.domain.vcs.capability.VcsCapability;
import dev.configflow.domain.vcs.exception.VcsException;
import dev.configflow.domain.vcs.exception.VcsPreconditionException;
import dev.configflow.domain.vcs.model.Author;
import dev.configflow.domain.vcs.model.ChangeType;
import dev.configflow.domain.vcs.model.CloneRequest;
import dev.configflow.domain.vcs.model.CommitRequest;
import dev.configflow.domain.vcs.model.ConflictedFile;
import dev.configflow.domain.vcs.model.FetchRequest;
import dev.configflow.domain.vcs.model.FileChange;
import dev.configflow.domain.vcs.model.HistoryQuery;
import dev.configflow.domain.vcs.model.IgnorePattern;
import dev.configflow.domain.vcs.model.Page;
import dev.configflow.domain.vcs.model.PullRequest;
import dev.configflow.domain.vcs.model.PushRequest;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.Revision;
import dev.configflow.domain.vcs.model.RevisionId;
import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.model.WorkingTreeStatus;
import dev.configflow.domain.vcs.port.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Stream;

import org.tmatesoft.svn.core.SVNCancelException;
import org.tmatesoft.svn.core.SVNCommitInfo;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNLogEntry;
import org.tmatesoft.svn.core.SVNProperty;
import org.tmatesoft.svn.core.SVNPropertyValue;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.wc.ISVNEventHandler;
import org.tmatesoft.svn.core.wc.SVNClientManager;
import org.tmatesoft.svn.core.wc.SVNEvent;
import org.tmatesoft.svn.core.wc.SVNPropertyData;
import org.tmatesoft.svn.core.wc.SVNRevision;
import org.tmatesoft.svn.core.wc.SVNStatus;
import org.tmatesoft.svn.core.wc.SVNStatusType;
import org.tmatesoft.svn.core.wc.SVNWCUtil;

/**
 * SVNKit-based Subversion provider.
 *
 * <p>Implements checkout and working-copy status; update/commit/lock arrive in later
 * slices. Note the capability set has no {@code STAGING}: SVN has no index, so every local change is reported as unstaged.</p>
 */
public final class SvnVcsProvider implements VcsProvider, RepositoryOperations, WorkingTreeOperations, RemoteSyncOperations, CommitOperations
{

	private static final Set<VcsCapability> CAPABILITIES = Set.of(VcsCapability.MERGE, VcsCapability.LOCK, VcsCapability.REMOTE_BROWSE);

	@Override
	public VcsType type()
	{
		return VcsType.SVN;
	}

	@Override
	public Set<VcsCapability> capabilities()
	{
		return CAPABILITIES;
	}

	@Override
	public boolean detect(Path localPath)
	{
		return localPath != null && SVNWCUtil.isVersionedDirectory(localPath.toFile());
	}

	@Override
	public RepositoryHandle open(Path localPath)
	{
		if(!detect(localPath))
		{
			throw new IllegalArgumentException("Not an SVN working copy: " + localPath);
		}
		return new RepositoryHandle(localPath, VcsType.SVN);
	}

	/** Checks out a working copy — SVN's equivalent of a clone. */
	@Override
	public RepositoryHandle cloneRepository(CloneRequest request, OperationMonitor monitor)
	{
		// Parsed before anything is created on disk: a malformed URL is a bad request,
		// not a failed checkout, and must not trigger the cleanup below.
		SVNURL url = parseUrl(request.url());
		SVNClientManager clients = SVNClientManager.newInstance();
		clients.setEventHandler(new SvnProgress(monitor, "checking out"));
		try
		{
			clients.getUpdateClient().doCheckout(url, request.localPath().toFile(), SVNRevision.HEAD, SVNRevision.HEAD, SVNDepth.INFINITY, false);
			return new RepositoryHandle(request.localPath(), VcsType.SVN);
		}
		catch(SVNException e)
		{
			// A checkout that stopped part-way leaves a directory that looks like a
			// working copy but cannot be used. Nothing here existed before, so removing
			// it is safe, and the next attempt would otherwise refuse to write into it.
			deleteQuietly(request.localPath());
			if(monitor.isCancelled() || e instanceof SVNCancelException)
			{
				throw new OperationCancelledException("The checkout was cancelled");
			}
			throw translate(e, "Failed to check out " + request.url());
		}
		finally
		{
			clients.dispose();
		}
	}

	@Override
	public RepositoryHandle init(Path path)
	{
		// `svnadmin create` makes a server-side repository, not a working copy; there is
		// no local-only equivalent of `git init`.
		throw new UnsupportedOperationException("SVN cannot initialise a local repository");
	}

	@Override
	public WorkingTreeStatus status(RepositoryHandle repo)
	{
		Path root = repo.localPath().toAbsolutePath().normalize();
		List<FileChange> unstaged = new ArrayList<>();
		List<ConflictedFile> conflicted = new ArrayList<>();
		SVNClientManager clients = SVNClientManager.newInstance();
		try
		{
			// remote=false keeps this local and instant; reportAll=false drops the
			// unchanged entries, which are the vast majority of a working copy.
			clients.getStatusClient().doStatus(root.toFile(), SVNRevision.WORKING, SVNDepth.INFINITY, false, false, false, false,
					status -> collect(root, status, unstaged, conflicted), null);
		}
		catch(SVNException e)
		{
			throw translate(e, "Failed to read status of " + root);
		}
		finally
		{
			clients.dispose();
		}
		unstaged.sort(Comparator.comparing(FileChange::path));
		conflicted.sort(Comparator.comparing(ConflictedFile::path));
		// No index and no rebase: SVN has neither, so both are constants here.
		return new WorkingTreeStatus(List.of(), unstaged, conflicted, false);
	}

	@Override
	public void stage(RepositoryHandle repo, List<Path> paths)
	{
		throw new UnsupportedOperationException("SVN has no staging area");
	}

	@Override
	public void unstage(RepositoryHandle repo, List<Path> paths)
	{
		throw new UnsupportedOperationException("SVN has no staging area");
	}

	@Override
	public void discard(RepositoryHandle repo, List<Path> paths)
	{
		if(paths.isEmpty())
		{
			return;
		}
		File[] targets = resolveAll(repo, paths);
		SVNClientManager clients = SVNClientManager.newInstance();
		try
		{
			// Depth INFINITY so reverting a directory takes everything below it, which is
			// what the user selected in the UI.
			clients.getWCClient().doRevert(targets, SVNDepth.INFINITY, null);
		}
		catch(SVNException e)
		{
			throw translate(e, "Failed to revert paths in " + repo.localPath());
		}
		finally
		{
			clients.dispose();
		}
	}

	@Override
	public void ignore(RepositoryHandle repo, IgnorePattern pattern)
	{
		// svn:ignore is a property on one directory whose value is a list of name globs,
		// so a rule like "build/*.log" becomes "*.log" on the "build" directory. Split by
		// hand rather than through Path: a bare glob like "*.log" is not a legal path
		// component on Windows.
		String raw = pattern.pattern();
		int slash = raw.lastIndexOf('/');
		File directory = (slash < 0 ? repo.localPath() : repo.localPath().resolve(raw.substring(0, slash))).toFile();
		String glob = slash < 0 ? raw : raw.substring(slash + 1);
		SVNClientManager clients = SVNClientManager.newInstance();
		try
		{
			List<String> globs = readIgnores(clients, directory);
			if(globs.contains(glob))
			{
				// Already ignored: rewriting the property would only dirty the working copy.
				return;
			}
			globs.add(glob);
			clients.getWCClient().doSetProperty(directory, SVNProperty.IGNORE, SVNPropertyValue.create(String.join("\n", globs) + "\n"), false,
					SVNDepth.EMPTY, null, null);
		}
		catch(SVNException e)
		{
			throw translate(e, "Failed to ignore " + pattern.pattern() + " in " + repo.localPath());
		}
		finally
		{
			clients.dispose();
		}
	}

	@Override
	public void fetch(RepositoryHandle repo, FetchRequest request, OperationMonitor monitor)
	{
		// SVN has no separate download step: update fetches and integrates in one go.
		throw new UnsupportedOperationException("SVN has no fetch; use update");
	}

	@Override
	public void pull(RepositoryHandle repo, PullRequest request, OperationMonitor monitor)
	{
		throw new UnsupportedOperationException("SVN has no pull; use update");
	}

	@Override
	public void push(RepositoryHandle repo, PushRequest request, OperationMonitor monitor)
	{
		// An SVN commit writes straight to the server, so nothing is ever left to upload.
		throw new UnsupportedOperationException("SVN commits go straight to the server; there is nothing to push");
	}

	@Override
	public void update(RepositoryHandle repo, Long revision, OperationMonitor monitor)
	{
		if(revision != null && revision < 1)
		{
			throw new IllegalArgumentException("revision must be positive: " + revision);
		}
		SVNClientManager clients = SVNClientManager.newInstance();
		clients.setEventHandler(new SvnProgress(monitor, "Updating"));
		try
		{
			clients.getUpdateClient().doUpdate(repo.localPath().toFile(), revision == null ? SVNRevision.HEAD : SVNRevision.create(revision),
					SVNDepth.INFINITY, false, false);
		}
		catch(SVNException e)
		{
			// Unlike a checkout there is nothing to clean up: an interrupted update leaves
			// a working copy that `cleanup` can still repair.
			if(monitor.isCancelled() || e instanceof SVNCancelException)
			{
				throw new OperationCancelledException("The update was cancelled");
			}
			throw translate(e, "Failed to update " + repo.localPath());
		}
		finally
		{
			clients.dispose();
		}
	}

	@Override
	public void cleanup(RepositoryHandle repo)
	{
		SVNClientManager clients = SVNClientManager.newInstance();
		try
		{
			clients.getWCClient().doCleanup(repo.localPath().toFile());
		}
		catch(SVNException e)
		{
			throw translate(e, "Failed to clean up " + repo.localPath());
		}
		finally
		{
			clients.dispose();
		}
	}

	/**
	 * Commits the given paths straight to the server — an SVN commit is a push.
	 *
	 * <p>With no staging area to read from, {@link CommitRequest#paths()} is what selects
	 * the content; an empty list means the whole working copy.</p>
	 */
	@Override
	public RevisionId commit(RepositoryHandle repo, CommitRequest request)
	{
		if(request.amend())
		{
			// A committed SVN revision is immutable, hence no AMEND capability.
			throw new UnsupportedOperationException("SVN cannot amend a committed revision");
		}
		File[] targets = request.paths().isEmpty()
				? new File[] { repo.localPath().toFile() }
				: resolveAll(repo, request.paths());
		SVNClientManager clients = SVNClientManager.newInstance();
		try
		{
			SVNCommitInfo info = clients.getCommitClient().doCommit(targets, request.keepLock(), request.message(), null, null, false, false,
					SVNDepth.INFINITY);
			if(info.getNewRevision() < 0)
			{
				// SVNKit reports "nothing happened" with revision -1 instead of failing;
				// without this the caller would be handed a revision id of "r-1".
				throw new VcsPreconditionException("Nothing to commit in " + repo.localPath());
			}
			return toRevisionId(info.getNewRevision());
		}
		catch(SVNException e)
		{
			throw translate(e, "Failed to commit in " + repo.localPath());
		}
		finally
		{
			clients.dispose();
		}
	}

	/**
	 * Walks history newest-first, one page at a time.
	 *
	 * <p>The cursor is the revision that did not fit on the previous page, so the next
	 * call simply starts the walk there. Filters SVN cannot express server-side are applied as the entries arrive, and the walk stops itself once the page is
	 * full — history is unbounded and must never be materialised whole.</p>
	 */
	@Override
	public Page<Revision> history(RepositoryHandle repo, HistoryQuery query)
	{
		if(query.branch() != null)
		{
			// SVN branches are ordinary directories, so there is no ref to filter by.
			throw new IllegalArgumentException("SVN has no branches; filter history by path instead");
		}
		File target = query.path() == null ? repo.localPath().toFile() : repo.localPath().resolve(query.path()).toFile();
		SVNRevision start = query.cursor() == null ? SVNRevision.HEAD : SVNRevision.create(parseRevision(query.cursor()));

		List<SVNLogEntry> entries = new ArrayList<>(query.limit() + 1);
		SVNClientManager clients = SVNClientManager.newInstance();
		try
		{
			clients.getLogClient().doLog(new File[] { target }, start, SVNRevision.create(1L), false, false, 0, entry -> {
				if(!matches(entry, query))
				{
					return;
				}
				entries.add(entry);
				if(entries.size() > query.limit())
				{
					// One entry past the page: enough to know the next cursor, and the
					// signal SVNKit understands as "stop handing me entries".
					throw new SVNCancelException(SVNErrorMessage.create(SVNErrorCode.CEASE_INVOCATION, "page complete"));
				}
			});
		}
		catch(SVNException e)
		{
			if(!isPageComplete(e))
			{
				throw translate(e, "Failed to read history of " + repo.localPath());
			}
		}
		finally
		{
			clients.dispose();
		}

		int pageSize = Math.min(entries.size(), query.limit());
		String nextCursor = entries.size() > query.limit() ? toRevisionId(entries.get(query.limit()).getRevision()).value() : null;
		List<Revision> items = new ArrayList<>(pageSize);
		for(int i = 0; i < pageSize; i++)
		{
			items.add(toRevision(entries.get(i)));
		}
		return new Page<>(items, nextCursor);
	}

	@Override
	public Revision show(RepositoryHandle repo, RevisionId id)
	{
		SVNRevision revision = SVNRevision.create(parseRevision(id.value()));
		List<SVNLogEntry> found = new ArrayList<>(1);
		SVNClientManager clients = SVNClientManager.newInstance();
		try
		{
			clients.getLogClient().doLog(new File[] { repo.localPath().toFile() }, revision, revision, false, false, 1, found::add);
		}
		catch(SVNException e)
		{
			throw translate(e, "Failed to read revision " + id.value() + " of " + repo.localPath());
		}
		finally
		{
			clients.dispose();
		}
		if(found.isEmpty())
		{
			throw new NoSuchElementException("No such revision: " + id.value());
		}
		return toRevision(found.get(0));
	}

	private static void collect(Path root, SVNStatus status, List<FileChange> unstaged, List<ConflictedFile> conflicted)
	{
		Path relative = relativize(root, status.getFile());
		if(relative == null)
		{
			return;
		}
		// Covers both text and tree conflicts; the node status of a tree conflict can
		// still read as normal.
		if(status.isConflicted() || status.getTreeConflict() != null)
		{
			conflicted.add(ConflictedFile.unresolved(relative));
			return;
		}
		ChangeType type = toChangeType(status);
		if(type != null)
		{
			unstaged.add(FileChange.of(relative, type));
		}
	}

	/** Working-copy-relative path, or {@code null} for the root directory itself. */
	private static Path relativize(Path root, File file)
	{
		if(file == null)
		{
			return null;
		}
		Path relative = root.relativize(file.toPath().toAbsolutePath().normalize());
		return relative.toString().isEmpty() ? null : relative;
	}

	/** Maps SVN's status vocabulary onto ours, or {@code null} when nothing changed. */
	private static ChangeType toChangeType(SVNStatus status)
	{
		SVNStatusType node = status.getNodeStatus();
		if(node == SVNStatusType.STATUS_ADDED || node == SVNStatusType.STATUS_REPLACED)
		{
			return ChangeType.ADDED;
		}
		if(node == SVNStatusType.STATUS_DELETED || node == SVNStatusType.STATUS_MISSING)
		{
			return ChangeType.DELETED;
		}
		if(node == SVNStatusType.STATUS_MODIFIED || node == SVNStatusType.STATUS_MERGED)
		{
			return ChangeType.MODIFIED;
		}
		if(node == SVNStatusType.STATUS_UNVERSIONED)
		{
			return ChangeType.UNTRACKED;
		}
		if(node == SVNStatusType.STATUS_IGNORED)
		{
			return ChangeType.IGNORED;
		}
		// A file whose content is untouched but whose properties changed is still a
		// local change the user has to commit.
		if(status.getPropertiesStatus() == SVNStatusType.STATUS_MODIFIED)
		{
			return ChangeType.MODIFIED;
		}
		return null;
	}

	/**
	 * Maps an SVNKit failure onto the exception the API layer renders: 400 for a target
	 * that cannot be used, 404 for one that does not exist, 500 for everything else.
	 */
	private static RuntimeException translate(SVNException e, String failureMessage)
	{
		SVNErrorCode code = e.getErrorMessage() == null ? null : e.getErrorMessage().getErrorCode();
		if(code == SVNErrorCode.WC_NOT_WORKING_COPY || code == SVNErrorCode.RA_ILLEGAL_URL)
		{
			return new IllegalArgumentException(failureMessage, e);
		}
		if(code == SVNErrorCode.FS_NOT_FOUND || code == SVNErrorCode.RA_LOCAL_REPOS_NOT_FOUND || code == SVNErrorCode.RA_DAV_PATH_NOT_FOUND
				|| code == SVNErrorCode.RA_SVN_REPOS_NOT_FOUND || code == SVNErrorCode.WC_PATH_NOT_FOUND || code == SVNErrorCode.ENTRY_NOT_FOUND
				|| code == SVNErrorCode.FS_NO_SUCH_REVISION)
		{
			return new NoSuchElementException(failureMessage);
		}
		return new VcsException(failureMessage, e);
	}

	/** Working-copy files for caller-supplied relative paths. */
	private static File[] resolveAll(RepositoryHandle repo, List<Path> paths)
	{
		return paths.stream().map(p -> repo.localPath().resolve(p).toFile()).toArray(File[]::new);
	}

	/** The directory's current svn:ignore globs, mutable so a new one can be appended. */
	private static List<String> readIgnores(SVNClientManager clients, File directory) throws SVNException
	{
		SVNPropertyData property = clients.getWCClient().doGetProperty(directory, SVNProperty.IGNORE, SVNRevision.WORKING, SVNRevision.WORKING);
		List<String> globs = new ArrayList<>();
		if(property == null || property.getValue() == null || property.getValue().getString() == null)
		{
			return globs;
		}
		for(String line : property.getValue().getString().split("\n"))
		{
			String glob = line.trim();
			if(!glob.isEmpty())
			{
				globs.add(glob);
			}
		}
		return globs;
	}

	/** True when the exception is our own "the page is full" signal rather than a failure. */
	private static boolean isPageComplete(SVNException e)
	{
		SVNErrorCode code = e.getErrorMessage() == null ? null : e.getErrorMessage().getErrorCode();
		return code == SVNErrorCode.CEASE_INVOCATION;
	}

	private static boolean matches(SVNLogEntry entry, HistoryQuery query)
	{
		if(query.author() != null && (entry.getAuthor() == null || !entry.getAuthor().contains(query.author())))
		{
			return false;
		}
		if(query.messageContains() != null && (entry.getMessage() == null || !entry.getMessage().contains(query.messageContains())))
		{
			return false;
		}
		if(entry.getDate() == null)
		{
			// A revision with no date cannot be placed in a time window either way; keep it
			// unless the caller asked for one.
			return query.from() == null && query.to() == null;
		}
		Instant timestamp = entry.getDate().toInstant();
		return (query.from() == null || !timestamp.isBefore(query.from())) && (query.to() == null || !timestamp.isAfter(query.to()));
	}

	private static Revision toRevision(SVNLogEntry entry)
	{
		long number = entry.getRevision();
		// SVN revisions are global snapshots taken one after another, so the preceding
		// number really is this revision's parent; r1 is the first and has none.
		List<RevisionId> parents = number > 1 ? List.of(toRevisionId(number - 1)) : List.of();
		String author = entry.getAuthor() == null ? "(anonymous)" : entry.getAuthor();
		Instant timestamp = entry.getDate() == null ? Instant.EPOCH : entry.getDate().toInstant();
		String message = entry.getMessage() == null ? "" : entry.getMessage();
		// No labels: SVN branches and tags are directories, not decorations on a revision.
		return new Revision(toRevisionId(number), parents, new Author(author, null), timestamp, message, List.of());
	}

	private static RevisionId toRevisionId(long revision)
	{
		return new RevisionId("r" + revision);
	}

	/** Accepts both {@code r123} and {@code 123}, the two ways a cursor reaches us. */
	private static long parseRevision(String value)
	{
		String digits = value.length() > 1 && (value.charAt(0) == 'r' || value.charAt(0) == 'R') ? value.substring(1) : value;
		try
		{
			long revision = Long.parseLong(digits);
			if(revision < 1)
			{
				throw new IllegalArgumentException("Not an SVN revision: " + value);
			}
			return revision;
		}
		catch(NumberFormatException e)
		{
			throw new IllegalArgumentException("Not an SVN revision: " + value, e);
		}
	}

	private static SVNURL parseUrl(String url)
	{
		try
		{
			return SVNURL.parseURIEncoded(url);
		}
		catch(SVNException e)
		{
			throw new IllegalArgumentException("Not a valid SVN URL: " + url, e);
		}
	}

	private static void deleteQuietly(Path path)
	{
		try(Stream<Path> paths = Files.walk(path))
		{
			paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
		catch(IOException | RuntimeException ignored)
		{
			// Nothing sensible to do; the caller is already reporting a failure.
		}
	}

	/**
	 * Carries checkout progress outward and cancellation inward.
	 *
	 * <p>SVNKit calls {@code checkCancelled} between files, which is the only point a
	 * checkout can stop without leaving a half-written working copy behind.</p>
	 */
	private record SvnProgress(OperationMonitor monitor, String phase) implements ISVNEventHandler
	{

		@Override
		public void handleEvent(SVNEvent event, double progress)
		{
			File file = event.getFile();
			// SVNKit's progress argument is UNKNOWN here, so the phase line is all we can
			// honestly report.
			monitor.onProgress(OperationProgress.indeterminate(file == null ? phase : phase + " " + file.getName()));
		}

		@Override
		public void checkCancelled() throws SVNCancelException
		{
			if(monitor.isCancelled())
			{
				throw new SVNCancelException();
			}
		}
	}
}