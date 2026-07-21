import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider } from '@/shared/i18n'
import { useUiStore } from '@/shared/lib/uiStore'
import { WorkingTreePanel } from '@/widgets/WorkingTreePanel'

function renderPanel() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <WorkingTreePanel />
      </I18nProvider>
    </QueryClientProvider>,
  )
}

/** Answers the status endpoint with the given payload. */
function stubStatus(status: unknown) {
  vi.stubGlobal(
    'fetch',
    vi.fn(() =>
      Promise.resolve(
        new Response(JSON.stringify(status), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
      ),
    ),
  )
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
    stubStatus({
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
    stubStatus({
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
    stubStatus({ staged: [], unstaged: [], conflicted: [] })

    renderPanel()

    expect(await screen.findByText('변경사항이 없습니다')).toBeInTheDocument()
  })

  it('asks for a repository instead of spinning forever when none is open', () => {
    stubStatus({ staged: [], unstaged: [], conflicted: [] })
    useUiStore.setState({ currentRepositoryId: null })

    renderPanel()

    expect(screen.getByText('저장소를 열어주세요')).toBeInTheDocument()
    expect(screen.queryByText('상태를 불러오는 중…')).not.toBeInTheDocument()
  })
})
