import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider } from '@/shared/i18n'
import { useUiStore } from '@/shared/lib/uiStore'
import { OperationsPanel } from '@/widgets/OperationsPanel'

function renderPanel() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <OperationsPanel />
      </I18nProvider>
    </QueryClientProvider>,
  )
}

interface RecordedCall {
  url: string
  method: string
}

/** Answers GET /operations with `operations` and records every POST. */
function stubOperations(operations: unknown, status = 200) {
  const calls: RecordedCall[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (init?.method === 'POST') {
        calls.push({ url, method: 'POST' })
        return Promise.resolve(new Response(null, { status: 204 }))
      }
      return Promise.resolve(
        new Response(JSON.stringify(operations), {
          status,
          headers: { 'content-type': 'application/json' },
        }),
      )
    }),
  )
  return calls
}

function operation(overrides: object = {}) {
  return {
    operationId: 'op-1',
    repositoryId: 'repo-1',
    type: 'CHECKOUT',
    state: 'RUNNING',
    progress: { percent: 40, phase: 'Switching', detail: 'main' },
    startedAt: '2026-01-01T00:00:00Z',
    finishedAt: null,
    errorMessage: null,
    logLines: [],
    ...overrides,
  }
}

const initialUiState = useUiStore.getState()

beforeEach(() => {
  useUiStore.setState(initialUiState, true)
  useUiStore.setState({ route: 'repository', currentRepositoryId: 'repo-1' })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('OperationsPanel', () => {
  it('shows the state, type and progress of a running operation', async () => {
    stubOperations([operation()])

    renderPanel()

    expect(await screen.findByText('실행 중')).toBeInTheDocument()
    expect(screen.getByText('체크아웃')).toBeInTheDocument()
    expect(screen.getByText('Switching · main')).toBeInTheDocument()
    expect(screen.getByText('40%')).toBeInTheDocument()
  })

  it('offers cancel only while an operation can still stop', async () => {
    stubOperations([
      operation({ operationId: 'running', state: 'RUNNING' }),
      operation({ operationId: 'done', state: 'SUCCEEDED', progress: null }),
      operation({ operationId: 'queued', state: 'QUEUED', progress: null }),
    ])

    renderPanel()
    await screen.findByText('완료')

    // Queued and running can be stopped; a finished one cannot.
    expect(screen.getAllByRole('button', { name: '취소' })).toHaveLength(2)
  })

  it('cancels through the operation endpoint', async () => {
    const calls = stubOperations([operation({ operationId: 'op-42' })])

    renderPanel()
    await userEvent.click(await screen.findByRole('button', { name: '취소' }))

    await waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].url).toContain('/operations/op-42/cancel')
  })

  it('surfaces the failure message of a failed operation', async () => {
    stubOperations([
      operation({
        state: 'FAILED',
        progress: null,
        errorMessage: 'Local changes would be overwritten',
      }),
    ])

    renderPanel()

    expect(await screen.findByText('실패')).toBeInTheDocument()
    expect(
      screen.getByText('Local changes would be overwritten'),
    ).toBeInTheDocument()
  })

  it('falls back to a generic label for an unnamed operation type', async () => {
    stubOperations([operation({ type: 'SVN_CLEANUP', progress: null })])

    renderPanel()

    expect(await screen.findByText('작업')).toBeInTheDocument()
  })

  it('omits the percentage while progress is indeterminate', async () => {
    stubOperations([
      operation({ progress: { percent: null, phase: 'Counting', detail: null } }),
    ])

    renderPanel()

    expect(await screen.findByText('Counting')).toBeInTheDocument()
    expect(screen.queryByText(/%$/)).not.toBeInTheDocument()
  })

  it('shows the empty state when nothing has run', async () => {
    stubOperations([])

    renderPanel()

    expect(await screen.findByText('진행 중인 작업이 없습니다.')).toBeInTheDocument()
  })

  it('reports a failure with a translated message', async () => {
    stubOperations({ code: 'INTERNAL_ERROR' }, 500)

    renderPanel()

    expect(
      await screen.findByText('작업 목록을 불러오지 못했습니다: 서버 오류가 발생했습니다'),
    ).toBeInTheDocument()
  })
})
