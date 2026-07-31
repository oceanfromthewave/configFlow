import type {DiffHunk} from '@/entities/repository/model/types'

export type SideBySideRowKind = 'context' | 'changed' | 'empty'

/** One row of a side-by-side hunk: old text/number on the left, new on the right. */
export interface SideBySideRow {
    oldNumber: number | null
    oldText: string | null
    oldKind: SideBySideRowKind
    newNumber: number | null
    newText: string | null
    newKind: SideBySideRowKind
}

/**
 * Regroups a unified-diff hunk into side-by-side rows.
 *
 * A unified hunk already lists a change as a run of removed lines followed by
 * a run of added lines, so pairing them index-by-index (padding the shorter
 * run with empty cells) is enough to line old and new up column-by-column —
 * the same approach GitHub's split view uses.
 */
export function toSideBySideRows(hunk: DiffHunk): SideBySideRow[] {
    const rows: SideBySideRow[] = []
    let oldLine = hunk.oldStart
    let newLine = hunk.newStart
    let i = 0

    while (i < hunk.lines.length) {
        const line = hunk.lines[i]
        if (line.startsWith(' ')) {
            rows.push({
                oldNumber: oldLine++,
                oldText: line.slice(1),
                oldKind: 'context',
                newNumber: newLine++,
                newText: line.slice(1),
                newKind: 'context',
            })
            i++
            continue
        }

        const removed: string[] = []
        while (i < hunk.lines.length && hunk.lines[i].startsWith('-')) {
            removed.push(hunk.lines[i].slice(1))
            i++
        }
        const added: string[] = []
        while (i < hunk.lines.length && hunk.lines[i].startsWith('+')) {
            added.push(hunk.lines[i].slice(1))
            i++
        }

        if (removed.length === 0 && added.length === 0) {
            i++
            continue
        }

        const pairCount = Math.max(removed.length, added.length)
        for (let j = 0; j < pairCount; j++) {
            const oldText = j < removed.length ? removed[j] : null
            const newText = j < added.length ? added[j] : null
            rows.push({
                oldNumber: oldText != null ? oldLine++ : null,
                oldText,
                oldKind: oldText != null ? 'changed' : 'empty',
                newNumber: newText != null ? newLine++ : null,
                newText,
                newKind: newText != null ? 'changed' : 'empty',
            })
        }
    }

    return rows
}
