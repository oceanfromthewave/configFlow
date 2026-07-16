/**
 * VCS domain types mirroring the backend domain records
 * (backend/domain/.../vcs/model, serialized to JSON per docs/07):
 * Path -> string, Instant -> ISO-8601, enums -> strings.
 */

export type ChangeType =
  | 'ADDED'
  | 'MODIFIED'
  | 'DELETED'
  | 'RENAMED'
  | 'COPIED'
  | 'CONFLICTED'
  | 'UNTRACKED'
  | 'IGNORED'
  | 'LOCKED_BY_OTHER'

export interface FileChange {
  path: string
  type: ChangeType
  oldPath?: string | null
  locked?: boolean
  lockOwner?: string | null
}

export type ConflictResolution = 'UNRESOLVED' | 'MINE' | 'THEIRS' | 'MANUAL'

export interface ConflictedFile {
  path: string
  resolution: ConflictResolution
}

export interface WorkingTreeStatus {
  staged: FileChange[]
  unstaged: FileChange[]
  conflicted: ConflictedFile[]
}

/** One hunk of a unified diff; lines are prefixed with ' ', '+' or '-'. */
export interface DiffHunk {
  oldStart: number
  oldCount: number
  newStart: number
  newCount: number
  lines: string[]
}

export interface FileDiff {
  path: string
  oldPath?: string | null
  type: ChangeType
  binary: boolean
  hunks: DiffHunk[]
}

export interface Author {
  name: string
  email?: string | null
}

export type RefLabelKind = 'BRANCH' | 'REMOTE_BRANCH' | 'TAG' | 'HEAD'

export interface RefLabel {
  kind: RefLabelKind
  name: string
}

/** Unified Git commit / SVN revision (docs/05 §1). */
export interface Revision {
  id: string
  parents: string[]
  author: Author
  timestamp: string
  message: string
  labels: RefLabel[]
}

/** Cursor-paged result (history, graph). `nextCursor` is null when exhausted. */
export interface Page<T> {
  items: T[]
  nextCursor: string | null
}

/**
 * `GET /repositories/{id}/revisions/{revId}` — commit metadata plus its
 * changed files (docs/07: "메타 + FileChange[]"). Assumed flattened shape;
 * flagged as a contract ambiguity for the integrator.
 */
export interface RevisionDetail extends Revision {
  files: FileChange[]
}
