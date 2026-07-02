package dev.configflow.domain.repository;

import dev.configflow.domain.vcs.model.VcsType;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Aggregate root representing a repository registered in ConfigFlow.
 *
 * <p>Only app metadata lives here (name, favorite flag, group, last-opened time).
 * VCS data itself (commits, branches) is never persisted; it is always read live
 * from the working copy through a {@code VcsProvider}.</p>
 *
 * @param id            application-level identity (UUID)
 * @param name          display name shown in the sidebar
 * @param localPath     absolute path of the working copy on disk (unique)
 * @param remoteUrl     origin URL if known, may be {@code null}
 * @param vcsType       which VCS backs this repository
 * @param groupName     favorite group name, may be {@code null}
 * @param favorite      whether the repository is pinned as a favorite
 * @param createdAt     registration time (UTC)
 * @param lastOpenedAt  last time the user opened it, may be {@code null}
 */
public record Repository(
        RepositoryId id,
        String name,
        Path localPath,
        String remoteUrl,
        VcsType vcsType,
        String groupName,
        boolean favorite,
        Instant createdAt,
        Instant lastOpenedAt) {

    public Repository {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(localPath, "localPath must not be null");
        Objects.requireNonNull(vcsType, "vcsType must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    /** Registers a new repository with defaults (not favorite, no group). */
    public static Repository register(
            String name, Path localPath, String remoteUrl, VcsType vcsType, Instant now) {
        return new Repository(
                RepositoryId.newId(), name, localPath, remoteUrl, vcsType, null, false, now, null);
    }

    /** Returns a copy renamed to {@code newName}. */
    public Repository withName(String newName) {
        return new Repository(
                id, newName, localPath, remoteUrl, vcsType, groupName, favorite, createdAt, lastOpenedAt);
    }

    /** Returns a copy with the favorite flag changed. */
    public Repository withFavorite(boolean newFavorite) {
        return new Repository(
                id, name, localPath, remoteUrl, vcsType, groupName, newFavorite, createdAt, lastOpenedAt);
    }

    /** Returns a copy moved to the given favorite group ({@code null} clears it). */
    public Repository withGroupName(String newGroupName) {
        return new Repository(
                id, name, localPath, remoteUrl, vcsType, newGroupName, favorite, createdAt, lastOpenedAt);
    }

    /** Returns a copy whose last-opened time is updated to {@code openedAt}. */
    public Repository opened(Instant openedAt) {
        return new Repository(
                id, name, localPath, remoteUrl, vcsType, groupName, favorite, createdAt, openedAt);
    }
}
