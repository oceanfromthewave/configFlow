package dev.configflow.application.operation;

import dev.configflow.domain.operation.ConsoleLevel;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationCancelledException;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs mutating VCS work off the request thread, one operation at a time per repository.
 *
 * <p><b>Why serial per repository.</b> Two commands on one working copy corrupt each
 * other — Git takes {@code index.lock} and a second writer simply fails, and a checkout
 * racing a commit is worse than a failure. Different repositories share nothing, so they
 * run in parallel: the serialisation is per repository, not global.</p>
 *
 * <p><b>How.</b> Each repository owns a chain of futures; submitting appends to its tail.
 * The chain swallows the previous link's outcome, so one failed operation does not strand
 * everything queued behind it.</p>
 *
 * <p><b>Cancellation is cooperative.</b> A queued operation is dropped before it starts.
 * A running one only stops where its task calls {@link OperationContext#throwIfCancelled()},
 * because interrupting a JGit call mid-write is how a working copy gets corrupted.</p>
 *
 * <p>Pure Java on purpose: no Spring, no SSE. Progress leaves through the
 * {@link OperationEvents} port so the queue stays testable without a transport.</p>
 */
public final class OperationQueue {

    /** Per-operation console cap: a runaway clone must not exhaust the heap. */
    static final int MAX_LOG_LINES = 500;

    /** How many finished operations stay resident; older ones are read back from history. */
    static final int MAX_RETAINED = 100;

    private final OperationHistoryStore history;
    private final OperationEvents events;
    private final Clock clock;
    private final Executor executor;

    private final Map<OperationId, LiveOperation> live = new ConcurrentHashMap<>();

    /** Tail of each repository's chain. Bounded by the number of registered repositories. */
    private final Map<String, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

    /** Guards admission against shutdown; held only for map work, never while a task runs. */
    private final Object lifecycle = new Object();

    private volatile boolean accepting = true;

    public OperationQueue(
            OperationHistoryStore history,
            OperationEvents events,
            Clock clock,
            Executor executor) {
        this.history = Objects.requireNonNull(history, "history");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * Accepts work and returns immediately with a {@code QUEUED} snapshot.
     *
     * @param repositoryId repository the work runs on; {@code null} for work that has no
     *                     repository yet, such as a clone into a new directory
     */
    public Operation submit(RepositoryId repositoryId, OperationType type, OperationTask task) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(task, "task");
        LiveOperation operation = new LiveOperation(OperationId.newId(), repositoryId, type);

        // Admission and shutdown share this lock. Without it shutdown could snapshot the
        // chains between the accepting check and the linkage below, leaving an accepted
        // operation outside the wait and stuck at QUEUED.
        synchronized (lifecycle) {
            if (!accepting) {
                throw new IllegalStateException("The operation queue is shutting down");
            }
            live.put(operation.id, operation);

            // Work with no repository — a clone into a new directory — has nothing to
            // serialise against, so each gets its own chain rather than queueing behind
            // unrelated clones.
            String chainKey = repositoryId == null
                    ? "clone:" + operation.id.asString()
                    : repositoryId.asString();
            tails.compute(chainKey, (key, tail) -> {
                CompletableFuture<Void> previous =
                        tail == null ? CompletableFuture.completedFuture(null) : tail;
                return previous
                        // Absorb the previous outcome: a failure must not cancel the queue.
                        .handle((ignoredResult, ignoredError) -> null)
                        .thenRunAsync(() -> execute(operation, task), executor);
            });
        }

        return operation.snapshot();
    }

    /**
     * Asks an operation to stop.
     *
     * @return {@code false} when there is no such operation or it already finished
     */
    public boolean cancel(OperationId id) {
        LiveOperation operation = live.get(id);
        if (operation == null || operation.state.isTerminal()) {
            return false;
        }
        operation.cancelRequested.set(true);
        return true;
    }

    /**
     * Stops taking work and gives what is already queued a chance to finish.
     *
     * <p>Shutting the executor down on its own is not enough. A chain only submits its
     * next link once the previous one completes, so at that moment only the running
     * operation is with the executor — everything behind it would be rejected on
     * submission and left sitting at {@code QUEUED} forever, with clients waiting on a
     * completion event that never arrives.</p>
     *
     * <p>So: stop accepting, wait for the chains, and mark whatever is still unfinished
     * as cancelled. Callers must not shut the executor down until this returns.</p>
     *
     * @param grace how long to wait for outstanding work before giving up on it
     */
    public void shutdown(Duration grace) {
        CompletableFuture<?>[] outstanding;
        synchronized (lifecycle) {
            accepting = false;
            outstanding = tails.values().toArray(CompletableFuture[]::new);
        }

        try {
            CompletableFuture.allOf(outstanding).get(grace.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            // A failed chain already recorded itself, and anything past the deadline is
            // handled below. Either way there is nothing left to wait for.
        }

        for (LiveOperation operation : live.values()) {
            // Ask first: a task still running past the grace period may yet stop on its
            // own, and terminate() below only wins if it has not finished already.
            operation.cancelRequested.set(true);
            terminate(operation, OperationState.CANCELLED,
                    "The application shut down before this operation finished");
        }
    }

    /** One operation, live or read back from history. */
    public Optional<Operation> find(OperationId id) {
        LiveOperation operation = live.get(id);
        return operation != null ? Optional.of(operation.snapshot()) : history.findById(id);
    }

    /**
     * Operations of a repository, newest first.
     *
     * @param state optional state filter, {@code null} for all
     */
    public List<Operation> list(RepositoryId repositoryId, OperationState state) {
        Map<OperationId, Operation> byId = new LinkedHashMap<>();
        for (LiveOperation operation : live.values()) {
            if (matches(operation, repositoryId, state)) {
                byId.put(operation.id, operation.snapshot());
            }
        }
        if (repositoryId != null) {
            for (Operation archived : history.findRecent(repositoryId, MAX_RETAINED)) {
                if (state == null || archived.state() == state) {
                    byId.putIfAbsent(archived.id(), archived);
                }
            }
        }
        List<Operation> all = new ArrayList<>(byId.values());
        all.sort(Comparator.comparing(OperationQueue::orderingTime).reversed());
        return all;
    }

    // --- execution -------------------------------------------------------

    private void execute(LiveOperation operation, OperationTask task) {
        if (operation.isSettled()) {
            // Shutdown already recorded an outcome for this one while it waited. Running
            // it now would touch the repository after the app said it was done.
            return;
        }
        if (operation.cancelRequested.get()) {
            // Cancelled while still waiting its turn: it must never touch the repository.
            terminate(operation, OperationState.CANCELLED, null);
            return;
        }

        operation.state = OperationState.RUNNING;
        operation.startedAt = clock.instant();
        // Announce the start so a client sees it leave the queue even if the task is
        // silent until it finishes.
        operation.progress = OperationProgress.indeterminate(operation.type.name());
        events.progress(operation.id, operation.progress);

        try {
            task.run(new TaskContext(operation));
            terminate(operation, OperationState.SUCCEEDED, null);
        } catch (OperationCancelledException e) {
            terminate(operation, OperationState.CANCELLED, null);
        } catch (Throwable e) {
            // Throwable, not Exception: an Error would otherwise leave the operation
            // stuck in RUNNING for the rest of the session, and anything watching it
            // would wait forever.
            terminate(operation, OperationState.FAILED, describe(e));
            if (e instanceof Error) {
                // Rethrown rather than swallowed, so the future completes exceptionally
                // and the failure is not disguised as an orderly finish. The chain
                // absorbs it either way, so this does not affect queued work.
                throw (Error) e;
            }
        }
    }

    private void terminate(LiveOperation operation, OperationState state, String errorMessage) {
        if (!operation.settle()) {
            // Someone got here first. Shutdown and a task finishing late both end up
            // calling this, and the second one must not overwrite the recorded outcome or
            // emit a duplicate completion.
            return;
        }
        operation.state = state;
        operation.finishedAt = clock.instant();
        operation.errorMessage = errorMessage;

        try {
            history.save(operation.snapshot());
        } catch (RuntimeException e) {
            // Archiving is not the operation's job: losing the record must not turn a
            // successful checkout into a failure. Surface it on the console instead.
            operation.addLogLine("Could not archive this operation: " + describe(e));
        }

        events.completed(operation.snapshot());
        trimRetained();
    }

    /** Keeps memory flat; evicted operations are still readable through the history store. */
    private void trimRetained() {
        List<LiveOperation> finished = new ArrayList<>();
        for (LiveOperation operation : live.values()) {
            if (operation.state.isTerminal()) {
                finished.add(operation);
            }
        }
        if (finished.size() <= MAX_RETAINED) {
            return;
        }
        finished.sort(Comparator.comparing(
                operation -> operation.finishedAt == null ? Instant.EPOCH : operation.finishedAt));
        for (int i = 0; i < finished.size() - MAX_RETAINED; i++) {
            live.remove(finished.get(i).id);
        }
    }

    private static boolean matches(
            LiveOperation operation, RepositoryId repositoryId, OperationState state) {
        if (repositoryId != null && !repositoryId.equals(operation.repositoryId)) {
            return false;
        }
        return state == null || operation.state == state;
    }

    /** Queued operations have no timestamps yet, so they sort as most recent. */
    private static Instant orderingTime(Operation operation) {
        if (operation.finishedAt() != null) {
            return operation.finishedAt();
        }
        return operation.startedAt() != null ? operation.startedAt() : Instant.MAX;
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message != null && !message.isBlank()
                ? message
                : e.getClass().getSimpleName();
    }

    // --- live state ------------------------------------------------------

    /**
     * Mutable state of one operation.
     *
     * <p>A record would mean rebuilding the whole operation on every progress tick, and
     * progress arrives thousands of times during a clone. Fields are volatile because the
     * worker writes them while HTTP threads read them.</p>
     */
    private static final class LiveOperation {

        private final OperationId id;
        private final RepositoryId repositoryId;
        private final OperationType type;
        private final List<String> logLines = Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean cancelRequested = new AtomicBoolean();

        /** Claimed by whoever records the terminal state, so only the first one counts. */
        private final AtomicBoolean settled = new AtomicBoolean();

        private volatile OperationState state = OperationState.QUEUED;
        private volatile OperationProgress progress;
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile String errorMessage;

        private LiveOperation(OperationId id, RepositoryId repositoryId, OperationType type) {
            this.id = id;
            this.repositoryId = repositoryId;
            this.type = type;
        }

        /** True for exactly one caller: the one allowed to record the terminal state. */
        boolean settle() {
            return settled.compareAndSet(false, true);
        }

        boolean isSettled() {
            return settled.get();
        }

        void addLogLine(String line) {
            synchronized (logLines) {
                if (logLines.size() < MAX_LOG_LINES) {
                    logLines.add(line);
                } else if (logLines.size() == MAX_LOG_LINES) {
                    logLines.add("… further output suppressed after "
                            + MAX_LOG_LINES + " lines");
                }
            }
        }

        Operation snapshot() {
            synchronized (logLines) {
                return new Operation(
                        id, repositoryId, type, state, progress,
                        startedAt, finishedAt, errorMessage, List.copyOf(logLines));
            }
        }
    }

    /** The view a running task gets of its own operation. */
    private final class TaskContext implements OperationContext {

        private final LiveOperation operation;

        private TaskContext(LiveOperation operation) {
            this.operation = operation;
        }

        @Override
        public OperationId operationId() {
            return operation.id;
        }

        @Override
        public void progress(OperationProgress progress) {
            operation.progress = progress;
            events.progress(operation.id, progress);
        }

        @Override
        public void log(String line, ConsoleLevel level) {
            operation.addLogLine(line);
            events.consoleLine(operation.repositoryId, operation.id, line, level.wireName());
        }

        @Override
        public boolean isCancelled() {
            return operation.cancelRequested.get();
        }
    }
}
