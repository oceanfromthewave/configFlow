package dev.configflow.application.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.operation.ConsoleLevel;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationEvents;
import dev.configflow.domain.operation.OperationHistoryStore;
import dev.configflow.domain.operation.OperationId;
import dev.configflow.domain.operation.OperationProgress;
import dev.configflow.domain.operation.OperationState;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OperationQueueTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final RepositoryId REPO_A = RepositoryId.newId();
    private static final RepositoryId REPO_B = RepositoryId.newId();

    private final RecordingEvents events = new RecordingEvents();
    private final InMemoryHistory history = new InMemoryHistory();

    /** Runs tasks inline, so submit() returns only once the work is done. */
    private OperationQueue inlineQueue() {
        return new OperationQueue(history, events, fixedClock(), Runnable::run);
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    // --- happy path ------------------------------------------------------

    @Test
    void submit_answersImmediatelyWithAQueuedOperation() {
        // A queue that never runs anything, so the returned snapshot cannot have advanced.
        OperationQueue queue = new OperationQueue(history, events, fixedClock(), task -> {
        });

        Operation accepted = queue.submit(REPO_A, OperationType.CHECKOUT, context -> {
        });

        assertEquals(OperationState.QUEUED, accepted.state());
        assertEquals(OperationType.CHECKOUT, accepted.type());
        assertEquals(REPO_A, accepted.repositoryId());
        assertNull(accepted.startedAt());
    }

    @Test
    void successfulTaskIsRecordedAndArchived() {
        OperationQueue queue = inlineQueue();

        Operation accepted = queue.submit(REPO_A, OperationType.CHECKOUT, context -> {
        });

        Operation finished = queue.find(accepted.id()).orElseThrow();
        assertEquals(OperationState.SUCCEEDED, finished.state());
        assertEquals(NOW, finished.startedAt());
        assertEquals(NOW, finished.finishedAt());
        assertNull(finished.errorMessage());
        assertTrue(history.saved.containsKey(accepted.id()), "terminal state must be archived");
    }

    @Test
    void progressAndConsoleLinesReachTheEventsPort() {
        OperationQueue queue = inlineQueue();

        Operation accepted = queue.submit(REPO_A, OperationType.CHECKOUT, context -> {
            context.log("checkout main", ConsoleLevel.CMD);
            context.progress(new OperationProgress(50, "Switching", "half way"));
        });

        // The queue announces the start itself, then the task's own report follows.
        assertEquals(2, events.progress.size());
        assertEquals(OperationType.CHECKOUT.name(), events.progress.get(0).phase());
        assertEquals(50, events.progress.get(1).percent());
        assertEquals(List.of("cmd:checkout main"), events.consoleLines);
        assertEquals(List.of(accepted.id()), events.completed.stream().map(Operation::id).toList());
        assertEquals(List.of("checkout main"), queue.find(accepted.id()).orElseThrow().logLines());
    }

    // --- failures --------------------------------------------------------

    @Test
    void failingTaskIsRecordedAsFailedWithItsMessage() {
        OperationQueue queue = inlineQueue();

        Operation accepted = queue.submit(REPO_A, OperationType.CHECKOUT, context -> {
            throw new IllegalStateException("index.lock exists");
        });

        Operation finished = queue.find(accepted.id()).orElseThrow();
        assertEquals(OperationState.FAILED, finished.state());
        assertEquals("index.lock exists", finished.errorMessage());
        assertEquals(OperationState.FAILED, events.completed.get(0).state());
    }

    @Test
    void failureMessageFallsBackToTheExceptionType() {
        OperationQueue queue = inlineQueue();

        Operation accepted = queue.submit(REPO_A, OperationType.CHECKOUT, context -> {
            throw new IllegalStateException();
        });

        assertEquals("IllegalStateException",
                queue.find(accepted.id()).orElseThrow().errorMessage());
    }

    @Test
    void anErrorIsRecordedRatherThanLeavingTheOperationRunningForever() {
        OperationQueue queue = inlineQueue();

        // Catching only Exception would leave this RUNNING for the rest of the session,
        // with anything watching it waiting for a completion that never arrives.
        Operation accepted = queue.submit(REPO_A, OperationType.CHECKOUT, context -> {
            throw new StackOverflowError("too deep");
        });

        Operation finished = queue.find(accepted.id()).orElseThrow();
        assertEquals(OperationState.FAILED, finished.state());
        assertEquals("too deep", finished.errorMessage());
        assertEquals(1, events.completed.size(), "clients must be told it ended");
    }

    @Test
    void anErrorDoesNotStopLaterWorkOnTheSameRepository() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            OperationQueue queue = new OperationQueue(history, events, fixedClock(), pool);
            CountDownLatch second = new CountDownLatch(1);

            queue.submit(REPO_A, OperationType.CHECKOUT, context -> {
                throw new StackOverflowError("too deep");
            });
            queue.submit(REPO_A, OperationType.CHECKOUT, context -> second.countDown());

            assertTrue(second.await(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void oneFailureDoesNotStrandTheRestOfTheChain() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            OperationQueue queue = new OperationQueue(history, events, fixedClock(), pool);
            CountDownLatch second = new CountDownLatch(1);

            queue.submit(REPO_A, OperationType.CHECKOUT, context -> {
                throw new IllegalStateException("boom");
            });
            queue.submit(REPO_A, OperationType.CHECKOUT, context -> second.countDown());

            assertTrue(second.await(5, TimeUnit.SECONDS),
                    "work queued behind a failure must still run");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aBrokenHistoryStoreDoesNotFailTheOperation() {
        OperationHistoryStore broken = new InMemoryHistory() {
            @Override
            public void save(Operation operation) {
                throw new IllegalStateException("disk is full");
            }
        };
        OperationQueue queue = new OperationQueue(broken, events, fixedClock(), Runnable::run);

        Operation accepted = queue.submit(REPO_A, OperationType.CHECKOUT, context -> {
        });

        Operation finished = queue.find(accepted.id()).orElseThrow();
        assertEquals(OperationState.SUCCEEDED, finished.state(),
                "archiving is not part of the work being done");
        assertTrue(finished.logLines().stream().anyMatch(line -> line.contains("disk is full")),
                () -> "the archive failure should be visible: " + finished.logLines());
    }

    // --- serialisation ---------------------------------------------------

    @Test
    void operationsOnOneRepositoryNeverOverlap() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            OperationQueue queue = new OperationQueue(history, events, fixedClock(), pool);
            AtomicInteger concurrent = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(8);

            for (int i = 0; i < 8; i++) {
                queue.submit(REPO_A, OperationType.CHECKOUT, context -> {
                    peak.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                    Thread.sleep(15);
                    concurrent.decrementAndGet();
                    done.countDown();
                });
            }

            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(1, peak.get(), "a working copy tolerates exactly one writer");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void differentRepositoriesRunInParallel() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            OperationQueue queue = new OperationQueue(history, events, fixedClock(), pool);
            CountDownLatch bothStarted = new CountDownLatch(2);

            OperationTask waitForTheOther = context -> {
                bothStarted.countDown();
                // Deadlocks unless the other repository really is running concurrently.
                assertTrue(bothStarted.await(5, TimeUnit.SECONDS));
            };
            queue.submit(REPO_A, OperationType.CHECKOUT, waitForTheOther);
            queue.submit(REPO_B, OperationType.CHECKOUT, waitForTheOther);

            assertTrue(bothStarted.await(5, TimeUnit.SECONDS),
                    "separate repositories share nothing and must not queue behind each other");
        } finally {
            pool.shutdownNow();
        }
    }

    // --- cancellation ----------------------------------------------------

    @Test
    void cancellingAQueuedOperationStopsItBeforeItTouchesAnything() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            OperationQueue queue = new OperationQueue(history, events, fixedClock(), pool);
            CountDownLatch blocker = new CountDownLatch(1);
            AtomicInteger ran = new AtomicInteger();

            queue.submit(REPO_A, OperationType.CHECKOUT, context -> blocker.await());
            Operation queued = queue.submit(
                    REPO_A, OperationType.CHECKOUT, context -> ran.incrementAndGet());

            assertTrue(queue.cancel(queued.id()));
            blocker.countDown();

            waitForState(queue, queued.id(), OperationState.CANCELLED);
            assertEquals(0, ran.get(), "a cancelled task must never start");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aRunningTaskStopsWhereItChecks() {
        OperationQueue queue = inlineQueue();
        AtomicInteger reachedEnd = new AtomicInteger();

        // Cancel from inside, which is the only way to observe it with an inline executor.
        Operation accepted = queue.submit(REPO_A, OperationType.CHECKOUT, context -> {
            queue.cancel(context.operationId());
            context.throwIfCancelled();
            reachedEnd.incrementAndGet();
        });

        assertEquals(OperationState.CANCELLED, queue.find(accepted.id()).orElseThrow().state());
        assertEquals(0, reachedEnd.get());
    }

    @Test
    void aTaskThatNeverChecksRunsToCompletion() {
        OperationQueue queue = inlineQueue();

        Operation accepted = queue.submit(REPO_A, OperationType.CHECKOUT, context -> {
            queue.cancel(context.operationId());
            // Deliberately never asks, which the contract says means it finishes.
        });

        assertEquals(OperationState.SUCCEEDED, queue.find(accepted.id()).orElseThrow().state());
    }

    @Test
    void cancellingAFinishedOrUnknownOperationReportsFalse() {
        OperationQueue queue = inlineQueue();
        Operation accepted = queue.submit(REPO_A, OperationType.CHECKOUT, context -> {
        });

        assertFalse(queue.cancel(accepted.id()), "already finished");
        assertFalse(queue.cancel(OperationId.newId()), "never existed");
    }

    // --- shutdown --------------------------------------------------------

    @Test
    void shutdownLetsRunningWorkFinish() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            OperationQueue queue = new OperationQueue(history, events, fixedClock(), pool);
            CountDownLatch started = new CountDownLatch(1);

            Operation accepted = queue.submit(REPO_A, OperationType.CHECKOUT, context -> {
                started.countDown();
                Thread.sleep(50);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));

            queue.shutdown(Duration.ofSeconds(5));

            assertEquals(OperationState.SUCCEEDED,
                    queue.find(accepted.id()).orElseThrow().state());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shutdownCompletesWorkTheExecutorNeverSaw() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch blocker = new CountDownLatch(1);
        try {
            OperationQueue queue = new OperationQueue(history, events, fixedClock(), pool);
            CountDownLatch started = new CountDownLatch(1);

            queue.submit(REPO_A, OperationType.CHECKOUT, context -> {
                started.countDown();
                blocker.await();
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            // Chained behind the blocked one, so the executor has never seen this task.
            Operation waiting = queue.submit(REPO_A, OperationType.CHECKOUT, context -> {
            });
            assertEquals(OperationState.QUEUED, queue.find(waiting.id()).orElseThrow().state());

            queue.shutdown(Duration.ofMillis(200));

            // Left at QUEUED it would never emit a completion, and a client watching it
            // would wait for an event that can no longer arrive.
            Operation finished = queue.find(waiting.id()).orElseThrow();
            assertEquals(OperationState.CANCELLED, finished.state());
            assertTrue(events.completed.stream()
                    .anyMatch(operation -> operation.id().equals(waiting.id())));
        } finally {
            blocker.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void workReleasedAfterTheGracePeriodCannotOverwriteItsShutdownOutcome() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            OperationQueue queue = new OperationQueue(history, events, fixedClock(), pool);
            CountDownLatch blocker = new CountDownLatch(1);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch taskReturned = new CountDownLatch(1);

            Operation slow = queue.submit(REPO_A, OperationType.CHECKOUT, context -> {
                started.countDown();
                blocker.await();
                taskReturned.countDown();
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));

            // Grace expires while the task is still stuck, so shutdown records CANCELLED.
            queue.shutdown(Duration.ofMillis(100));
            assertEquals(OperationState.CANCELLED, queue.find(slow.id()).orElseThrow().state());

            // Now let it finish. Its own terminate() must not rewrite the outcome or
            // announce a second completion for the same operation.
            blocker.countDown();
            assertTrue(taskReturned.await(5, TimeUnit.SECONDS));
            Thread.sleep(100);

            assertEquals(OperationState.CANCELLED, queue.find(slow.id()).orElseThrow().state());
            assertEquals(1, events.completed.stream()
                    .filter(operation -> operation.id().equals(slow.id())).count());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void repositorylessWorkDoesNotQueueBehindUnrelatedRepositorylessWork() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            OperationQueue queue = new OperationQueue(history, events, fixedClock(), pool);
            CountDownLatch bothStarted = new CountDownLatch(2);

            // Two clones into different directories share nothing, so a single chain for
            // "no repository" would make the second wait on the first for no reason.
            OperationTask waitForTheOther = context -> {
                bothStarted.countDown();
                assertTrue(bothStarted.await(5, TimeUnit.SECONDS));
            };
            queue.submit(null, "C:/dev/one", OperationType.CLONE, waitForTheOther);
            queue.submit(null, "C:/dev/two", OperationType.CLONE, waitForTheOther);

            assertTrue(bothStarted.await(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void repositorylessWorkOnTheSameResourceStillRunsOneAtATime() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            OperationQueue queue = new OperationQueue(history, events, fixedClock(), pool);
            AtomicInteger concurrent = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(4);

            // Having no repository does not mean sharing nothing: a clone owns its target
            // directory, and two of them there would write over each other.
            for (int i = 0; i < 4; i++) {
                queue.submit(null, "C:/dev/same", OperationType.CLONE, context -> {
                    peak.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                    Thread.sleep(15);
                    concurrent.decrementAndGet();
                    done.countDown();
                });
            }

            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(1, peak.get(), "one directory tolerates exactly one writer");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aResourceKeyCannotCollideWithARepositoryId() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            OperationQueue queue = new OperationQueue(history, events, fixedClock(), pool);
            CountDownLatch bothStarted = new CountDownLatch(2);

            OperationTask waitForTheOther = context -> {
                bothStarted.countDown();
                assertTrue(bothStarted.await(5, TimeUnit.SECONDS));
            };
            // A directory named exactly like a repository id must not inherit that
            // repository's chain and stall behind its work.
            queue.submit(REPO_A, OperationType.CHECKOUT, waitForTheOther);
            queue.submit(null, REPO_A.asString(), OperationType.CLONE, waitForTheOther);

            assertTrue(bothStarted.await(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aDrainedChainStopsBeingTracked() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            OperationQueue queue = new OperationQueue(history, events, fixedClock(), pool);
            CountDownLatch done = new CountDownLatch(3);

            // Clone keys are one per target directory. Without cleanup a session that
            // clones into many directories keeps a completed future for every one.
            for (int i = 0; i < 3; i++) {
                queue.submit(null, "C:/dev/target-" + i, OperationType.CLONE,
                        context -> done.countDown());
            }

            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertTrue(awaitChainCount(queue, 0),
                    "finished chains still tracked: " + queue.chainCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cleanupDoesNotDropAChainThatStillHasWorkQueued() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            OperationQueue queue = new OperationQueue(history, events, fixedClock(), pool);
            CountDownLatch firstRunning = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch bothDone = new CountDownLatch(2);
            AtomicInteger concurrent = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();

            queue.submit(null, "C:/dev/same", OperationType.CLONE, context -> {
                peak.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                firstRunning.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                concurrent.decrementAndGet();
                bothDone.countDown();
            });
            assertTrue(firstRunning.await(5, TimeUnit.SECONDS));

            // Appended while the first is still running. When the first finishes it must
            // not take the key with it, or this one's successor would start alongside it.
            queue.submit(null, "C:/dev/same", OperationType.CLONE, context -> {
                peak.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                Thread.sleep(15);
                concurrent.decrementAndGet();
                bothDone.countDown();
            });
            release.countDown();

            assertTrue(bothDone.await(10, TimeUnit.SECONDS));
            assertEquals(1, peak.get(), "the second must still queue behind the first");
            assertTrue(awaitChainCount(queue, 0));
        } finally {
            pool.shutdownNow();
        }
    }

    /** Cleanup runs after the task returns, so the count settles slightly later. */
    private static boolean awaitChainCount(OperationQueue queue, int expected)
            throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (queue.chainCount() == expected) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    @Test
    void submittingAfterShutdownIsRefused() {
        OperationQueue queue = inlineQueue();
        queue.shutdown(Duration.ZERO);

        assertThrows(IllegalStateException.class, () -> queue.submit(
                REPO_A, OperationType.CHECKOUT, context -> {
                }));
    }

    // --- listing ---------------------------------------------------------

    @Test
    void listIsScopedToOneRepositoryAndCanFilterByState() {
        OperationQueue queue = inlineQueue();
        queue.submit(REPO_A, OperationType.CHECKOUT, context -> {
        });
        queue.submit(REPO_A, OperationType.BRANCH_CREATE, context -> {
            throw new IllegalStateException("nope");
        });
        queue.submit(REPO_B, OperationType.CHECKOUT, context -> {
        });

        assertEquals(2, queue.list(REPO_A, null).size());
        assertEquals(1, queue.list(REPO_A, OperationState.FAILED).size());
        assertEquals(1, queue.list(REPO_B, null).size());
        assertEquals(3, queue.list(null, null).size(), "no filter means every repository");
    }

    @Test
    void findFallsBackToHistoryOnceAnOperationIsEvicted() {
        OperationQueue queue = inlineQueue();
        List<OperationId> ids = new ArrayList<>();
        for (int i = 0; i < OperationQueue.MAX_RETAINED + 5; i++) {
            ids.add(queue.submit(REPO_A, OperationType.CHECKOUT, context -> {
            }).id());
        }

        // The oldest fell out of memory, but archiving means it is still findable.
        Operation oldest = queue.find(ids.get(0)).orElseThrow();
        assertEquals(OperationState.SUCCEEDED, oldest.state());
        assertNotNull(history.saved.get(ids.get(0)));
    }

    @Test
    void consoleOutputIsCappedSoARunawayTaskCannotExhaustTheHeap() {
        OperationQueue queue = inlineQueue();

        Operation accepted = queue.submit(REPO_A, OperationType.CHECKOUT, context -> {
            for (int i = 0; i < OperationQueue.MAX_LOG_LINES + 50; i++) {
                context.log("line " + i, ConsoleLevel.OUT);
            }
        });

        List<String> lines = queue.find(accepted.id()).orElseThrow().logLines();
        assertEquals(OperationQueue.MAX_LOG_LINES + 1, lines.size());
        assertTrue(lines.get(lines.size() - 1).contains("suppressed"));
    }

    // --- helpers ---------------------------------------------------------

    private static void waitForState(
            OperationQueue queue, OperationId id, OperationState expected) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (queue.find(id).map(Operation::state).orElse(null) == expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("operation never reached " + expected);
    }

    private static final class RecordingEvents implements OperationEvents {

        private final List<OperationProgress> progress =
                Collections.synchronizedList(new ArrayList<>());
        private final List<Operation> completed = Collections.synchronizedList(new ArrayList<>());
        private final List<String> consoleLines =
                Collections.synchronizedList(new ArrayList<>());
        private final List<RepositoryId> refsChanged =
                Collections.synchronizedList(new ArrayList<>());

        @Override
        public void progress(OperationId id, OperationProgress value) {
            progress.add(value);
        }

        @Override
        public void completed(Operation operation) {
            completed.add(operation);
        }

        @Override
        public void consoleLine(
                RepositoryId repositoryId, OperationId id, String line, String level) {
            consoleLines.add(level + ":" + line);
        }

        @Override
        public void refsChanged(RepositoryId repositoryId) {
            refsChanged.add(repositoryId);
        }

        @Override
        public void workingTreeChanged(RepositoryId repositoryId) {
        }

        @Override
        public void repositoryRegistered(RepositoryId repositoryId) {
        }
    }

    private static class InMemoryHistory implements OperationHistoryStore {

        private final Map<OperationId, Operation> saved =
                Collections.synchronizedMap(new LinkedHashMap<>());

        @Override
        public void save(Operation operation) {
            saved.put(operation.id(), operation);
        }

        @Override
        public Optional<Operation> findById(OperationId id) {
            return Optional.ofNullable(saved.get(id));
        }

        @Override
        public List<Operation> findRecent(RepositoryId repositoryId, int limit) {
            List<Operation> matching = new ArrayList<>();
            synchronized (saved) {
                for (Operation operation : saved.values()) {
                    if (repositoryId.equals(operation.repositoryId())) {
                        matching.add(operation);
                    }
                }
            }
            Collections.reverse(matching);
            return matching.size() > limit ? matching.subList(0, limit) : matching;
        }
    }
}
