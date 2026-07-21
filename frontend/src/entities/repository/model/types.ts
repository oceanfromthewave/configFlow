/**
 * Repository entity as returned by `GET /repositories`
 * (docs/07-api-design.md). Only the fields the Welcome grid needs for now;
 * extended in later milestones.
 */
export type VcsType = 'GIT' | 'SVN'

export interface RepositorySummary {
  id: string
  name: string
  localPath: string
  vcsType: VcsType
  favorite: boolean
  group?: string
  lastOpenedAt?: string
}

/** How a single path differs from the previous revision. */
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
}

export type Resolution = 'UNRESOLVED' | 'MINE' | 'THEIRS' | 'MANUAL'

export interface ConflictedFile {
  path: string
  resolution: Resolution
}

/** Working-tree status as returned by `GET /repositories/{id}/status`. */
export interface WorkingTreeStatus {
  staged: FileChange[]
  unstaged: FileChange[]
  conflicted: ConflictedFile[]
}
