package dev.configflow.domain.vcs.model;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Request to clone (Git) or checkout (SVN) a remote repository.
 *
 * @param url          remote URL
 * @param localPath    target directory on disk
 * @param credentialId reference to a stored credential, {@code null} for anonymous access
 */
public record CloneRequest(String url, Path localPath, String credentialId) {

    public CloneRequest {
        Objects.requireNonNull(url, "url must not be null");
        Objects.requireNonNull(localPath, "localPath must not be null");
    }
}
