import { getRuntimeConfig } from './config'

/**
 * RFC 9457 Problem Details body as produced by the backend
 * (docs/07-api-design.md §1).
 */
export interface ProblemDetails {
  type?: string
  title?: string
  status?: number
  detail?: string
  /** Stable machine-readable code for frontend branching. */
  code?: string
  context?: Record<string, unknown>
}

/** Codes synthesized on the client when no Problem Details body exists. */
export const CLIENT_ERROR_CODES = {
  network: 'NETWORK_ERROR',
  unknown: 'UNKNOWN_ERROR',
} as const

export class ApiError extends Error {
  readonly code: string
  readonly title: string
  readonly detail?: string
  readonly status: number
  readonly context?: Record<string, unknown>

  constructor(args: {
    code: string
    title: string
    status: number
    detail?: string
    context?: Record<string, unknown>
    cause?: unknown
  }) {
    super(args.detail ?? args.title, { cause: args.cause })
    this.name = 'ApiError'
    this.code = args.code
    this.title = args.title
    this.status = args.status
    this.detail = args.detail
    this.context = args.context
  }

  static async fromResponse(response: Response): Promise<ApiError> {
    const fallback = new ApiError({
      code: CLIENT_ERROR_CODES.unknown,
      title: `HTTP ${response.status}`,
      status: response.status,
    })

    const contentType = response.headers.get('content-type') ?? ''
    if (!contentType.includes('json')) return fallback

    try {
      const body = (await response.json()) as ProblemDetails
      return new ApiError({
        code: body.code ?? CLIENT_ERROR_CODES.unknown,
        title: body.title ?? `HTTP ${response.status}`,
        status: body.status ?? response.status,
        detail: body.detail,
        context: body.context,
      })
    } catch {
      return fallback
    }
  }
}

export interface ApiFetchOptions extends Omit<RequestInit, 'body'> {
  body?: unknown
}

/**
 * Typed fetch wrapper: prefixes the base URL, attaches the
 * `X-ConfigFlow-Token` header, serializes JSON bodies and converts
 * error responses into typed {@link ApiError}s.
 */
export async function apiFetch<T>(
  path: string,
  options: ApiFetchOptions = {},
): Promise<T> {
  const { apiBaseUrl, token } = getRuntimeConfig()
  const { body, headers, ...init } = options

  let response: Response
  try {
    response = await fetch(`${apiBaseUrl}${path}`, {
      ...init,
      headers: {
        'X-ConfigFlow-Token': token,
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...headers,
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    })
  } catch (cause) {
    throw new ApiError({
      code: CLIENT_ERROR_CODES.network,
      title: 'Backend unreachable',
      status: 0,
      detail: cause instanceof Error ? cause.message : String(cause),
      cause,
    })
  }

  if (!response.ok) {
    throw await ApiError.fromResponse(response)
  }

  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}
