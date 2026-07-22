import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider } from '@/shared/i18n'
import { useUiStore } from '@/shared/lib/uiStore'
import { WorkingTreePanel } from '@/widgets/WorkingTreePanel'

function renderPanel() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <WorkingTreePanel />
      </I18nProvider>
    </QueryClientProvider>,
  )
}

interface RecordedCall {
  url: string
  body: unknown
}

/**
 * Answers GET /status with the given payload and 204s every mutation,
 * recording what was posted so the tests can assert on the request itself.
 */
function stubApi(status: unknown): RecordedCall[] {
  const calls: RecordedCall[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') {
        calls.push({
          url: String(input),
          body: init.body ? JSON.parse(String(init.body)) : null,
        })
        return Promise.resolve(new Response(null, { status: 204 }))
      }
      return Promise.resolve(
        new Response(JSON.stringify(status), {
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

describe('WorkingTreePanel', () => {
  it('groups staged and unstaged changes into counted sections', async () => {
    stubApi({
      staged: [{ path: 'staged.txt', type: 'ADDED', oldPath: null }],
      unstaged: [
        { path: 'changed.txt', type: 'MODIFIED', oldPath: null },
        { path: 'new.txt', type: 'UNTRACKED', oldPath: null },
      ],
      conflicted: [],
    })

    renderPanel()

    expect(await screen.findByText('staged.txt')).toBeInTheDocument()
    expect(screen.getByText('changed.txt')).toBeInTheDocument()
    expect(screen.getByText('new.txt')).toBeInTheDocument()
    expect(screen.getByText('스테이지됨 (1)')).toBeInTheDocument()
    expect(screen.getByText('변경사항 (2)')).toBeInTheDocument()
    // Nothing conflicted, so that section must not be rendered at all.
    expect(screen.queryByText(/충돌/)).not.toBeInTheDocument()
  })

  it('surfaces conflicts first', async () => {
    stubApi({
      staged: [],
      unstaged: [],
      conflicted: [{ path: 'both-edited.txt', resolution: 'UNRESOLVED' }],
    })

    renderPanel()

    expect(await screen.findByText('both-edited.txt')).toBeInTheDocument()
    expect(screen.getByText('충돌 (1)')).toBeInTheDocument()
    // The backend enum is translated, never rendered raw.
    expect(screen.getByText('미해결')).toBeInTheDocument()
    expect(screen.queryByText('UNRESOLVED')).not.toBeInTheDocument()
  })

  it('shows the clean state when there are no changes', async () => {
    stubApi({ staged: [], unstaged: [], conflicted: [] })

    renderPanel()

    expect(await screen.findByText('변경사항이 없습니다')).toBeInTheDocument()
  })

  it('asks for a repository instead of spinning forever when none is open', () => {
    stubApi({ staged: [], unstaged: [], conflicted: [] })
    useUiStore.setState({ currentRepositoryId: null })

    renderPanel()

    expect(screen.getByText('저장소를 열어주세요')).toBeInTheDocument()
    expect(screen.queryByText('상태를 불러오는 중…')).not.toBeInTheDocument()
  })

  it('stages a single file through the row action', async () => {
    const calls = stubApi({
      staged: [],
      unstaged: [{ path: 'new.txt', type: 'UNTRACKED', oldPath: null }],
      conflicted: [],
    })

    renderPanel()
    await userEvent.click(
      await screen.findByRole('button', { name: '스테이지: new.txt' }),
    )

    await waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].url).toContain('/repositories/repo-1/stage')
    expect(calls[0].body).toEqual({ paths: ['new.txt'] })
  })

  it('unstages the whole section in one request', async () => {
    const calls = stubApi({
      staged: [
        { path: 'a.txt', type: 'ADDED', oldPath: null },
        { path: 'b.txt', type: 'MODIFIED', oldPath: null },
      ],
      unstaged: [],
      conflicted: [],
    })

    renderPanel()
    await userEvent.click(
      await screen.findByRole('button', { name: '모두 해제' }),
    )

    await waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].url).toContain('/repositories/repo-1/unstage')
    expect(calls[0].body).toEqual({ paths: ['a.txt', 'b.txt'] })
  })
})
