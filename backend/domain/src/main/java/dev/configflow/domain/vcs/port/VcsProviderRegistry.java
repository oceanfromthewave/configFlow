package dev.configflow.domain.vcs.port;

import dev.configflow.domain.vcs.model.VcsType;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Registry of all installed {@link VcsProvider}s.
 *
 * <p>Adding a new VCS means implementing {@link VcsProvider} in a new infrastructure
 * module and registering it here; no other layer changes.</p>
 */
public interface VcsProviderRegistry {

    /** Registers a provider (called once during assembly). */
    void register(VcsProvider provider);

    /** Returns the provider for the given VCS type, if installed. */
    Optional<VcsProvider> byType(VcsType type);

    /** Auto-detects which provider owns the working copy at {@code localPath}. */
    Optional<VcsProvider> detect(Path localPath);

    /** All registered providers. */
    List<VcsProvider> all();
}
