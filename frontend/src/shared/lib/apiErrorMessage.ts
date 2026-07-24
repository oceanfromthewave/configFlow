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
  CANCELLED: 'error.cancelled',
  INTERNAL_ERROR: 'error.internal',
}

/**
 * Translates a bare failure code.
 *
 * The same codes arrive two ways — as an HTTP problem, and on the event stream
 * when queued work fails long after its request answered — so the lookup cannot
 * require an {@link ApiError} to wrap it.
 */
export function errorCodeKey(code: string | null | undefined): MessageKey {
  if (!code) return 'error.unknown'
  return ERROR_KEYS[code] ?? 'error.unknown'
}

export function apiErrorKey(error: unknown): MessageKey {
  if (error instanceof ApiError) {
    return errorCodeKey(error.code)
  }
  return 'error.unknown'
}
