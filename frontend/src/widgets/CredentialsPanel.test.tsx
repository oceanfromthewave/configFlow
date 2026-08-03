import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider } from '@/shared/i18n'
import { CredentialsPanel } from '@/widgets/CredentialsPanel'

function renderPanel() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <CredentialsPanel />
      </I18nProvider>
    </QueryClientProvider>,
  )
}

interface RecordedCall {
  url: string
  method: string
  body: unknown
}

/** Answers GET /credentials with `credentials` and records every mutating call. */
function stubCredentials(credentials: unknown, status = 200) {
  const calls: RecordedCall[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const method = init?.method ?? 'GET'
      if (method !== 'GET') {
        calls.push({
          url,
          method,
          body: init?.body ? JSON.parse(String(init.body)) : null,
        })
        return Promise.resolve(new Response(null, { status: 204 }))
      }
      return Promise.resolve(
        new Response(JSON.stringify(credentials), {
          status,
          headers: { 'content-type': 'application/json' },
        }),
      )
    }),
  )
  return calls
}

function credential(overrides: object = {}) {
  return {
    id: 'cred-1',
    host: 'github.com',
    protocol: 'https',
    username: 'alice',
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('CredentialsPanel', () => {
  it('lists a credential by target and account, never a secret', async () => {
    stubCredentials([credential()])

    renderPanel()

    expect(await screen.findByText('https://github.com')).toBeInTheDocument()
    expect(screen.getByText('alice')).toBeInTheDocument()
  })

  it('labels a token-only credential rather than showing a blank account', async () => {
    stubCredentials([credential({ username: '' })])

    renderPanel()

    // An empty username is token auth, not a nameless row.
    expect(await screen.findByText('토큰 인증')).toBeInTheDocument()
  })

  it('shows the empty state when nothing is registered', async () => {
    stubCredentials([])

    renderPanel()

    expect(await screen.findByText('저장된 자격 증명이 없습니다.')).toBeInTheDocument()
  })

  it('deletes through the credential endpoint', async () => {
    const calls = stubCredentials([credential({ id: 'cred-42' })])

    renderPanel()
    await userEvent.click(await screen.findByRole('button', { name: '삭제' }))

    await waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].method).toBe('DELETE')
    expect(calls[0].url).toContain('/credentials/cred-42')
  })

  it('surfaces a translated error when a delete fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
        const method = init?.method ?? 'GET'
        if (method === 'DELETE') {
          return Promise.resolve(
            new Response(
              JSON.stringify({ code: 'INTERNAL_ERROR', title: 'boom', status: 500 }),
              { status: 500, headers: { 'content-type': 'application/problem+json' } },
            ),
          )
        }
        return Promise.resolve(
          new Response(JSON.stringify([credential()]), {
            status: 200,
            headers: { 'content-type': 'application/json' },
          }),
        )
      }),
    )

    renderPanel()
    await userEvent.click(await screen.findByRole('button', { name: '삭제' }))

    // Failing silently used to just re-enable the button; the row must now say why.
    expect(await screen.findByText(/삭제하지 못했습니다/)).toBeInTheDocument()
  })

  it('posts the entered credential, secret included', async () => {
    const calls = stubCredentials([])

    renderPanel()
    await screen.findByText('저장된 자격 증명이 없습니다.')

    // Two forms share label text (host/username), so scope to the credential form's
    // section — the one with the secret field, which the SSH key form has no equivalent of.
    const form = screen.getByLabelText('비밀번호 / 토큰').closest('form') as HTMLElement
    await userEvent.type(within(form).getByLabelText('호스트'), 'github.com')
    await userEvent.type(within(form).getByLabelText('사용자 이름'), 'alice')
    await userEvent.type(within(form).getByLabelText('비밀번호 / 토큰'), 'tok')
    await userEvent.click(within(form).getByRole('button', { name: '추가' }))

    await waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].method).toBe('POST')
    expect(calls[0].url).toContain('/credentials')
    expect(calls[0].body).toEqual({
      host: 'github.com',
      protocol: 'https',
      username: 'alice',
      secret: 'tok',
    })
  })

  it('keeps the add button disabled until a host and secret are entered', async () => {
    stubCredentials([])

    renderPanel()

    const form = (await screen.findByLabelText('비밀번호 / 토큰')).closest('form') as HTMLElement
    const add = within(form).getByRole('button', { name: '추가' })
    expect(add).toBeDisabled()

    // Host alone is not enough — a credential with no secret is nothing to store.
    await userEvent.type(within(form).getByLabelText('호스트'), 'github.com')
    expect(add).toBeDisabled()

    await userEvent.type(within(form).getByLabelText('비밀번호 / 토큰'), 'tok')
    expect(add).toBeEnabled()
  })

  it('generates an SSH key and shows the public half', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input)
        const method = init?.method ?? 'GET'
        if (method === 'POST' && url.includes('/credentials/ssh-keys')) {
          return Promise.resolve(
            new Response(
              JSON.stringify(
                credential({
                  id: 'cred-ssh',
                  protocol: 'ssh',
                  username: 'git',
                  publicKey: 'ssh-ed25519 AAAA... git@laptop',
                }),
              ),
              { status: 200, headers: { 'content-type': 'application/json' } },
            ),
          )
        }
        return Promise.resolve(
          new Response(JSON.stringify([]), {
            status: 200,
            headers: { 'content-type': 'application/json' },
          }),
        )
      }),
    )

    renderPanel()
    await screen.findByText('SSH 키')

    const form = screen.getByText('SSH 키').closest('form') as HTMLElement
    await userEvent.type(within(form).getByLabelText('호스트'), 'github.com')
    await userEvent.type(within(form).getByLabelText('사용자 이름'), 'git')
    await userEvent.click(within(form).getByRole('button', { name: '키 생성' }))

    expect(await screen.findByText('ssh-ed25519 AAAA... git@laptop')).toBeInTheDocument()
  })

  it('shows the public key on an existing SSH credential row', async () => {
    stubCredentials([
      credential({
        id: 'cred-ssh',
        protocol: 'ssh',
        username: 'git',
        publicKey: 'ssh-ed25519 AAAA... git@laptop',
      }),
    ])

    renderPanel()

    expect(await screen.findByText('ssh-ed25519 AAAA... git@laptop')).toBeInTheDocument()
  })
})
