package dev.configflow.application.remote;

import dev.configflow.application.operation.OperationContext;
import dev.configflow.application.operation.OperationQueue;
import dev.configflow.application.repository.RepositoryService;
import dev.configflow.domain.operation.ConsoleLevel;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationEvents;
import dev.configflow.domain.operation.OperationProgress;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.Repository;
import dev.configflow.domain.vcs.model.CloneRequest;
import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.port.OperationMonitor;
import dev.configflow.domain.vcs.port.RepositoryOperations;
import dev.configflow.domain.vcs.port.VcsProvider;
import dev.configflow.domain.vcs.port.VcsProviderRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Use case: cloning a repository into the workspace.
 *
 * <p>Apart from every other queued operation in one way: there is no repository yet, so
 * nothing to serialise against and no id to report against until it finishes. The clone
 * runs, and only then does the result get registered.</p>
 */
public final class CloneService {

    private final VcsProviderRegistry providers;
    private final RepositoryService repositories;
    private final OperationQueue queue;
    private final OperationEvents events;

    public CloneService(
            VcsProviderRegistry providers,
            RepositoryService repositories,
            OperationQueue queue,
            OperationEvents events) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.repositories = Objects.requireNonNull(repositories, "repositories");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * Clones {@code url} into {@code localPath} and registers the result.
     *
     * @param vcsType which engine to use; the client says so because a URL alone does not
     *                reliably tell Git from SVN
     * @throws IllegalArgumentException      if the URL is blank or the target is unusable
     * @throws UnsupportedOperationException if that VCS cannot clone
     */
    public Operation clone(String url, Path localPath, VcsType vcsType, String credentialId) {
        String remoteUrl = requireText(url, "url");
        Path target = requireTarget(localPath);
        RepositoryOperations engine = engineFor(
                vcsType == null ? VcsType.GIT : vcsType);
        CloneRequest request = new CloneRequest(remoteUrl, target, credentialId);

        // No repository id: this operation has nothing to queue behind, which is why the
        // queue gives repository-less work its own chain.
        return queue.submit(null, OperationType.CLONE, context -> {
            context.log("clone " + remoteUrl + " " + target, ConsoleLevel.CMD);
            engine.cloneRepository(request, monitorFor(context));

            context.progress(OperationProgress.indeterminate("Registering"));
            Repository registered = repositories.register(target);
            // The clone finished long after the request that started it returned, so the
            // Welcome list has no other way to learn about this.
            events.repositoryRegistered(registered.id());
        });
    }

    /**
     * The target must not already hold anything.
     *
     * <p>Checked here rather than left to the engine because a clone into a non-empty
     * directory fails halfway, and the cleanup then cannot tell what it created from what
     * was already there.</p>
     */
    private static Path requireTarget(Path localPath) {
        if (localPath == null) {
            throw new IllegalArgumentException("A 'localPath' is required");
        }
        Path target = localPath.toAbsolutePath().normalize();
        if (Files.exists(target)) {
            try (var entries = Files.list(target)) {
                if (entries.findAny().isPresent()) {
                    throw new IllegalArgumentException(
                            "The target directory is not empty: " + target);
                }
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("Cannot read the target directory: " + target, e);
            }
        }
        return target;
    }

    private RepositoryOperations engineFor(VcsType vcsType) {
        VcsProvider provider = providers.byType(vcsType).orElseThrow(() ->
                new NoSuchElementException("No provider for " + vcsType));
        if (!(provider instanceof RepositoryOperations engine)) {
            throw new UnsupportedOperationException(vcsType + " cannot clone");
        }
        return engine;
    }

    private static OperationMonitor monitorFor(OperationContext context) {
        return new OperationMonitor() {
            @Override
            public void onProgress(OperationProgress progress) {
                context.progress(progress);
            }

            @Override
            public boolean isCancelled() {
                return context.isCancelled();
            }
        };
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("'" + field + "' must not be blank");
        }
        return value.trim();
    }
}
