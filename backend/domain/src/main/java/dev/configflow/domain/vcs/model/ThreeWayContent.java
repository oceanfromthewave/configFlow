package dev.configflow.domain.vcs.model;

/**
 * The three sides of a conflicted file for the merge editor.
 *
 * @param base   common ancestor content, {@code null} when unavailable
 * @param mine   local ("ours") content
 * @param theirs incoming ("theirs") content
 */
public record ThreeWayContent(String base, String mine, String theirs) {
}
