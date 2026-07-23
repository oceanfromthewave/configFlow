import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider } from '@/shared/i18n'
import { useUiStore } from '@/shared/lib/uiStore'
import { Toolbar } from '@/widgets/Toolbar'

function renderToolbar() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <Toolbar />
      </I18nProvider>
    </QueryClientProvider>,
  )
}

interface RecordedCall {
  url: string
  body: unknown
}

/** Answers GET /operations with `operations` and records every command POST. */
function stubApi(operations: unknown[] = [], postStatus = 202): RecordedCall[] {
  const calls: RecordedCall[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') {
        calls.push({
          url: String(input),
          body: init.body ? JSON.parse(String(init.body)) : null,
        })
        return Promise.resolve(
          new Response(
            JSON.stringify(
              postStatus === 202
                ? { operationId: 'op-1', type: 'PULL', state: 'QUEUED' }
                : { code: 'VCS_AUTH_REQUIRED' },
            ),
            { status: postStatus, headers: { 'content-type': 'application/json' } },
          ),
        )
      }
      return Promise.resolve(
        new Response(JSON.stringify(operations), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
      )
    }),
  )
  return calls
}

const initialUiState = useUiStore.getState()

beforeEach(() => {
  useUiStore.setState(initialUiState, true)
  useUiStore.setState({ route: 'repository', currentRepositoryId: 'repo-1' })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('Toolbar', () => {
  it('pulls with a merge strategy', async () => {
    const calls = stubApi()

    renderToolbar()
    await userEvent.click(screen.getByRole('button', { name: 'Pull' }))

    await waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].url).toContain('/repositories/repo-1/pull')
    expect(calls[0].body).toEqual({ strategy: 'MERGE' })
  })

  it('pushes and fetches through their own endpoints', async () => {
    const calls = stubApi()

    renderToolbar()
    await userEvent.click(screen.getByRole('button', { name: 'Push' }))
    await waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].url).toContain('/push')

    await userEvent.click(screen.getByRole('button', { name: 'Fetch' }))
    await waitFor(() => expect(calls).toHaveLength(2))
    expect(calls[1].url).toContain('/fetch')
    expect(calls[1].body).toEqual({ prune: false })
  })

  it('reveals the operations panel, which is where the answer appears', async () => {
    stubApi()
    expect(useUiStore.getState().bottomPanelCollapsed).toBe(true)

    renderToolbar()
    await userEvent.click(screen.getByRole('button', { name: 'Pull' }))

    expect(useUiStore.getState().bottomPanelTab).toBe('operations')
    expect(useUiStore.getState().bottomPanelCollapsed).toBe(false)
  })

  it('stays disabled until a repository is open', () => {
    stubApi()
    useUiStore.setState({ currentRepositoryId: null })

    renderToolbar()

    expect(screen.getByRole('button', { name: 'Pull' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Push' })).toBeDisabled()
  })

  it('disables the commands while the queue is still busy', async () => {
    // A second command would only queue behind the first, so the button would
    // look like it did nothing.
    stubApi([
      {
        operationId: 'op-1',
        type: 'FETCH',
        state: 'RUNNING',
        logLines: [],
      },
    ])

    renderToolbar()

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Pull' })).toBeDisabled(),
    )
  })

  it('names an auth failure rather than falling back to a generic error', async () => {
    stubApi([], 401)

    renderToolbar()
    await userEvent.click(screen.getByRole('button', { name: 'Push' }))

    // Every code the remote endpoints can return needs a translation; without one
    // the user is told "something went wrong" and has nothing to act on.
    expect(await screen.findByText('인증이 필요합니다')).toBeInTheDocument()
  })
})
