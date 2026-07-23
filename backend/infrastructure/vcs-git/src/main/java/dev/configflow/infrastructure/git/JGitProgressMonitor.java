package dev.configflow.infrastructure.git;

import dev.configflow.domain.operation.OperationProgress;
import dev.configflow.domain.vcs.port.OperationMonitor;
import org.eclipse.jgit.lib.ProgressMonitor;

/**
 * Adapts JGit's {@link ProgressMonitor} onto our {@link OperationMonitor}.
 *
 * <p>Cancellation rides along: JGit asks {@link #isCancelled()} between chunks, and
 * between chunks is the only place a transfer can stop without leaving a half-written
 * pack behind.</p>
 */
final class JGitProgressMonitor implements ProgressMonitor {

    private final OperationMonitor monitor;

    private String task = "";
    private int totalWork = UNKNOWN;
    private int completed;
    private int lastReportedPercent = -1;

    JGitProgressMonitor(OperationMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public void start(int totalTasks) {
        // Nothing useful yet: the task names arrive with beginTask.
    }

    @Override
    public void beginTask(String title, int total) {
        task = title == null ? "" : title;
        totalWork = total;
        completed = 0;
        lastReportedPercent = -1;
        // Announce the phase straight away: "Receiving objects" at 0% still tells the
        // user what is happening during the pause before the first chunk lands.
        monitor.onProgress(OperationProgress.indeterminate(task));
    }

    @Override
    public void update(int completedInThisChunk) {
        completed += completedInThisChunk;
        if (totalWork == UNKNOWN || totalWork <= 0) {
            return;
        }
        int percent = (int) Math.min(100L, (long) completed * 100 / totalWork);
        // JGit updates per object, so a big clone would emit tens of thousands of events.
        // Whole percent changes are all a progress bar can render anyway.
        if (percent != lastReportedPercent) {
            lastReportedPercent = percent;
            monitor.onProgress(
                    new OperationProgress(percent, task, completed + "/" + totalWork));
        }
    }

    @Override
    public void endTask() {
        // The next beginTask replaces the phase; nothing to report on its own.
    }

    @Override
    public boolean isCancelled() {
        return monitor.isCancelled();
    }

    @Override
    public void showDuration(boolean enabled) {
        // Only affects JGit's own textual rendering, which we never use.
    }
}
