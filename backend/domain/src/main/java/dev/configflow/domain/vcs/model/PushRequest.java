package dev.configflow.domain.vcs.model;

/**
 * Request to push to a remote.
 *
 * @param remote         remote name, {@code null} for the default remote
 * @param forceWithLease force-push guarded by the remote-tracking ref state
 * @param pushTags       also push tags
 */
public record PushRequest(String remote, boolean forceWithLease, boolean pushTags) {
}
