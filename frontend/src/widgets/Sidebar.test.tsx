import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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

interface RecordedCall {
  url: string
  body: unknown
}

/** Answers GET /refs and records every checkout POST. */
function stubRefs(refs: unknown, status = 200, postStatus = 202) {
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
                ? { operationId: 'op-1', type: 'CHECKOUT', state: 'QUEUED' }
                : { code: 'CONFLICT' },
            ),
            { status: postStatus, headers: { 'content-type': 'application/json' } },
          ),
        )
      }
      return Promise.resolve(
        new Response(JSON.stringify(status === 200 ? { refs } : refs), {
          status,
          headers: { 'content-type': 'application/json' },
        }),
      )
    }),
  )
  return calls
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

  it('checks out a branch when its row is clicked', async () => {
    const calls = stubRefs([
      { kind: 'HEAD', name: 'main' },
      { kind: 'BRANCH', name: 'main' },
      { kind: 'BRANCH', name: 'feature/x' },
    ])

    renderSidebar()
    await userEvent.click(
      await screen.findByRole('button', { name: '체크아웃: feature/x' }),
    )

    await waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].url).toContain('/repositories/repo-1/checkout')
    expect(calls[0].body).toEqual({ ref: 'feature/x' })
  })

  it('does not offer to check out the branch already in use', async () => {
    stubRefs([
      { kind: 'HEAD', name: 'main' },
      { kind: 'BRANCH', name: 'main' },
    ])

    renderSidebar()
    await screen.findByText('main')

    expect(
      screen.queryByRole('button', { name: '체크아웃: main' }),
    ).not.toBeInTheDocument()
  })

  it('does not offer checkout for remote branches or tags', async () => {
    stubRefs([
      { kind: 'HEAD', name: 'main' },
      { kind: 'BRANCH', name: 'main' },
      { kind: 'REMOTE_BRANCH', name: 'origin/main' },
      { kind: 'TAG', name: 'v1.0' },
    ])

    renderSidebar()
    await screen.findByText('origin/main')

    // Checking out either detaches the head, which needs its own confirmation.
    expect(
      screen.queryByRole('button', { name: '체크아웃: origin/main' }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '체크아웃: v1.0' }),
    ).not.toBeInTheDocument()
  })

  it('reports a blocked checkout with a translated message', async () => {
    stubRefs(
      [
        { kind: 'HEAD', name: 'main' },
        { kind: 'BRANCH', name: 'main' },
        { kind: 'BRANCH', name: 'feature/x' },
      ],
      200,
      409,
    )

    renderSidebar()
    await userEvent.click(
      await screen.findByRole('button', { name: '체크아웃: feature/x' }),
    )

    expect(await screen.findByText(/체크아웃하지 못했습니다/)).toBeInTheDocument()
  })

  it('merges a branch into the current one from its context menu', async () => {
    const calls = stubRefs([
      { kind: 'HEAD', name: 'main' },
      { kind: 'BRANCH', name: 'main' },
      { kind: 'BRANCH', name: 'feature/x' },
    ])

    renderSidebar()
    fireEvent.contextMenu(
      await screen.findByRole('button', { name: '체크아웃: feature/x' }),
    )
    await userEvent.click(
      await screen.findByRole('menuitem', { name: /feature\/x/ }),
    )

    await waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].url).toContain('/repositories/repo-1/merge')
    expect(calls[0].body).toEqual({ source: 'feature/x' })
  })

  it('does not offer to merge the current branch into itself', async () => {
    stubRefs([
      { kind: 'HEAD', name: 'main' },
      { kind: 'BRANCH', name: 'main' },
      { kind: 'BRANCH', name: 'feature/x' },
    ])

    renderSidebar()
    // Wait for the list before touching it: until refs load the section is a spinner.
    await screen.findByRole('button', { name: '체크아웃: feature/x' })

    // The current branch renders as a plain row, so right-clicking it opens nothing.
    fireEvent.contextMenu(within(sectionOf('브랜치')).getByText('main'))

    expect(screen.queryByRole('menuitem')).not.toBeInTheDocument()
  })

  it('reports a failed merge with a translated message', async () => {
    stubRefs(
      [
        { kind: 'HEAD', name: 'main' },
        { kind: 'BRANCH', name: 'main' },
        { kind: 'BRANCH', name: 'feature/x' },
      ],
      200,
      409,
    )

    renderSidebar()
    fireEvent.contextMenu(
      await screen.findByRole('button', { name: '체크아웃: feature/x' }),
    )
    await userEvent.click(
      await screen.findByRole('menuitem', { name: /feature\/x/ }),
    )

    expect(await screen.findByText(/병합하지 못했습니다/)).toBeInTheDocument()
  })

  it('reports a failure without hiding the workspace links', async () => {
    stubRefs({ code: 'INTERNAL_ERROR' }, 500)

    renderSidebar()

    expect(await screen.findAllByText('브랜치를 불러오지 못했습니다')).toHaveLength(3)
    expect(screen.getByText('파일 상태')).toBeInTheDocument()
  })
})
