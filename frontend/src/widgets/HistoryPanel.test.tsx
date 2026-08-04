import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider } from '@/shared/i18n'
import { useUiStore } from '@/shared/lib/uiStore'
import { ConfirmModal } from '@/widgets/ConfirmModal'
import { HistoryPanel } from '@/widgets/HistoryPanel'

function renderPanel() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <HistoryPanel />
        <ConfirmModal />
      </I18nProvider>
    </QueryClientProvider>,
  )
}

function revision(id: string, message: string, extra: object = {}) {
  return {
    id,
    parents: [],
    author: { name: 'Alice', email: 'alice@configflow.dev' },
    timestamp: '2026-01-15T09:30:00Z',
    message,
    labels: [],
    ...extra,
  }
}

/** Records every history request URL and answers with the queued pages. */
function stubHistory(pages: unknown[]) {
  const urls: string[] = []
  let call = 0
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL) => {
      urls.push(String(input))
      const page = pages[Math.min(call, pages.length - 1)]
      call += 1
      return Promise.resolve(
        new Response(JSON.stringify(page), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
      )
    }),
  )
  return urls
}

/** Answers GET history requests with one page and records every POST. */
function stubHistoryAndPost(items: unknown[], postStatus = 202) {
  const calls: { url: string; body: unknown }[] = []
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
                ? { operationId: 'op-1', type: 'CHERRY_PICK', state: 'QUEUED' }
                : { code: 'CONFLICT' },
            ),
            { status: postStatus, headers: { 'content-type': 'application/json' } },
          ),
        )
      }
      return Promise.resolve(
        new Response(JSON.stringify({ items, nextCursor: null }), {
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

describe('HistoryPanel', () => {
  it('lists commits with their subject, short id and ref labels', async () => {
    stubHistory([
      {
        items: [
          revision('0123456789abcdef0123', 'feat: add a thing\n\nlong body', {
            labels: [
              { kind: 'HEAD', name: 'HEAD' },
              { kind: 'BRANCH', name: 'main' },
            ],
          }),
          revision('fedcba9876543210fedc', 'chore: seed'),
        ],
        nextCursor: null,
      },
    ])

    renderPanel()

    // Only the subject line is listed; the body stays in the title attribute.
    expect(await screen.findByText('feat: add a thing')).toBeInTheDocument()
    expect(screen.queryByText(/long body/)).not.toBeInTheDocument()
    expect(screen.getByText('0123456')).toBeInTheDocument()
    expect(screen.getByText('chore: seed')).toBeInTheDocument()
    expect(screen.getByText('HEAD')).toBeInTheDocument()
    expect(screen.getByText('main')).toBeInTheDocument()
  })

  it('shows the empty state when nothing matches', async () => {
    stubHistory([{ items: [], nextCursor: null }])

    renderPanel()

    expect(await screen.findByText('커밋이 없습니다')).toBeInTheDocument()
  })

  it('fetches the next page with the returned cursor', async () => {
    const urls = stubHistory([
      { items: [revision('aaa1', 'first page')], nextCursor: 'cursor-2' },
      { items: [revision('bbb2', 'second page')], nextCursor: null },
    ])

    renderPanel()
    await userEvent.click(await screen.findByRole('button', { name: '더 보기' }))

    expect(await screen.findByText('second page')).toBeInTheDocument()
    // The first page stays rendered above the newly appended one.
    expect(screen.getByText('first page')).toBeInTheDocument()
    const historyUrls = urls.filter((url) => url.includes('/history'))
    expect(historyUrls[0]).not.toContain('cursor=')
    expect(historyUrls[1]).toContain('cursor=cursor-2')
    expect(screen.queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument()
  })

  it('applies filters only on submit, and drops blank ones', async () => {
    const urls = stubHistory([{ items: [], nextCursor: null }])
    const historyUrls = () => urls.filter((url) => url.includes('/history'))

    renderPanel()
    await waitFor(() => expect(historyUrls()).toHaveLength(1))

    await userEvent.type(screen.getByLabelText('작성자'), 'alice')
    // Typing alone must not refire the query.
    expect(historyUrls()).toHaveLength(1)

    await userEvent.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() => expect(historyUrls()).toHaveLength(2))
    expect(historyUrls()[1]).toContain('author=alice')
    // The message box was left empty, so it must not narrow the query.
    expect(historyUrls()[1]).not.toContain('message=')
  })

  it('clears the applied filters', async () => {
    const urls = stubHistory([{ items: [], nextCursor: null }])

    renderPanel()
    await userEvent.type(screen.getByLabelText('메시지'), 'fix')
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await waitFor(() => expect(urls.at(-1)).toContain('message=fix'))

    await userEvent.click(screen.getByRole('button', { name: '초기화' }))

    await waitFor(() => expect(urls.at(-1)).not.toContain('message='))
    expect(screen.getByLabelText('메시지')).toHaveValue('')
  })

  it('reports a failure with a translated message', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify({ code: 'CAPABILITY_NOT_SUPPORTED' }), {
            status: 400,
            headers: { 'content-type': 'application/json' },
          }),
        ),
      ),
    )

    renderPanel()

    expect(
      await screen.findByText(
        '히스토리를 불러오지 못했습니다: 이 VCS는 해당 기능을 지원하지 않습니다',
      ),
    ).toBeInTheDocument()
  })

  it('cherry-picks a commit onto the current branch from its context menu', async () => {
    const calls = stubHistoryAndPost([revision('abc1234567890abc1234', 'feat: topic commit')])

    renderPanel()
    fireEvent.contextMenu(await screen.findByText('feat: topic commit'))
    await userEvent.click(await screen.findByRole('menuitem', { name: '체리픽' }))
    await userEvent.click(await screen.findByRole('button', { name: '확인' }))

    await waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].url).toContain('/repositories/repo-1/cherry-pick')
    expect(calls[0].body).toEqual({ revisions: ['abc1234567890abc1234'] })
  })

  it('reports a failed cherry-pick with a translated message', async () => {
    stubHistoryAndPost([revision('abc1234567890abc1234', 'feat: topic commit')], 409)

    renderPanel()
    fireEvent.contextMenu(await screen.findByText('feat: topic commit'))
    await userEvent.click(await screen.findByRole('menuitem', { name: '체리픽' }))
    await userEvent.click(await screen.findByRole('button', { name: '확인' }))

    expect(await screen.findByText(/체리픽하지 못했습니다/)).toBeInTheDocument()
  })

  it('asks for a repository when none is open', () => {
    stubHistory([{ items: [], nextCursor: null }])
    useUiStore.setState({ currentRepositoryId: null })

    const { container } = renderPanel()

    expect(screen.getByText('저장소를 열어주세요')).toBeInTheDocument()
    expect(within(container).queryByLabelText('작성자')).not.toBeInTheDocument()
  })
})
