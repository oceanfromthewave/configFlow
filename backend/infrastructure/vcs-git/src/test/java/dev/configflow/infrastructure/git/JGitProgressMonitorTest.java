package dev.configflow.infrastructure.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.operation.OperationProgress;
import dev.configflow.domain.vcs.port.OperationMonitor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.junit.jupiter.api.Test;

class JGitProgressMonitorTest {

    private final List<OperationProgress> reports = new ArrayList<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private final ProgressMonitor monitor = new JGitProgressMonitor(new OperationMonitor() {
        @Override
        public void onProgress(OperationProgress progress) {
            reports.add(progress);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    });

    @Test
    void beginTask_announcesThePhaseBeforeAnyWorkIsDone() {
        monitor.beginTask("Receiving objects", 100);

        // The pause before the first chunk can be long; the user should know why.
        assertEquals(1, reports.size());
        assertEquals("Receiving objects", reports.get(0).phase());
        assertNull(reports.get(0).percent(), "nothing has happened yet");
    }

    @Test
    void update_reportsPercentageAgainstTheTotal() {
        monitor.beginTask("Receiving objects", 200);
        reports.clear();

        monitor.update(50);
        monitor.update(50);

        assertEquals(List.of(25, 50), reports.stream().map(OperationProgress::percent).toList());
        assertEquals("100/200", reports.get(1).detail());
    }

    @Test
    void update_onlyReportsWhenTheWholePercentMoves() {
        monitor.beginTask("Receiving objects", 1_000);
        reports.clear();

        // JGit updates once per object; a 1000-object transfer must not become 1000 events.
        for (int i = 0; i < 1_000; i++) {
            monitor.update(1);
        }

        // 0 through 100 inclusive: the 0% report is what turns an indeterminate bar into
        // a determinate one, since it is the first to carry the total.
        assertEquals(101, reports.size(), "one report per whole percent");
        assertEquals(0, reports.get(0).percent());
        assertEquals(100, reports.get(reports.size() - 1).percent());
    }

    @Test
    void update_withoutAKnownTotalReportsNothingFurther() {
        monitor.beginTask("Counting", ProgressMonitor.UNKNOWN);
        reports.clear();

        monitor.update(10);

        // A percentage would be invented; the phase from beginTask is all we honestly have.
        assertTrue(reports.isEmpty());
    }

    @Test
    void update_neverExceedsAHundredPercent() {
        monitor.beginTask("Receiving objects", 10);
        reports.clear();

        monitor.update(50);

        assertEquals(100, reports.get(0).percent());
    }

    @Test
    void eachTaskRestartsTheCount() {
        monitor.beginTask("Counting", 100);
        monitor.update(50);
        monitor.beginTask("Receiving objects", 100);
        reports.clear();

        monitor.update(10);

        assertEquals(10, reports.get(0).percent(), "the new phase starts from zero");
        assertEquals("Receiving objects", reports.get(0).phase());
    }

    @Test
    void isCancelled_passesTheSignalThroughToJGit() {
        assertFalse(monitor.isCancelled());

        cancelled.set(true);

        // This is the hook JGit consults between chunks; without it a long transfer could
        // not be stopped at all.
        assertTrue(monitor.isCancelled());
    }
}
