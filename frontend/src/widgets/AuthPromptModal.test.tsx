import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider } from '@/shared/i18n'
import { useUiStore, type AuthPrompt } from '@/shared/lib/uiStore'
import { AuthPromptModal } from '@/widgets/AuthPromptModal'

const PROMPT: AuthPrompt = {
  operationId: 'op-1',
  host: 'github.com',
  protocol: 'https',
}

function renderModal(prompts: AuthPrompt[]) {
  useUiStore.setState({ authPrompts: prompts })
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <AuthPromptModal />
      </I18nProvider>
    </QueryClientProvider>,
  )
}

interface RecordedCall {
  url: string
  method: string
  body: unknown
}

/**
 * Stubs `fetch` for the modal's two POSTs: `/credentials` and the operation
 * retry. `credStatus`/`retryStatus` let a test fail either step; every call is
 * recorded so a test can assert on what was actually sent.
 */
function stubAuthApi(options: { credStatus?: number; retryStatus?: number } = {}) {
  const calls: RecordedCall[] = []
  const { credStatus = 200, retryStatus = 202 } = options

  const problem = (status: number) =>
    new Response(JSON.stringify({ code: 'INTERNAL_ERROR', title: 'boom', status }), {
      status,
      headers: { 'content-type': 'application/problem+json' },
    })

  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const method = init?.method ?? 'GET'
      calls.push({
        url,
        method,
        body: init?.body ? JSON.parse(String(init.body)) : null,
      })
      if (url.includes('/retry')) {
        return Promise.resolve(
          retryStatus >= 400
            ? problem(retryStatus)
            : new Response(
                JSON.stringify({
                  operationId: 'op-2',
                  type: 'FETCH',
                  state: 'QUEUED',
                  logLines: [],
                }),
                { status: retryStatus, headers: { 'content-type': 'application/json' } },
              ),
        )
      }
      // Anything else is the credential POST.
      return Promise.resolve(
        credStatus >= 400
          ? problem(credStatus)
          : new Response(
              JSON.stringify({
                id: 'cred-1',
                host: 'github.com',
                protocol: 'https',
                username: '',
                createdAt: '2026-01-01T00:00:00Z',
              }),
              { status: credStatus, headers: { 'content-type': 'application/json' } },
            ),
      )
    }),
  )
  return calls
}

afterEach(() => {
  vi.unstubAllGlobals()
  useUiStore.setState({ authPrompts: [] })
})

describe('AuthPromptModal', () => {
  it('renders nothing until an operation asks for credentials', () => {
    renderModal([])

    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('names the remote that rejected the request', () => {
    renderModal([PROMPT])

    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText(/https:\/\/github\.com/)).toBeInTheDocument()
  })

  it('saves a credential for the remote, then retries that same operation', async () => {
    const calls = stubAuthApi()

    renderModal([PROMPT])
    await userEvent.type(screen.getByLabelText('사용자 이름'), 'alice')
    await userEvent.type(screen.getByLabelText('비밀번호 / 토큰'), 'tok')
    await userEvent.click(screen.getByRole('button', { name: '저장 후 재시도' }))

    await waitFor(() => expect(calls).toHaveLength(2))

    const cred = calls.find((c) => c.url.includes('/credentials'))!
    expect(cred.method).toBe('POST')
    expect(cred.body).toEqual({
      host: 'github.com',
      protocol: 'https',
      username: 'alice',
      secret: 'tok',
    })

    const retry = calls.find((c) => c.url.includes('/retry'))!
    expect(retry.method).toBe('POST')
    expect(retry.url).toContain('/operations/op-1/retry')

    // Once the operation is safely re-queued the prompt closes on its own.
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('sends no username field for token-only auth', async () => {
    const calls = stubAuthApi()

    renderModal([PROMPT])
    await userEvent.type(screen.getByLabelText('비밀번호 / 토큰'), 'ghp_token')
    await userEvent.click(screen.getByRole('button', { name: '저장 후 재시도' }))

    await waitFor(() => expect(calls.some((c) => c.url.includes('/credentials'))).toBe(true))
    const cred = calls.find((c) => c.url.includes('/credentials'))!
    // An empty username is token auth, not a blank account: omit it entirely.
    expect(cred.body).toEqual({ host: 'github.com', protocol: 'https', secret: 'ghp_token' })
  })

  it('keeps the prompt open and explains when the credential is rejected', async () => {
    const calls = stubAuthApi({ credStatus: 500 })

    renderModal([PROMPT])
    await userEvent.type(screen.getByLabelText('비밀번호 / 토큰'), 'tok')
    await userEvent.click(screen.getByRole('button', { name: '저장 후 재시도' }))

    expect(await screen.findByText(/인증하지 못했습니다/)).toBeInTheDocument()
    // A failed save must not retry, and the dialog stays for another try.
    expect(calls.some((c) => c.url.includes('/retry'))).toBe(false)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('dismisses without touching the network when cancelled', async () => {
    const calls = stubAuthApi()

    renderModal([PROMPT])
    await userEvent.click(screen.getByRole('button', { name: '취소' }))

    expect(screen.queryByRole('dialog')).toBeNull()
    expect(calls).toHaveLength(0)
  })

  it('works through concurrent failures one at a time without losing any', async () => {
    stubAuthApi()
    const second: AuthPrompt = { operationId: 'op-2', host: 'gitlab.com', protocol: 'https' }
    renderModal([PROMPT, second])

    // The first failure is shown; the second waits behind it.
    expect(screen.getByText(/https:\/\/github\.com/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '취소' }))

    // The earlier prompt is gone, but the queued one takes its place rather than
    // the second failure being silently dropped.
    expect(screen.getByText(/https:\/\/gitlab\.com/)).toBeInTheDocument()
  })

  it('keeps save-and-retry disabled until a secret is entered', async () => {
    renderModal([PROMPT])

    const submit = screen.getByRole('button', { name: '저장 후 재시도' })
    expect(submit).toBeDisabled()

    // A username alone stores nothing; the secret is what there is to save.
    await userEvent.type(screen.getByLabelText('사용자 이름'), 'alice')
    expect(submit).toBeDisabled()

    await userEvent.type(screen.getByLabelText('비밀번호 / 토큰'), 'tok')
    expect(submit).toBeEnabled()
  })
})
