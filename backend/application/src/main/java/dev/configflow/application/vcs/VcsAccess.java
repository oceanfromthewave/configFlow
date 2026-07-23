package dev.configflow.application.vcs;

import dev.configflow.domain.repository.Repository;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.repository.RepositoryStore;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.port.VcsProvider;
import dev.configflow.domain.vcs.port.VcsProviderRegistry;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Turns a registered repository id into a live engine handle plus one operation port.
 *
 * <p>Every use case needs the same three steps — look the repository up, find the
 * provider for its VCS, check it actually implements the port being asked for — and
 * getting the order wrong changes which error the caller sees. Keeping it in one place
 * means that decision is made once.</p>
 */
public final class VcsAccess {

    private final RepositoryStore repositoryStore;
    private final VcsProviderRegistry providers;

    public VcsAccess(RepositoryStore repositoryStore, VcsProviderRegistry providers) {
        this.repositoryStore = Objects.requireNonNull(repositoryStore, "repositoryStore");
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    /**
     * Opens the repository and narrows its provider to {@code port}.
     *
     * @throws NoSuchElementException        if no repository has the given id
     * @throws UnsupportedOperationException if its provider does not implement {@code port}
     */
    public <T> Opened<T> open(RepositoryId id, Class<T> port) {
        Repository repository = require(id);
        VcsProvider provider = providers.byType(repository.vcsType()).orElseThrow(() ->
                new IllegalStateException("No provider registered for " + repository.vcsType()));
        if (!port.isInstance(provider)) {
            throw new UnsupportedOperationException(
                    repository.vcsType() + " has no " + port.getSimpleName());
        }
        return new Opened<>(provider, provider.open(repository.localPath()), port.cast(provider));
    }

    /** The stored repository, without touching the engine. */
    public Repository require(RepositoryId id) {
        return repositoryStore.findById(id).orElseThrow(() ->
                new NoSuchElementException("Repository not found: " + id.asString()));
    }

    /** A repository resolved down to one live operation port. */
    public record Opened<T>(VcsProvider provider, RepositoryHandle handle, T operations) {
    }
}
