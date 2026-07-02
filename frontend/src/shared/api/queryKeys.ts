/**
 * Central TanStack Query key factory.
 *
 * Key shape convention: `['repositories', repositoryId, section, ...params]`.
 * SSE-driven invalidation (app/sseBridge.ts) relies on this shape, so all
 * future queries must build their keys through this factory.
 */
export const queryKeys = {
  health: () => ['health'] as const,

  repositories: () => ['repositories'] as const,
  repository: (repositoryId: string) => ['repositories', repositoryId] as const,

  status: (repositoryId: string) =>
    ['repositories', repositoryId, 'status'] as const,
  history: (repositoryId: string) =>
    ['repositories', repositoryId, 'history'] as const,
  refs: (repositoryId: string) =>
    ['repositories', repositoryId, 'refs'] as const,
  graph: (repositoryId: string) =>
    ['repositories', repositoryId, 'graph'] as const,
  conflicts: (repositoryId: string) =>
    ['repositories', repositoryId, 'conflicts'] as const,

  operations: () => ['operations'] as const,
  operation: (operationId: string) => ['operations', operationId] as const,
}

/** Repository sub-sections that must be refreshed after a VCS operation completes. */
export const OPERATION_AFFECTED_SECTIONS = [
  'status',
  'history',
  'refs',
  'graph',
] as const
