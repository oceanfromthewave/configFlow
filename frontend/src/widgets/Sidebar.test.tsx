import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider } from '@/shared/i18n'
import { useUiStore } from '@/shared/lib/uiStore'
import { Sidebar } from '@/widgets/Sidebar'

function renderSidebar() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <Sidebar />
      </I18nProvider>
    </QueryClientProvider>,
  )
}

function stubRefs(refs: unknown, status = 200) {
  vi.stubGlobal(
    'fetch',
    vi.fn(() =>
      Promise.resolve(
        new Response(JSON.stringify(status === 200 ? { refs } : refs), {
          status,
          headers: { 'content-type': 'application/json' },
        }),
      ),
    ),
  )
}

/** The section list that follows the heading with the given text. */
function sectionOf(title: string) {
  return screen.getByRole('heading', { name: title }).closest('section')!
}

const initialUiState = useUiStore.getState()

beforeEach(() => {
  useUiStore.setState(initialUiState, true)
  useUiStore.setState({ route: 'repository', currentRepositoryId: 'repo-1' })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('Sidebar', () => {
  it('splits refs into local, remote and tag sections', async () => {
    stubRefs([
      { kind: 'HEAD', name: 'main' },
      { kind: 'BRANCH', name: 'main' },
      { kind: 'BRANCH', name: 'feature/x' },
      { kind: 'REMOTE_BRANCH', name: 'origin/main' },
      { kind: 'TAG', name: 'v1.0' },
    ])

    renderSidebar()
    expect(await screen.findByText('feature/x')).toBeInTheDocument()

    expect(within(sectionOf('브랜치')).getByText('main')).toBeInTheDocument()
    expect(within(sectionOf('원격')).getByText('origin/main')).toBeInTheDocument()
    expect(within(sectionOf('태그')).getByText('v1.0')).toBeInTheDocument()
    // A branch must not leak into the remote or tag lists.
    expect(within(sectionOf('원격')).queryByText('feature/x')).not.toBeInTheDocument()
  })

  it('marks the branch HEAD points at, and only that one', async () => {
    stubRefs([
      { kind: 'HEAD', name: 'feature/x' },
      { kind: 'BRANCH', name: 'main' },
      { kind: 'BRANCH', name: 'feature/x' },
    ])

    renderSidebar()
    const current = await screen.findByLabelText('현재 브랜치')

    expect(current.closest('[aria-current]')).toHaveTextContent('feature/x')
    expect(screen.getAllByLabelText('현재 브랜치')).toHaveLength(1)
  })

  it('reports a detached head instead of marking a branch', async () => {
    stubRefs([
      { kind: 'HEAD', name: '0123456789abcdef0123456789abcdef01234567' },
      { kind: 'BRANCH', name: 'main' },
    ])

    renderSidebar()

    // The id matches no branch, so nothing may be flagged as current.
    expect(await screen.findByText(/분리된 HEAD/)).toHaveTextContent('0123456')
    expect(screen.queryByLabelText('현재 브랜치')).not.toBeInTheDocument()
  })

  it('shows the empty state for a repository without commits', async () => {
    stubRefs([])

    renderSidebar()

    expect(await screen.findAllByText('항목이 없습니다')).toHaveLength(3)
  })

  it('asks for a repository before fetching anything', () => {
    stubRefs([])
    useUiStore.setState({ currentRepositoryId: null })

    renderSidebar()

    expect(screen.getAllByText('저장소를 열면 브랜치가 표시됩니다')).toHaveLength(3)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('reports a failure without hiding the workspace links', async () => {
    stubRefs({ code: 'INTERNAL_ERROR' }, 500)

    renderSidebar()

    expect(await screen.findAllByText('브랜치를 불러오지 못했습니다')).toHaveLength(3)
    expect(screen.getByText('파일 상태')).toBeInTheDocument()
  })
})
