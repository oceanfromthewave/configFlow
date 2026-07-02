package dev.configflow.domain.vcs.port;

import dev.configflow.domain.vcs.capability.VcsCapability;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.VcsType;
import java.nio.file.Path;
import java.util.Set;

/**
 * Entry-point port of a VCS plugin (Git, SVN, ...).
 *
 * <p>A provider declares its {@link VcsCapability capabilities} and implements only
 * the role-specific operation interfaces it supports ({@link WorkingTreeOperations},
 * {@link StashOperations}, ...). Callers discover optional roles via
 * {@code instanceof} after checking the capability set.</p>
 */
public interface VcsProvider {

    /** Which VCS this provider implements. */
    VcsType type();

    /** Features this provider supports; drives conditional UI and API validation. */
    Set<VcsCapability> capabilities();

    /** True when {@code localPath} contains a working copy of this VCS (.git / .svn). */
    boolean detect(Path localPath);

    /** Opens a working copy and returns a handle used by all operation ports. */
    RepositoryHandle open(Path localPath);
}
