import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { App } from '@/app/App'
import { I18nProvider } from '@/shared/i18n'
import { useUiStore } from '@/shared/lib/uiStore'

function renderApp() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <App />
      </I18nProvider>
    </QueryClientProvider>,
  )
}

const initialUiState = useUiStore.getState()

beforeEach(() => {
  useUiStore.setState(initialUiState, true)
  // URL-aware stub: the shell now queries both /health and /repositories,
  // which return different shapes.
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : String(input)
      const body = url.includes('/repositories') ? [] : { status: 'UP' }
      return Promise.resolve(
        new Response(JSON.stringify(body), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
      )
    }),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('layout shell', () => {
  it('renders the repository shell: toolbar, sidebar, center empty state, diff and bottom panel', () => {
    useUiStore.setState({ route: 'repository', bottomPanelCollapsed: false })
    renderApp()

    // TitleBar
    expect(screen.getByText('ConfigFlow')).toBeInTheDocument()

    // Toolbar placeholders, disabled
    for (const label of ['Pull', 'Push', 'Fetch', 'Branch']) {
      expect(screen.getByRole('button', { name: label })).toBeDisabled()
    }

    // Sidebar sections
    expect(screen.getByText('워크스페이스')).toBeInTheDocument()
    expect(screen.getByText('브랜치')).toBeInTheDocument()
    expect(screen.getByText('태그')).toBeInTheDocument()

    // Center empty state (docs/06: "저장소를 열어주세요")
    expect(screen.getByText('저장소를 열어주세요')).toBeInTheDocument()

    // Diff placeholder
    expect(screen.getByText('Diff 뷰어')).toBeInTheDocument()

    // Bottom panel tabs
    expect(screen.getByRole('tab', { name: '콘솔' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '작업' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '로그' })).toBeInTheDocument()
  })

  it('shows only the bottom panel header when collapsed', () => {
    useUiStore.setState({ route: 'repository', bottomPanelCollapsed: true })
    renderApp()

    expect(screen.getByRole('tab', { name: '콘솔' })).toBeInTheDocument()
    expect(
      screen.queryByText('실행된 VCS 명령이 여기에 표시됩니다.'),
    ).not.toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: '패널 펼치기' }),
    ).toHaveAttribute('aria-expanded', 'false')
  })

  it('renders the backend connection indicator in the StatusBar', async () => {
    useUiStore.setState({ route: 'repository' })
    renderApp()

    expect(
      await screen.findByText('백엔드 연결됨', undefined, { timeout: 3000 }),
    ).toBeInTheDocument()
  })

  it('starts on the welcome page and shows the empty grids once the list loads', async () => {
    renderApp()

    // Header renders immediately; the grids appear after the query resolves.
    expect(screen.getByText('시작하기')).toBeInTheDocument()
    expect(
      await screen.findByText('즐겨찾기가 없습니다', undefined, {
        timeout: 3000,
      }),
    ).toBeInTheDocument()
    expect(screen.getByText('최근 연 저장소가 없습니다')).toBeInTheDocument()
  })
})
