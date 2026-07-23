package dev.configflow.infrastructure.git;

import dev.configflow.domain.vcs.capability.VcsCapability;
import dev.configflow.domain.vcs.model.*;
import dev.configflow.domain.vcs.port.BranchOperations;
import dev.configflow.domain.vcs.port.OperationMonitor;
import dev.configflow.domain.vcs.port.RemoteSyncOperations;
import dev.configflow.domain.vcs.port.RepositoryOperations;
import dev.configflow.domain.vcs.port.CommitOperations;
import dev.configflow.domain.vcs.port.DiffOperations;
import dev.configflow.domain.vcs.port.VcsProvider;
import dev.configflow.domain.vcs.port.WorkingTreeOperations;
import dev.configflow.domain.vcs.port.RefBrowseOperations;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.util.FS;

/**
 * JGit-based Git provider.
 *
 * <p>Implements the operation ports Git actually supports; callers discover them by
 * checking {@link #capabilities()} and narrowing with {@code instanceof}.</p>
 */
public final class GitVcsProvider implements VcsProvider, WorkingTreeOperations, CommitOperations, DiffOperations, RefBrowseOperations,
		BranchOperations, RemoteSyncOperations, RepositoryOperations
{

	private static final Set<VcsCapability> CAPABILITIES = Set.of(VcsCapability.STAGING, VcsCapability.STASH, VcsCapability.REBASE, VcsCapability.TAG,
			VcsCapability.CHERRY_PICK, VcsCapability.AMEND, VcsCapability.MERGE, VcsCapability.HISTORY_GRAPH);

	private final GitWorkingTree workingTree;
	private final GitCommits commits;
	private final GitDiff diffs;

	private final GitRefs refs;
	private final GitBranches branches;
	private final GitRemotes remotes;

	public GitVcsProvider()
	{
		this(new GitRepositoryAccess());
	}

	private GitVcsProvider(GitRepositoryAccess access)
	{
		this(new GitWorkingTree(access), new GitCommits(access), new GitDiff(access), new GitRefs(access), new GitBranches(access), new GitRemotes(access));
	}

	GitVcsProvider(GitWorkingTree workingTree, GitCommits commits, GitDiff diffs, GitRefs refs, GitBranches branches, GitRemotes remotes)
	{
		this.workingTree = workingTree;
		this.commits = commits;
		this.diffs = diffs;
		this.refs = refs;
		this.branches = branches;
		this.remotes = remotes;
	}

	@Override
	public VcsType type()
	{
		return VcsType.GIT;
	}

	@Override
	public Set<VcsCapability> capabilities()
	{
		return CAPABILITIES;
	}

	@Override
	public boolean detect(Path localPath)
	{
		if(localPath == null)
		{
			return false;
		}
		Path gitDir = localPath.resolve(".git");
		return RepositoryCache.FileKey.isGitRepository(gitDir.toFile(), FS.DETECTED);
	}

	@Override
	public RepositoryHandle open(Path localPath)
	{
		if(!detect(localPath))
		{
			throw new IllegalArgumentException("Not a Git working copy: " + localPath);
		}
		return new RepositoryHandle(localPath, VcsType.GIT);
	}

	@Override
	public WorkingTreeStatus status(RepositoryHandle repo)
	{
		return workingTree.status(repo);
	}

	@Override
	public void stage(RepositoryHandle repo, List<Path> paths)
	{
		workingTree.stage(repo, paths);
	}

	@Override
	public void unstage(RepositoryHandle repo, List<Path> paths)
	{
		workingTree.unstage(repo, paths);
	}

	@Override
	public void discard(RepositoryHandle repo, List<Path> paths)
	{
		workingTree.discard(repo, paths);
	}

	@Override
	public void ignore(RepositoryHandle repo, IgnorePattern pattern)
	{
		workingTree.ignore(repo, pattern);
	}

	@Override
	public RevisionId commit(RepositoryHandle repo, CommitRequest request)
	{
		return commits.commit(repo, request);
	}

	@Override
	public Page<Revision> history(RepositoryHandle repo, HistoryQuery query)
	{
		return commits.history(repo, query);
	}

	@Override
	public Revision show(RepositoryHandle repo, RevisionId id)
	{
		return commits.show(repo, id);
	}

	@Override
	public FileDiff diffWorking(RepositoryHandle repo, Path path, boolean staged)
	{
		return diffs.diffWorking(repo, path, staged);
	}

	@Override
	public FileDiff diffRevisions(RepositoryHandle repo, RevisionId from, RevisionId to, Path path)
	{
		return diffs.diffRevisions(repo, from, to, path);
	}

	@Override
	public String contentAt(RepositoryHandle repo, RevisionId revision, Path path)
	{
		return diffs.contentAt(repo, revision, path);
	}

	@Override
	public List<RefLabel> listRefs(RepositoryHandle repo)
	{
		return refs.listRefs(repo);
	}

	@Override
	public List<Revision> compare(RepositoryHandle repo, String base, String target)
	{
		return refs.compare(repo,base,target);
	}

	@Override
	public void checkout(RepositoryHandle repo, String ref)
	{
		branches.checkout(repo, ref);
	}

	@Override
	public void createBranch(RepositoryHandle repo, String name, String startPoint, boolean checkout)
	{
		branches.createBranch(repo, name, startPoint, checkout);
	}

	@Override
	public void deleteBranch(RepositoryHandle repo, String name, boolean remote, boolean force)
	{
		branches.deleteBranch(repo, name, remote, force);
	}

	@Override
	public void merge(RepositoryHandle repo, MergeRequest request)
	{
		branches.merge(repo, request);
	}

	@Override
	public void fetch(RepositoryHandle repo, FetchRequest request, OperationMonitor monitor)
	{
		remotes.fetch(repo, request, monitor);
	}

	@Override
	public void pull(RepositoryHandle repo, PullRequest request, OperationMonitor monitor)
	{
		remotes.pull(repo, request, monitor);
	}

	@Override
	public void push(RepositoryHandle repo, PushRequest request, OperationMonitor monitor)
	{
		remotes.push(repo, request, monitor);
	}

	/** SVN-only; Git has no equivalent of a working-copy update against a revision. */
	@Override
	public void update(RepositoryHandle repo, Long revision, OperationMonitor monitor)
	{
		throw new UnsupportedOperationException("Git has no SVN-style update; use pull");
	}

	/** SVN-only; a Git working copy has no lock database to clean up. */
	@Override
	public void cleanup(RepositoryHandle repo)
	{
		throw new UnsupportedOperationException("Git has no SVN-style cleanup");
	}

	@Override
	public RepositoryHandle cloneRepository(CloneRequest request, OperationMonitor monitor)
	{
		return remotes.cloneRepository(request, monitor);
	}

	@Override
	public RepositoryHandle init(Path path)
	{
		return remotes.init(path);
	}
}