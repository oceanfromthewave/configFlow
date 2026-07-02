package dev.configflow.domain.vcs.model;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Lightweight handle to an opened working copy, passed to every VCS operation.
 *
 * <p>Providers may key internal caches (e.g. an open JGit {@code Repository}) by the
 * local path; the handle itself carries no provider-specific state so it stays a pure
 * domain value.</p>
 *
 * @param localPath absolute path of the working copy root
 * @param vcsType   VCS backing the working copy
 */
public record RepositoryHandle(Path localPath, VcsType vcsType) {

    public RepositoryHandle {
        Objects.requireNonNull(localPath, "localPath must not be null");
        Objects.requireNonNull(vcsType, "vcsType must not be null");
    }
}
