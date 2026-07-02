package dev.configflow.domain.vcs.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Unified model of a Git commit and an SVN revision (ubiquitous language).
 *
 * @param id        VCS-native identifier
 * @param parents   parent revisions (SVN always has 0..1, Git merges have 2+)
 * @param author    who authored the change
 * @param timestamp author/commit time (UTC)
 * @param message   full commit/revision message
 * @param labels    ref decorations pointing at this revision
 */
public record Revision(
        RevisionId id,
        List<RevisionId> parents,
        Author author,
        Instant timestamp,
        String message,
        List<RefLabel> labels) {

    public Revision {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(author, "author must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(message, "message must not be null");
        parents = List.copyOf(parents == null ? List.of() : parents);
        labels = List.copyOf(labels == null ? List.of() : labels);
    }
}
