package dev.configflow.infrastructure.git;

import dev.configflow.domain.credential.RemoteCredentialResolver;
import dev.configflow.domain.operation.OperationCancelledException;
import dev.configflow.domain.vcs.exception.MergeConflictException;
import dev.configflow.domain.vcs.exception.VcsAuthenticationRequiredException;
import dev.configflow.domain.vcs.exception.VcsException;
import dev.configflow.domain.vcs.exception.VcsNetworkException;
import dev.configflow.domain.vcs.exception.VcsPreconditionException;
import dev.configflow.domain.vcs.model.CloneRequest;
import dev.configflow.domain.vcs.model.FetchRequest;
import dev.configflow.domain.vcs.model.PullRequest;
import dev.configflow.domain.vcs.model.PushRequest;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.port.OperationMonitor;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.*;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.errors.CanceledException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefLeaseSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

/**
 * Talking to remotes: fetch, pull, push, clone.
 *
 * <p>Held by {@link GitVcsProvider}, which exposes these through the
 * {@code RemoteSyncOperations} and {@code RepositoryOperations} ports.</p>
 */
final class GitRemotes
{

	private final GitRepositoryAccess access;
	private final RemoteCredentialResolver credentials;

	GitRemotes(GitRepositoryAccess access)
	{
		this(access, (host, protocol) -> Optional.empty());
	}

	GitRemotes(GitRepositoryAccess access, RemoteCredentialResolver credentials)
	{
		this.access = access;
		this.credentials = credentials;
	}

	void fetch(RepositoryHandle repo, FetchRequest request, OperationMonitor monitor)
	{
		String remote = remoteNameOf(request.remote());
		String target = repo.localPath().toString();
		UsernamePasswordCredentialsProvider cp = null;
		try(Git git = access.open(repo))
		{
			target = requireRemoteUrl(git, remote);
			cp = credentialsFor(target);
			git.fetch().setRemote(remote).setRemoveDeletedRefs(request.prune()).setCredentialsProvider(cp)
					.setProgressMonitor(new JGitProgressMonitor(monitor)).call();
		}
		catch(GitAPIException e)
		{
			throw translate(e, monitor, target, "fetch");
		}
		finally
		{
			if(cp != null)
			{
				cp.clear();
			}
		}
	}

	void pull(RepositoryHandle repo, PullRequest request, OperationMonitor monitor)
	{
		String remote = remoteNameOf(request.remote());
		String target = repo.localPath().toString();
		UsernamePasswordCredentialsProvider cp = null;
		try(Git git = access.open(repo))
		{
			target = requireRemoteUrl(git, remote);
			cp = credentialsFor(target);
			PullResult result = git.pull().setRemote(remote).setRebase(request.strategy() == PullRequest.Strategy.REBASE).setCredentialsProvider(cp)
					.setProgressMonitor(new JGitProgressMonitor(monitor)).call();

			// A pull that could not integrate is not an exception in JGit: it reports the
			// conflict in the result and leaves the working tree for the user to fix.
			if(result.getMergeResult() != null && result.getMergeResult().getConflicts() != null && !result.getMergeResult().getConflicts().isEmpty())
			{
				throw new MergeConflictException(result.getMergeResult().getConflicts().keySet().stream().map(Path::of).toList());
			}
			if(result.getRebaseResult() != null && !result.getRebaseResult().getStatus().isSuccessful())
			{
				throw new VcsPreconditionException("Rebase during pull stopped: " + result.getRebaseResult().getStatus());
			}
			if(!result.isSuccessful())
			{
				throw new VcsException("Pull failed in " + repo.localPath());
			}
		}
		catch(GitAPIException e)
		{
			throw translate(e, monitor, target, "pull");
		}
		finally
		{
			if(cp != null)
			{
				cp.clear();
			}
		}
	}

	void push(RepositoryHandle repo, PushRequest request, OperationMonitor monitor)
	{
		String remote = remoteNameOf(request.remote());
		String target = repo.localPath().toString();
		UsernamePasswordCredentialsProvider cp = null;
		try(Git git = access.open(repo))
		{
			target = requireRemoteUrl(git, remote);
			cp = credentialsFor(target);
			var push = git.push().setRemote(remote).setCredentialsProvider(cp).setProgressMonitor(new JGitProgressMonitor(monitor));
			if(request.forceWithLease())
			{
				push.setForce(true).setRefLeaseSpecs(leaseFor(git.getRepository(), remote));
			}
			if(request.pushTags())
			{
				push.setPushTags();
			}
			Iterable<PushResult> results = push.call();
			checkRejections(results);
			updateTrackingRefs(git.getRepository(), remote, results);
		}
		catch(GitAPIException e)
		{
			throw translate(e, monitor, target, "push");
		}
		catch(IOException e)
		{
			throw new VcsException("Pushed, but could not record the new remote state", e);
		}
		finally
		{
			if(cp != null)
			{
				cp.clear();
			}
		}
	}

	/**
	 * Moves {@code refs/remotes/<remote>/<branch>} to what we just pushed.
	 *
	 * <p>Plain {@code git push} does this and JGit does not, which leaves the app
	 * believing the remote is wherever it was at the last fetch. Two things break as a result: the branch list shows a stale {@code origin/...}, and a later
	 * force-with-lease is refused by the user's own push.</p>
	 */
	private static void updateTrackingRefs(Repository repository, String remote, Iterable<PushResult> results) throws IOException
	{
		for(PushResult result : results)
		{
			for(RemoteRefUpdate update : result.getRemoteUpdates())
			{
				if(update.getStatus() != RemoteRefUpdate.Status.OK || update.getNewObjectId() == null || !update.getRemoteName().startsWith(Constants.R_HEADS))
				{
					continue;
				}
				String branch = update.getRemoteName().substring(Constants.R_HEADS.length());
				RefUpdate tracking = repository.updateRef(Constants.R_REMOTES + remote + "/" + branch);
				tracking.setNewObjectId(update.getNewObjectId());
				// A force push moves the ref backwards, which is not fast-forward.
				tracking.setForceUpdate(true);
				tracking.update();
			}
		}
	}

	/**
	 * Builds the lease that makes {@code --force-with-lease} mean anything.
	 *
	 * <p>The lease says "overwrite the remote branch only if it still points where I last
	 * saw it". That last-seen value is exactly the remote-tracking ref, so a colleague who pushed after our last fetch causes the push to be rejected instead
	 * of erasing their work. An empty lease list is not a weaker version of this — it is a plain {@code --force}, which is the thing the option exists to
	 * avoid.</p>
	 *
	 * @throws VcsPreconditionException
	 * 		if there is nothing to lease against, since forcing blindly is never what the user asked for
	 */
	private static List<RefLeaseSpec> leaseFor(Repository repository, String remote)
	{
		try
		{
			String branch = repository.getBranch();
			if(branch == null || ObjectId.isId(branch))
			{
				throw new VcsPreconditionException("Cannot force-with-lease from a detached HEAD: check out a branch first");
			}
			Ref tracking = repository.findRef(Constants.R_REMOTES + remote + "/" + branch);
			if(tracking == null)
			{
				// Never fetched this branch, so we have no idea what is on the remote and
				// no basis for claiming our version supersedes it.
				throw new VcsPreconditionException("No remote-tracking ref for " + remote + "/" + branch + "; fetch before forcing");
			}
			return List.of(new RefLeaseSpec(Constants.R_HEADS + branch, tracking.getObjectId().name()));
		}
		catch(IOException e)
		{
			throw new VcsException("Could not read the remote-tracking state of " + remote, e);
		}
	}

	private static String remoteNameOf(String requested)
	{
		return hasText(requested) ? requested.trim() : Constants.DEFAULT_REMOTE_NAME;
	}

	/**
	 * Resolves a remote name to its URL, failing fast if it is not configured.
	 *
	 * <p>Two reasons this is worth doing up front. A name that does not exist should say
	 * so — letting JGit fail produces a message about the local path, which tells the user nothing. And when the transport does fail, the URL is what
	 * identifies the host an authentication prompt has to name.</p>
	 */
	private static String requireRemoteUrl(Git git, String remote)
	{
		String url = git.getRepository().getConfig().getString("remote", remote, "url");
		if(url == null)
		{
			throw new NoSuchElementException("No such remote: " + remote);
		}
		return url;
	}

	private UsernamePasswordCredentialsProvider credentialsFor(String url)
	{
		return credentials.resolve(hostOf(url), protocolOf(url))
				.map(c -> new UsernamePasswordCredentialsProvider(c.username() == null ? "" : c.username(), c.secret())).orElse(null);
	}

	RepositoryHandle cloneRepository(CloneRequest request, OperationMonitor monitor)
	{
		UsernamePasswordCredentialsProvider cp = credentialsFor(request.url());
		try
		{
			Git.cloneRepository().setURI(request.url()).setDirectory(request.localPath().toFile()).setCredentialsProvider(cp)
					.setProgressMonitor(new JGitProgressMonitor(monitor)).call().close();
			return new RepositoryHandle(request.localPath(), VcsType.GIT);
		}
		catch(GitAPIException | RuntimeException e)
		{
			// A clone that stopped part-way leaves a directory that looks like a
			// repository but cannot be used, and the next attempt would refuse to write
			// into it. Nothing here existed before, so removing it is safe.
			deleteQuietly(request.localPath());
			throw translate(e, monitor, request.url(), "clone");
		}
		finally
		{
			if(cp != null)
			{
				cp.clear();
			}
		}
	}

	RepositoryHandle init(Path path)
	{
		try
		{
			Git.init().setDirectory(path.toFile()).call().close();
			return new RepositoryHandle(path, VcsType.GIT);
		}
		catch(GitAPIException e)
		{
			throw new VcsException("Failed to initialise a repository at " + path, e);
		}
	}

	/**
	 * A rejected push is a status in the result, not an exception.
	 *
	 * <p>Without this a non-fast-forward push — the case where someone else pushed first
	 * and your commits did not land — would be reported to the user as a success.</p>
	 */
	private static void checkRejections(Iterable<PushResult> results)
	{
		List<String> rejected = new ArrayList<>();
		for(PushResult result : results)
		{
			for(RemoteRefUpdate update : result.getRemoteUpdates())
			{
				if(isRejection(update.getStatus()))
				{
					rejected.add(update.getRemoteName() + ": " + update.getStatus() + (update.getMessage() == null ? "" : " (" + update.getMessage() + ")"));
				}
			}
		}
		if(!rejected.isEmpty())
		{
			// The user can pull and retry, so this is a precondition rather than a fault.
			throw new VcsPreconditionException("The remote rejected this push — " + String.join("; ", rejected));
		}
	}

	private static boolean isRejection(RemoteRefUpdate.Status status)
	{
		return switch(status)
		{
			case REJECTED_NONFASTFORWARD, REJECTED_NODELETE, REJECTED_REMOTE_CHANGED, REJECTED_OTHER_REASON, NON_EXISTING -> true;
			default -> false;
		};
	}

	/**
	 * Turns a JGit failure into the right domain exception.
	 *
	 * <p>The three cases matter to the user in different ways: bad credentials mean
	 * "enter them again", an unreachable host means "try later", and a cancellation is not a failure at all.</p>
	 */
	private static RuntimeException translate(Exception e, OperationMonitor monitor, String target, String action)
	{
		// Ask the monitor before reading the exception. A cancelled clone surfaces as
		// "Missing unknown <sha>" rather than anything cancellation-shaped, and our own
		// request to stop is a far more reliable signal than JGit's failure mode.
		if(monitor.isCancelled() || hasCause(e, CanceledException.class))
		{
			return new OperationCancelledException("The " + action + " was cancelled");
		}
		if(e instanceof InvalidRemoteException || looksLikeUnknownRemote(e))
		{
			return new NoSuchElementException("No such remote: " + target);
		}
		if(e instanceof TransportException)
		{
			if(looksLikeAuthFailure(e))
			{
				return new VcsAuthenticationRequiredException(hostOf(target), protocolOf(target), e);
			}
			return new VcsNetworkException("Could not reach " + target + " to " + action, e);
		}
		return new VcsException("Failed to " + action + " " + target, e);
	}

	/**
	 * Push reports an unknown remote as a plain {@code TransportException}, unlike fetch which has a typed one, so the message is the only thing that
	 * distinguishes "you named a remote that does not exist" from "the network is down".
	 */
	private static boolean looksLikeUnknownRemote(Throwable e)
	{
		return e instanceof TransportException && messageContains(e, "not found");
	}

	/** Best effort: a leftover directory is untidy, but failing here would mask the real error. */
	private static void deleteQuietly(Path path)
	{
		try
		{
			org.eclipse.jgit.util.FileUtils.delete(path.toFile(),
					org.eclipse.jgit.util.FileUtils.RECURSIVE | org.eclipse.jgit.util.FileUtils.RETRY | org.eclipse.jgit.util.FileUtils.SKIP_MISSING);
		}
		catch(IOException | RuntimeException ignored)
		{
			// Nothing sensible to do; the caller is already reporting a failure.
		}
	}

	private static boolean hasCause(Throwable e, Class<? extends Throwable> type)
	{
		for(Throwable cause = e; cause != null; cause = cause.getCause())
		{
			if(type.isInstance(cause))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * JGit has no typed "auth failed", so the message is the only signal available.
	 *
	 * <p>Deliberately broad: treating a network hiccup as an auth prompt is a small
	 * annoyance, while missing a real auth failure leaves the user staring at a generic error with no way forward.</p>
	 */
	private static boolean looksLikeAuthFailure(Throwable e)
	{
		return messageContains(e, "authentication", "not authorized", "unauthorized", "credentials", "auth fail", "permission denied");
	}

	/** Case-insensitive search over the whole cause chain. */
	private static boolean messageContains(Throwable e, String... needles)
	{
		for(Throwable cause = e; cause != null; cause = cause.getCause())
		{
			String message = cause.getMessage();
			if(message == null)
			{
				continue;
			}
			String lower = message.toLowerCase(Locale.ROOT);
			for(String needle : needles)
			{
				if(lower.contains(needle))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static String hostOf(String target)
	{
		try
		{
			String host = new URI(target).getHost();
			return host != null ? host : target;
		}
		catch(URISyntaxException e)
		{
			// scp-style (git@host:path) and local paths are not URIs; the raw string is
			// still the most useful thing to show.
			return target;
		}
	}

	private static String protocolOf(String target)
	{
		try
		{
			String scheme = new URI(target).getScheme();
			return scheme != null ? scheme : "ssh";
		}
		catch(URISyntaxException e)
		{
			return "ssh";
		}
	}

	private static boolean hasText(String value)
	{
		return value != null && !value.isBlank();
	}
}
