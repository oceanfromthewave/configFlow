package dev.configflow.domain.vcs.model;

/**
 * Request to fetch from a remote.
 *
 * @param remote remote name, {@code null} for the default remote
 * @param prune  remove remote-tracking refs deleted upstream
 */
public record FetchRequest(String remote, boolean prune) {
}
