import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { repositoryNameFromUrl } from '@/entities/repository/api/repositories'
import { I18nProvider } from '@/shared/i18n'
import { CloneDialog } from '@/widgets/CloneDialog'

function renderDialog(onClose = vi.fn()) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <CloneDialog onClose={onClose} />
      </I18nProvider>
    </QueryClientProvider>,
  )
  return onClose
}

interface RecordedCall {
  url: string
  body: unknown
}

function stubApi(status = 202): RecordedCall[] {
  const calls: RecordedCall[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({
        url: String(input),
        body: init?.body ? JSON.parse(String(init.body)) : null,
      })
      return Promise.resolve(
        new Response(
          JSON.stringify(
            status === 202
              ? { operationId: 'op-1', type: 'CLONE', state: 'QUEUED' }
              : { code: 'VALIDATION_ERROR' },
          ),
          { status, headers: { 'content-type': 'application/json' } },
        ),
      )
    }),
  )
  return calls
}

function withNativePicker(result: string | null) {
  vi.stubGlobal('configflowNative', {
    selectDirectory: vi.fn(() => Promise.resolve(result)),
    openExternal: vi.fn(),
    showItemInFolder: vi.fn(),
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('repositoryNameFromUrl', () => {
  it('derives the directory git would create', () => {
    expect(repositoryNameFromUrl('https://github.com/owner/repo.git')).toBe('repo')
    expect(repositoryNameFromUrl('https://github.com/owner/repo')).toBe('repo')
    // scp-style, where the separator before the path is a colon.
    expect(repositoryNameFromUrl('git@github.com:owner/repo.git')).toBe('repo')
    expect(repositoryNameFromUrl('https://host/owner/repo/')).toBe('repo')
  })

  it('drops a query or fragment instead of putting it in a folder name', () => {
    // `?` and `#` are not legal in a Windows path, and they also hide the `.git`
    // suffix from the stripper, so the folder would end up named `repo.git?ref=main`.
    expect(repositoryNameFromUrl('https://host/owner/repo.git?ref=main')).toBe('repo')
    expect(repositoryNameFromUrl('https://host/owner/repo#readme')).toBe('repo')
    expect(repositoryNameFromUrl('https://host/owner/repo/?ref=main')).toBe('repo')
  })

  it('gives up rather than guessing', () => {
    expect(repositoryNameFromUrl('')).toBeNull()
    expect(repositoryNameFromUrl('   ')).toBeNull()
    expect(repositoryNameFromUrl('.git')).toBeNull()
    expect(repositoryNameFromUrl('?ref=main')).toBeNull()
  })
})

describe('CloneDialog', () => {
  it('shows the path it would clone into before committing to it', async () => {
    stubApi()

    renderDialog()
    await userEvent.type(
      screen.getByLabelText('원격 저장소 URL'),
      'https://github.com/owner/repo.git',
    )
    await userEvent.type(screen.getByLabelText('받을 위치'), 'C:\\dev')

    // Getting this wrong is easy and expensive to undo, so it is shown, not assumed.
    expect(screen.getByText(/복제될 경로: C:\\dev\\repo/)).toBeInTheDocument()
  })

  it('sends the derived target', async () => {
    const calls = stubApi()

    renderDialog()
    await userEvent.type(
      screen.getByLabelText('원격 저장소 URL'),
      'https://github.com/owner/repo.git',
    )
    await userEvent.type(screen.getByLabelText('받을 위치'), 'C:\\dev')
    await userEvent.click(screen.getByRole('button', { name: '복제' }))

    await waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].url).toContain('/repositories/clone')
    expect(calls[0].body).toEqual({
      url: 'https://github.com/owner/repo.git',
      localPath: 'C:\\dev\\repo',
    })
  })

  it('stays disabled until both halves of the target are known', async () => {
    stubApi()

    renderDialog()
    expect(screen.getByRole('button', { name: '복제' })).toBeDisabled()

    await userEvent.type(screen.getByLabelText('원격 저장소 URL'), 'https://h/o/r.git')
    expect(screen.getByRole('button', { name: '복제' })).toBeDisabled()

    await userEvent.type(screen.getByLabelText('받을 위치'), '/home/me')
    expect(screen.getByRole('button', { name: '복제' })).toBeEnabled()
  })

  it('fills the parent from the OS picker', async () => {
    stubApi()
    withNativePicker('D:\\projects')

    renderDialog()
    await userEvent.click(screen.getByRole('button', { name: '찾아보기' }))

    await waitFor(() =>
      expect(screen.getByLabelText('받을 위치')).toHaveValue('D:\\projects'),
    )
  })

  it('offers no picker in a browser, where none can work', () => {
    stubApi()

    renderDialog()

    expect(screen.queryByRole('button', { name: '찾아보기' })).not.toBeInTheDocument()
  })

  it('closes once the clone is queued, since the rest happens in the background', async () => {
    stubApi()
    const onClose = renderDialog()

    await userEvent.type(screen.getByLabelText('원격 저장소 URL'), 'https://h/o/r.git')
    await userEvent.type(screen.getByLabelText('받을 위치'), '/home/me')
    await userEvent.click(screen.getByRole('button', { name: '복제' }))

    await waitFor(() => expect(onClose).toHaveBeenCalled())
  })

  it('stays open and explains when the request is rejected', async () => {
    stubApi(400)
    const onClose = renderDialog()

    await userEvent.type(screen.getByLabelText('원격 저장소 URL'), 'https://h/o/r.git')
    await userEvent.type(screen.getByLabelText('받을 위치'), '/home/me')
    await userEvent.click(screen.getByRole('button', { name: '복제' }))

    expect(await screen.findByText(/복제를 시작하지 못했습니다/)).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
  })
})
