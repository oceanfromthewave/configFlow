/**
 * A stored credential as the client sees it: reference only, never the secret.
 *
 * The password or token lives in the OS credential store and is unreachable from
 * here — the server returns neither the secret nor its store key (docs/07 §125).
 */
export interface Credential {
  id: string
  host: string
  protocol: string
  /** Account name; empty for token-only auth. */
  username: string
  createdAt: string
}

/** POST body for registering a credential. The secret only ever travels inbound. */
export interface SaveCredentialPayload {
  host: string
  protocol: string
  /** Omitted or empty for token-only auth. */
  username?: string
  secret: string
}
