package dev.configflow.config;

import dev.configflow.application.branch.BranchService;
import dev.configflow.application.operation.OperationQueue;
import dev.configflow.application.vcs.VcsAccess;
import dev.configflow.domain.operation.OperationEvents;
import dev.configflow.domain.operation.OperationHistoryStore;
import dev.configflow.domain.repository.RepositoryStore;
import dev.configflow.domain.vcs.port.VcsProviderRegistry;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles the operation queue and the use cases that run on it.
 */
@Configuration
public class OperationConfig {

    /**
     * Worker pool behind the queue.
     *
     * <p>Cached rather than fixed: work is already serialised per repository, so the pool
     * only ever needs one thread per <em>active</em> repository, and a fixed size would
     * cap how many repositories can work at once. Daemon threads so a stuck operation
     * cannot keep the JVM alive after the desktop app closes.</p>
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService operationExecutor() {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "cf-operation-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newCachedThreadPool(factory);
    }

    @Bean
    public OperationQueue operationQueue(
            OperationHistoryStore history,
            OperationEvents events,
            Clock clock,
            ExecutorService operationExecutor) {
        return new OperationQueue(history, events, clock, operationExecutor);
    }

    @Bean
    public VcsAccess vcsAccess(RepositoryStore repositoryStore, VcsProviderRegistry providers) {
        return new VcsAccess(repositoryStore, providers);
    }

    @Bean
    public BranchService branchService(
            VcsAccess vcsAccess, OperationQueue operationQueue, OperationEvents events) {
        return new BranchService(vcsAccess, operationQueue, events);
    }
}
