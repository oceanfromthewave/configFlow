import { ApiError } from '@/shared/api'
import type { MessageKey } from '@/shared/i18n'

/**
 * Maps a backend error `code` to a translated message.
 *
 * Server-authored text (`ApiError.detail`) is never shown to users: it is
 * English, untranslated, and can leak absolute paths or internals. The stable
 * `code` (docs/07 §1) is the contract the UI branches on.
 */
const ERROR_KEYS: Record<string, MessageKey> = {
  VALIDATION_ERROR: 'error.validation',
  NOT_FOUND: 'error.notFound',
  CAPABILITY_NOT_SUPPORTED: 'error.capabilityNotSupported',
  CONFLICT: 'error.conflict',
  MERGE_CONFLICT: 'error.mergeConflict',
  VCS_AUTH_REQUIRED: 'error.authRequired',
  VCS_NETWORK_ERROR: 'error.remoteUnreachable',
  NETWORK_ERROR: 'error.network',
  INTERNAL_ERROR: 'error.internal',
}

export function apiErrorKey(error: unknown): MessageKey {
  if (error instanceof ApiError) {
    return ERROR_KEYS[error.code] ?? 'error.unknown'
  }
  return 'error.unknown'
}
