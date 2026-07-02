package dev.configflow.domain.vcs.model;

/**
 * Classification of a single file change in the working tree or a revision.
 */
public enum ChangeType {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED,
    COPIED,
    CONFLICTED,
    UNTRACKED,
    IGNORED,
    /** SVN: file is locked by another user (needs-lock workflow). */
    LOCKED_BY_OTHER
}
