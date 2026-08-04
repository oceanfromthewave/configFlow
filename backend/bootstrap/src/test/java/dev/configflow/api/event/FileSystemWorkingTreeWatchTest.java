package dev.configflow.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationEvents;
import dev.configflow.domain.operation.OperationId;
import dev.configflow.domain.operation.OperationProgress;
import dev.configflow.domain.repository.RepositoryId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the reason this adapter exists: edits made outside ConfigFlow (an IDE, a terminal
 * `git checkout`) must reach the UI, while our own VCS metadata churn must not.
 */
class FileSystemWorkingTreeWatchTest {

    /** Generous: a WatchService can take a moment, and the watch itself debounces 500ms. */
    private static final long TIMEOUT_SECONDS = 10;

    private final RepositoryId id = RepositoryId.newId();
    private final RecordingEvents events = new RecordingEvents();
    private final FileSystemWorkingTreeWatch watch = new FileSystemWorkingTreeWatch(events);

    @TempDir
    Path workingCopy;

    @AfterEach
    void tearDown() throws IOException {
        watch.close();
    }

    @Test
    void reportsAFileCreatedOutsideTheApp() throws Exception {
        watch.watch(id, workingCopy);

        Files.writeString(workingCopy.resolve("README.md"), "hello");

        assertEquals(id, awaitChange());
    }

    @Test
    void collapsesABurstOfEditsIntoOneEvent() throws Exception {
        watch.watch(id, workingCopy);

        for (int i = 0; i < 20; i++) {
            Files.writeString(workingCopy.resolve("file" + i + ".txt"), "x");
        }

        assertEquals(id, awaitChange());
        // Twenty writes inside the debounce window are one refresh, not twenty.
        assertNull(events.changed.poll(2, TimeUnit.SECONDS));
    }

    @Test
    void watchesDirectoriesCreatedAfterRegistration() throws Exception {
        watch.watch(id, workingCopy);
        Path nested = Files.createDirectory(workingCopy.resolve("src"));
        assertEquals(id, awaitChange());

        Files.writeString(nested.resolve("Main.java"), "class Main {}");

        assertEquals(id, awaitChange());
    }

    @Test
    void ignoresTheVcsMetadataDirectory() throws Exception {
        Path git = Files.createDirectory(workingCopy.resolve(".git"));
        watch.watch(id, workingCopy);

        // What every one of our own commits touches; reporting it would loop the UI.
        Files.writeString(git.resolve("index"), "0000");

        assertNull(events.changed.poll(2, TimeUnit.SECONDS));
    }

    @Test
    void stopsReportingAfterUnwatch() throws Exception {
        watch.watch(id, workingCopy);
        watch.unwatch(id);

        Files.writeString(workingCopy.resolve("README.md"), "hello");

        assertNull(events.changed.poll(2, TimeUnit.SECONDS));
    }

    private RepositoryId awaitChange() throws InterruptedException {
        return events.changed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static final class RecordingEvents implements OperationEvents {

        final BlockingQueue<RepositoryId> changed = new LinkedBlockingQueue<>();

        @Override
        public void progress(OperationId id, OperationProgress progress) {
        }

        @Override
        public void completed(Operation operation) {
        }

        @Override
        public void consoleLine(RepositoryId repositoryId, OperationId id, String line, String level) {
        }

        @Override
        public void refsChanged(RepositoryId repositoryId) {
        }

        @Override
        public void repositoryRegistered(RepositoryId repositoryId) {
        }

        @Override
        public void workingTreeChanged(RepositoryId repositoryId) {
            changed.add(repositoryId);
        }
    }
}
