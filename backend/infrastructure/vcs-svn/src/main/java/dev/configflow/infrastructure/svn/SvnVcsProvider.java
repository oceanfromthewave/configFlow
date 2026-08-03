package dev.configflow.infrastructure.svn;

import dev.configflow.domain.operation.OperationCancelledException;
import dev.configflow.domain.operation.OperationProgress;
import dev.configflow.domain.vcs.capability.VcsCapability;
import dev.configflow.domain.vcs.exception.VcsException;
import dev.configflow.domain.vcs.model.ChangeType;
import dev.configflow.domain.vcs.model.CloneRequest;
import dev.configflow.domain.vcs.model.ConflictedFile;
import dev.configflow.domain.vcs.model.FileChange;
import dev.configflow.domain.vcs.model.IgnorePattern;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.model.WorkingTreeStatus;
import dev.configflow.domain.vcs.port.OperationMonitor;
import dev.configflow.domain.vcs.port.RepositoryOperations;
import dev.configflow.domain.vcs.port.VcsProvider;
import dev.configflow.domain.vcs.port.WorkingTreeOperations;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.tmatesoft.svn.core.SVNCancelException;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.wc.ISVNEventHandler;
import org.tmatesoft.svn.core.wc.SVNClientManager;
import org.tmatesoft.svn.core.wc.SVNEvent;
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
public final class SvnVcsProvider implements VcsProvider, RepositoryOperations, WorkingTreeOperations
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
		clients.setEventHandler(new CheckoutProgress(monitor));
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
			throw new VcsException("Failed to check out " + request.url(), e);
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
			throw new VcsException("Failed to read status of " + root, e);
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
		// SVN does support this (`svn revert`); wired up in the update+commit slice.
		throw new UnsupportedOperationException("Discarding SVN changes is not supported yet");
	}

	@Override
	public void ignore(RepositoryHandle repo, IgnorePattern pattern)
	{
		// Likewise: the svn:ignore property lands with the commit slice.
		throw new UnsupportedOperationException("SVN ignore rules are not supported yet");
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
	private record CheckoutProgress(OperationMonitor monitor) implements ISVNEventHandler
	{

		@Override
		public void handleEvent(SVNEvent event, double progress)
		{
			File file = event.getFile();
			// SVNKit's progress argument is UNKNOWN for checkout, so the phase line is
			// all we can honestly report.
			monitor.onProgress(OperationProgress.indeterminate(file == null ? "Checking out" : "Checking out " + file.getName()));
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