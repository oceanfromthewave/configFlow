package dev.configflow.domain.vcs.model;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Filter + cursor for history and graph queries. All filter fields are optional
 * ({@code null} = no filter).
 *
 * @param cursor          opaque cursor from a previous {@link Page}, {@code null} for the first page
 * @param limit           maximum number of items to return (must be positive)
 * @param branch          restrict to a branch/ref
 * @param author          filter by author name substring
 * @param messageContains filter by message substring
 * @param path            restrict to revisions touching this path
 * @param from            lower bound on revision timestamp (inclusive)
 * @param to              upper bound on revision timestamp (inclusive)
 */
public record HistoryQuery(
        String cursor,
        int limit,
        String branch,
        String author,
        String messageContains,
        Path path,
        Instant from,
        Instant to) {

    public HistoryQuery {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    /** First page with the given size and no filters. */
    public static HistoryQuery firstPage(int limit) {
        return new HistoryQuery(null, limit, null, null, null, null, null, null);
    }
}
