package dev.configflow.domain.operation;

/**
 * Kind of long-running or mutating operation submitted to the operation queue.
 */
public enum OperationType {
    CLONE,
    INIT,
    FETCH,
    PULL,
    PUSH,
    COMMIT,
    CHECKOUT,
    BRANCH_CREATE,
    BRANCH_DELETE,
    MERGE,
    REBASE,
    CHERRY_PICK,
    RESET,
    REVERT,
    TAG,
    STASH,
    LOCK,
    UNLOCK,
    SVN_UPDATE,
    SVN_CLEANUP,
    OTHER
}
