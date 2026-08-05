/**
 * The credential target the AI key is stored under. Matches `AiConfig.CLAUDE_KEY_HOST`
 * on the backend — the AI key rides the same host/protocol/username credential row as
 * remote auth, just at a fixed target, so no new storage or endpoint was needed for it.
 */
export const AI_KEY_HOST = 'api.anthropic.com'
export const AI_KEY_PROTOCOL = 'https'
export const AI_KEY_USERNAME = ''

export function isAiKeyCredential(credential: { host: string; protocol: string; username: string }) {
  return (
    credential.host === AI_KEY_HOST &&
    credential.protocol === AI_KEY_PROTOCOL &&
    credential.username === AI_KEY_USERNAME
  )
}
