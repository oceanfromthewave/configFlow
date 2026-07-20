package dev.configflow.infrastructure.git;

import java.nio.file.Path;

/**
 * Conversions between OS paths and the paths Git expects.
 *
 * <p>Domain models carry {@link Path}, which renders with backslashes on Windows, while
 * Git always addresses content with forward slashes. Every value handed to JGit as a
 * path pattern must go through here, otherwise path matching silently fails on Windows.</p>
 */
final class GitPaths {

    private GitPaths() {
    }

    /** Converts an OS path (possibly with backslashes on Windows) to a Git path ('/'). */
    static String toGitPath(Path path) {
        return path.toString().replace('\\', '/');
    }
}
