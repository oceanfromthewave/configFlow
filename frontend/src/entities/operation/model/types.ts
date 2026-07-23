/** Lifecycle of a queued operation (docs/05). */
export type OperationState =
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'

export type OperationType =
  | 'CLONE'
  | 'INIT'
  | 'FETCH'
  | 'PULL'
  | 'PUSH'
  | 'COMMIT'
  | 'CHECKOUT'
  | 'BRANCH_CREATE'
  | 'BRANCH_DELETE'
  | 'MERGE'
  | 'REBASE'
  | 'CHERRY_PICK'
  | 'RESET'
  | 'REVERT'
  | 'TAG'
  | 'STASH'
  | 'LOCK'
  | 'UNLOCK'
  | 'SVN_UPDATE'
  | 'SVN_CLEANUP'
  | 'OTHER'

/** `percent` is null while the total amount of work is unknown. */
export interface OperationProgress {
  percent: number | null
  phase: string
  detail?: string | null
}

export interface Operation {
  operationId: string
  repositoryId?: string | null
  type: OperationType
  state: OperationState
  progress?: OperationProgress | null
  startedAt?: string | null
  finishedAt?: string | null
  errorMessage?: string | null
  logLines: string[]
}

/** The 202 body every mutating endpoint answers with. */
export interface AcceptedOperation {
  operationId: string
  type: OperationType
  state: OperationState
}

/** True once the operation can no longer change. */
export function isTerminal(state: OperationState): boolean {
  return state === 'SUCCEEDED' || state === 'FAILED' || state === 'CANCELLED'
}
