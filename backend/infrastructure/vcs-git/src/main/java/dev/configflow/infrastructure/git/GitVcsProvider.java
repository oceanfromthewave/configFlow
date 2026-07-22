package dev.configflow.infrastructure.git;

import dev.configflow.domain.vcs.capability.VcsCapability;
import dev.configflow.domain.vcs.model.CommitRequest;
import dev.configflow.domain.vcs.model.HistoryQuery;
import dev.configflow.domain.vcs.model.IgnorePattern;
import dev.configflow.domain.vcs.model.Page;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.Revision;
import dev.configflow.domain.vcs.model.RevisionId;
import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.model.WorkingTreeStatus;
import dev.configflow.domain.vcs.port.CommitOperations;
import dev.configflow.domain.vcs.port.VcsProvider;
import dev.configflow.domain.vcs.port.WorkingTreeOperations;

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
public final class GitVcsProvider implements VcsProvider, WorkingTreeOperations, CommitOperations
{

	private static final Set<VcsCapability> CAPABILITIES = Set.of(VcsCapability.STAGING, VcsCapability.STASH, VcsCapability.REBASE, VcsCapability.TAG,
			VcsCapability.CHERRY_PICK, VcsCapability.AMEND, VcsCapability.MERGE, VcsCapability.HISTORY_GRAPH);

	private final GitWorkingTree workingTree;
	private final GitCommits commits;

	public GitVcsProvider()
	{
		this(new GitRepositoryAccess());
	}

	private GitVcsProvider(GitRepositoryAccess access)
	{
		this(new GitWorkingTree(access), new GitCommits(access));
	}

	GitVcsProvider(GitWorkingTree workingTree, GitCommits commits)
	{
		this.workingTree = workingTree;
		this.commits = commits;
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
}