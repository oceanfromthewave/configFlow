package dev.configflow.domain.vcs.model;

/**
 * Kind of version control system backing a repository.
 *
 * <p>New systems (e.g. Mercurial) are added here together with a new
 * {@code VcsProvider} implementation module.</p>
 */
public enum VcsType {
    GIT,
    SVN
}
