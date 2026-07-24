import { getRuntimeConfig } from './config'

/**
 * Typed map of SSE events emitted by `GET /api/v1/events`
 * (docs/07-api-design.md §3).
 */
export interface SseEventMap {
  'operation.progress': {
    operationId: string
    percent: number
    phase: string
    detail?: string
  }
  'operation.completed': {
    operationId: string
    state: 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
    error: {
      /** Stable name of the cause; the only part safe to branch on. */
      code: string
      title?: string
      /** Server-authored English. Show the translated `code` instead. */
      detail?: string
      /** What answering it needs: `host` and `protocol` for VCS_AUTH_REQUIRED. */
      context?: Record<string, string>
    } | null
    result?: Record<string, unknown>
  }
  'workingtree.changed': { repositoryId: string }
  'repository.refs-changed': { repositoryId: string }
  /** A clone finished and joined the workspace, so the list of them is short by one. */
  'repository.registered': { repositoryId: string }
  'console.line': {
    repositoryId: string
    operationId?: string
    line: string
    level: 'cmd' | 'out' | 'err'
  }
  heartbeat: Record<string, never>
}

export type SseEventName = keyof SseEventMap

/** Discriminated union of every event, for `onAny` consumers. */
export type SseEvent = {
  [K in SseEventName]: { type: K; data: SseEventMap[K] }
}[SseEventName]

export type SseConnectionState = 'connecting' | 'open' | 'closed'

const EVENT_NAMES: readonly SseEventName[] = [
  'operation.progress',
  'operation.completed',
  'workingtree.changed',
  'repository.refs-changed',
  'repository.registered',
  'console.line',
  'heartbeat',
]

const INITIAL_BACKOFF_MS = 1_000
const MAX_BACKOFF_MS = 30_000

export interface SseClientOptions {
  /** Full URL override; defaults to `{apiBaseUrl}/events?token={token}`. */
  url?: string
}

/**
 * EventSource subscription with automatic reconnect (exponential backoff).
 *
 * EventSource cannot send custom headers, so the session token travels as
 * a query parameter instead of `X-ConfigFlow-Token`.
 */
export class SseClient {
  private readonly url: string
  private source: EventSource | null = null
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private backoffMs = INITIAL_BACKOFF_MS
  private closedByUser = false

  private readonly anyListeners = new Set<(event: SseEvent) => void>()
  private readonly listeners = new Map<
    SseEventName,
    Set<(data: never) => void>
  >()

  state: SseConnectionState = 'closed'

  constructor(options: SseClientOptions = {}) {
    if (options.url) {
      this.url = options.url
    } else {
      const { apiBaseUrl, token } = getRuntimeConfig()
      this.url = `${apiBaseUrl}/events?token=${encodeURIComponent(token)}`
    }
  }

  /** Subscribe to a single typed event. Returns an unsubscribe function. */
  on<K extends SseEventName>(
    name: K,
    handler: (data: SseEventMap[K]) => void,
  ): () => void {
    let set = this.listeners.get(name)
    if (!set) {
      set = new Set()
      this.listeners.set(name, set)
    }
    set.add(handler as (data: never) => void)
    return () => {
      set.delete(handler as (data: never) => void)
    }
  }

  /** Subscribe to all events as a discriminated union. */
  onAny(handler: (event: SseEvent) => void): () => void {
    this.anyListeners.add(handler)
    return () => {
      this.anyListeners.delete(handler)
    }
  }

  connect(): void {
    if (typeof EventSource === 'undefined') return // non-browser env (tests)
    if (this.source) return
    this.closedByUser = false
    this.state = 'connecting'

    const source = new EventSource(this.url)
    this.source = source

    source.onopen = () => {
      this.state = 'open'
      this.backoffMs = INITIAL_BACKOFF_MS
    }

    source.onerror = () => {
      // EventSource retries CONNECTING states itself; only when the
      // connection is fatally closed do we recreate it with backoff.
      if (source.readyState === EventSource.CLOSED) {
        this.teardownSource()
        this.scheduleReconnect()
      }
    }

    for (const name of EVENT_NAMES) {
      source.addEventListener(name, (raw) => {
        this.dispatch(name, (raw as MessageEvent<string>).data)
      })
    }
  }

  close(): void {
    this.closedByUser = true
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.teardownSource()
  }

  private teardownSource(): void {
    this.state = 'closed'
    if (this.source) {
      this.source.close()
      this.source = null
    }
  }

  private scheduleReconnect(): void {
    if (this.closedByUser || this.reconnectTimer) return
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.connect()
    }, this.backoffMs)
    this.backoffMs = Math.min(this.backoffMs * 2, MAX_BACKOFF_MS)
  }

  private dispatch(name: SseEventName, rawData: string): void {
    let data: unknown
    try {
      data = rawData ? JSON.parse(rawData) : {}
    } catch {
      return // malformed payload: ignore rather than crash the stream
    }
    const event = { type: name, data } as SseEvent
    this.listeners.get(name)?.forEach((handler) => {
      ;(handler as (d: unknown) => void)(data)
    })
    this.anyListeners.forEach((handler) => {
      handler(event)
    })
  }
}
