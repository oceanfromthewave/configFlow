package dev.configflow.domain.vcs.model;

import java.util.List;

/**
 * One hunk of a unified diff, structured for the frontend diff renderer.
 *
 * @param oldStart first line number on the old side (1-based)
 * @param oldCount number of lines on the old side
 * @param newStart first line number on the new side (1-based)
 * @param newCount number of lines on the new side
 * @param lines    raw hunk lines, each prefixed with {@code ' '}, {@code '+'} or {@code '-'}
 */
public record DiffHunk(int oldStart, int oldCount, int newStart, int newCount, List<String> lines) {

    public DiffHunk {
        lines = List.copyOf(lines == null ? List.of() : lines);
    }
}
