import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider } from '@/shared/i18n'
import { useUiStore } from '@/shared/lib/uiStore'
import { DiffPanel } from '@/widgets/DiffPanel'

function renderPanel() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <DiffPanel />
      </I18nProvider>
    </QueryClientProvider>,
  )
}

/** Answers the diff endpoint and records the URLs that were requested. */
function stubDiff(body: unknown, status = 200) {
  const urls: string[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL) => {
      urls.push(String(input))
      return Promise.resolve(
        new Response(JSON.stringify(body), {
          status,
          headers: { 'content-type': 'application/json' },
        }),
      )
    }),
  )
  return urls
}

const modified = {
  path: 'src/app.ts',
  oldPath: null,
  type: 'MODIFIED',
  binary: false,
  hunks: [
    {
      oldStart: 10,
      oldCount: 3,
      newStart: 10,
      newCount: 4,
      lines: [' keep me', '-old line', '+new line', '+extra line'],
    },
  ],
}

const initialUiState = useUiStore.getState()

beforeEach(() => {
  useUiStore.setState(initialUiState, true)
  useUiStore.setState({ route: 'repository', currentRepositoryId: 'repo-1' })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('DiffPanel', () => {
  it('invites a selection when no file is chosen', () => {
    stubDiff(modified)

    renderPanel()

    expect(screen.getByText('Diff 뷰어')).toBeInTheDocument()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('renders hunk lines with independent old and new line numbers', async () => {
    useUiStore.setState({ selectedFile: { path: 'src/app.ts', staged: false } })
    stubDiff(modified)

    renderPanel()

    expect(await screen.findByText('-old line')).toBeInTheDocument()
    const removed = screen.getByText('-old line').closest('tr')!
    const added = screen.getByText('+new line').closest('tr')!
    // Testing Library trims text, so the leading context space is gone here.
    const context = screen.getByText('keep me').closest('tr')!

    // Context advances both sides; a removal only the old side; an addition only the new.
    expect(within(context).getAllByRole('cell')[0]).toHaveTextContent('10')
    expect(within(context).getAllByRole('cell')[1]).toHaveTextContent('10')
    expect(within(removed).getAllByRole('cell')[0]).toHaveTextContent('11')
    expect(within(removed).getAllByRole('cell')[1]).toBeEmptyDOMElement()
    expect(within(added).getAllByRole('cell')[0]).toBeEmptyDOMElement()
    expect(within(added).getAllByRole('cell')[1]).toHaveTextContent('11')
  })

  it('summarises the added and removed line counts', async () => {
    useUiStore.setState({ selectedFile: { path: 'src/app.ts', staged: false } })
    stubDiff(modified)

    renderPanel()

    expect(await screen.findByText('+2 −1')).toBeInTheDocument()
  })

  it('requests the staged side when the selection is staged', async () => {
    useUiStore.setState({ selectedFile: { path: 'src/app.ts', staged: true } })
    const urls = stubDiff(modified)

    renderPanel()

    await waitFor(() => expect(urls).toHaveLength(1))
    expect(urls[0]).toContain('path=src%2Fapp.ts')
    expect(urls[0]).toContain('staged=true')
    expect(screen.getByText('스테이지됨 · HEAD 대비')).toBeInTheDocument()
  })

  it('refetches when the selected side changes', async () => {
    useUiStore.setState({ selectedFile: { path: 'src/app.ts', staged: false } })
    const urls = stubDiff(modified)

    renderPanel()
    await waitFor(() => expect(urls).toHaveLength(1))

    // The same path has two different diffs, so the side must be part of the key.
    useUiStore.setState({ selectedFile: { path: 'src/app.ts', staged: true } })

    await waitFor(() => expect(urls).toHaveLength(2))
    expect(urls[1]).toContain('staged=true')
  })

  it('explains a binary file instead of rendering it', async () => {
    useUiStore.setState({ selectedFile: { path: 'logo.png', staged: true } })
    stubDiff({
      path: 'logo.png',
      oldPath: null,
      type: 'ADDED',
      binary: true,
      hunks: [],
    })

    renderPanel()

    expect(await screen.findByText('바이너리 파일')).toBeInTheDocument()
  })

  it('explains an unchanged file', async () => {
    useUiStore.setState({ selectedFile: { path: 'src/app.ts', staged: true } })
    stubDiff({
      path: 'src/app.ts',
      oldPath: null,
      type: 'MODIFIED',
      binary: false,
      hunks: [],
    })

    renderPanel()

    expect(await screen.findByText('변경 내용이 없습니다')).toBeInTheDocument()
  })

  it('reports a failure with a translated message', async () => {
    useUiStore.setState({ selectedFile: { path: 'src/app.ts', staged: false } })
    stubDiff({ code: 'NOT_FOUND' }, 404)

    renderPanel()

    expect(
      await screen.findByText('차이를 불러오지 못했습니다: 찾을 수 없습니다'),
    ).toBeInTheDocument()
  })
})
