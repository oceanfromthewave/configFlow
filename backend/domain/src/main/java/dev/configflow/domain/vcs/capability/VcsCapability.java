package dev.configflow.domain.vcs.capability;

/**
 * Optional features a {@code VcsProvider} may support.
 *
 * <p>The application layer and the UI never branch on the VCS type; they query the
 * provider's capability set instead ("SVN has no stash" becomes
 * {@code capabilities().contains(STASH)} rather than {@code if (svn)}).</p>
 */
public enum VcsCapability {
    /** Explicit staging area (Git index). */
    STAGING,
    /** Stash / shelve local changes. */
    STASH,
    /** Rebase (start/continue/abort/skip). */
    REBASE,
    /** First-class tags. */
    TAG,
    /** Cherry-pick individual revisions. */
    CHERRY_PICK,
    /** Amend the previous commit. */
    AMEND,
    /** Merge between refs. */
    MERGE,
    /** Path locking (SVN lock/unlock/break-lock). */
    LOCK,
    /** Browse the remote repository tree without a working copy (SVN). */
    REMOTE_BROWSE,
    /** Multi-parent history usable for graph rendering. */
    HISTORY_GRAPH,
    /** Move HEAD (and optionally index/working tree) to a target commit. */
    RESET
}
