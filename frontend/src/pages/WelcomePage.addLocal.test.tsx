import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider } from '@/shared/i18n'
import { useUiStore } from '@/shared/lib/uiStore'
import { WelcomePage } from '@/pages/WelcomePage'

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <WelcomePage />
      </I18nProvider>
    </QueryClientProvider>,
  )
}

interface RecordedCall {
  url: string
  body: unknown
}

/** Empty repository list on GET; records the register POST. */
function stubApi(registerStatus = 200): RecordedCall[] {
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
              registerStatus === 200
                ? {
                    id: 'repo-1',
                    name: 'work',
                    localPath: 'C:/dev/work',
                    vcsType: 'GIT',
                    favorite: false,
                  }
                : { code: 'VALIDATION_ERROR' },
            ),
            {
              status: registerStatus,
              headers: { 'content-type': 'application/json' },
            },
          ),
        )
      }
      return Promise.resolve(
        new Response('[]', {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
      )
    }),
  )
  return calls
}

/** Installs the Electron preload bridge, or removes it to simulate a browser. */
function withNativePicker(result: string | null) {
  const selectDirectory = vi.fn(() => Promise.resolve(result))
  vi.stubGlobal('configflowNative', {
    selectDirectory,
    openExternal: vi.fn(),
    showItemInFolder: vi.fn(),
  })
  return selectDirectory
}

const initialUiState = useUiStore.getState()

beforeEach(() => {
  useUiStore.setState(initialUiState, true)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('WelcomePage · adding a local repository', () => {
  it('opens the OS picker and registers what was chosen', async () => {
    const calls = stubApi()
    const picker = withNativePicker('C:\\dev\\work')

    renderPage()
    await userEvent.click(screen.getByRole('button', { name: '로컬 추가' }))

    expect(picker).toHaveBeenCalledOnce()
    await waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].body).toEqual({ localPath: 'C:\\dev\\work' })
    // One click, not two: no form should appear when the picker did the job.
    expect(
      screen.queryByLabelText(/저장소 폴더 경로/),
    ).not.toBeInTheDocument()
  })

  it('does nothing when the picker is cancelled', async () => {
    const calls = stubApi()
    withNativePicker(null)

    renderPage()
    await userEvent.click(screen.getByRole('button', { name: '로컬 추가' }))

    await waitFor(() => expect(calls).toHaveLength(0))
    expect(screen.queryByLabelText(/저장소 폴더 경로/)).not.toBeInTheDocument()
  })

  it('falls back to typing a path when there is no picker', async () => {
    const calls = stubApi()
    // No configflowNative: this is the app running in a plain browser, where no
    // API can hand back a real filesystem path.

    renderPage()
    await userEvent.click(screen.getByRole('button', { name: '로컬 추가' }))

    const input = await screen.findByLabelText(/저장소 폴더 경로/)
    await userEvent.type(input, 'C:/dev/work')
    await userEvent.click(screen.getByRole('button', { name: '등록' }))

    await waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].body).toEqual({ localPath: 'C:/dev/work' })
  })

  it('explains why the path has to be typed in a browser', async () => {
    stubApi()

    renderPage()
    await userEvent.click(screen.getByRole('button', { name: '로컬 추가' }))

    expect(await screen.findByText(/폴더 선택창을 열 수 없어/)).toBeInTheDocument()
  })

  it('reports a rejected folder even though there is no form to attach it to', async () => {
    stubApi(400)
    withNativePicker('C:\\not-a-repo')

    renderPage()
    await userEvent.click(screen.getByRole('button', { name: '로컬 추가' }))

    expect(await screen.findByText(/등록 실패/)).toBeInTheDocument()
  })
})
