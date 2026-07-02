package dev.configflow.infrastructure.svn;

import dev.configflow.domain.vcs.capability.VcsCapability;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.port.VcsProvider;
import java.nio.file.Path;
import java.util.Set;
import org.tmatesoft.svn.core.wc.SVNWCUtil;

/**
 * SVNKit-based Subversion provider.
 *
 * <p>M0 scaffolding: declares the SVN capability set (note: no {@code STAGING} or
 * {@code STASH}) and implements {@link #detect(Path)}; the operation ports are
 * implemented in M1.</p>
 */
public final class SvnVcsProvider implements VcsProvider {

    private static final Set<VcsCapability> CAPABILITIES = Set.of(
            VcsCapability.MERGE,
            VcsCapability.LOCK,
            VcsCapability.REMOTE_BROWSE);

    @Override
    public VcsType type() {
        return VcsType.SVN;
    }

    @Override
    public Set<VcsCapability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public boolean detect(Path localPath) {
        return localPath != null && SVNWCUtil.isVersionedDirectory(localPath.toFile());
    }

    @Override
    public RepositoryHandle open(Path localPath) {
        if (!detect(localPath)) {
            throw new IllegalArgumentException("Not an SVN working copy: " + localPath);
        }
        return new RepositoryHandle(localPath, VcsType.SVN);
    }
}
