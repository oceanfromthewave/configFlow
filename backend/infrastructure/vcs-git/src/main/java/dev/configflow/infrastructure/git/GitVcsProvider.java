package dev.configflow.infrastructure.git;

import dev.configflow.domain.vcs.capability.VcsCapability;
import dev.configflow.domain.vcs.model.IgnorePattern;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.model.WorkingTreeStatus;
import dev.configflow.domain.vcs.port.VcsProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import dev.configflow.domain.vcs.port.WorkingTreeOperations;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.util.FS;

/**
 * JGit-based Git provider.
 *
 * <p>M0 scaffolding: declares the Git capability set and implements
 * {@link #detect(Path)}; the operation ports (working tree, commits, ...) are implemented in M1.</p>
 */
public final class GitVcsProvider implements VcsProvider, WorkingTreeOperations
{

	private static final Set<VcsCapability> CAPABILITIES = Set.of(VcsCapability.STAGING, VcsCapability.STASH, VcsCapability.REBASE, VcsCapability.TAG,
			VcsCapability.CHERRY_PICK, VcsCapability.AMEND, VcsCapability.MERGE, VcsCapability.HISTORY_GRAPH);

	private final GitWorkingTree workingTree;

	public GitVcsProvider()
	{
		this(new GitWorkingTree(new GitRepositoryAccess()));
	}

	GitVcsProvider(GitWorkingTree workingTree)
	{
		this.workingTree = workingTree;
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
	public WorkingTreeStatus status(RepositoryHandle repo) {
		return workingTree.status(repo);
	}

	@Override
	public void stage(RepositoryHandle repo, List<Path> paths) {
		workingTree.stage(repo, paths);
	}

	@Override
	public void unstage(RepositoryHandle repo, List<Path> paths) {
		workingTree.unstage(repo, paths);
	}

	@Override
	public void discard(RepositoryHandle repo, List<Path> paths) {
		workingTree.discard(repo, paths);
	}

	@Override
	public void ignore(RepositoryHandle repo, IgnorePattern pattern) {
		workingTree.ignore(repo, pattern);
	}
}
