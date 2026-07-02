package dev.configflow.domain.vcs.model;

import java.util.Objects;

/**
 * One node of the SVN repository browser tree.
 *
 * @param name        entry name (file or directory)
 * @param directory   true when the entry is a directory
 * @param size        file size in bytes (0 for directories)
 * @param lastChanged revision that last changed this entry, may be {@code null}
 */
public record RemoteEntry(String name, boolean directory, long size, RevisionId lastChanged) {

    public RemoteEntry {
        Objects.requireNonNull(name, "name must not be null");
    }
}
