import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider } from '@/shared/i18n'
import { useUiStore } from '@/shared/lib/uiStore'
import { stubRepositoryApi } from '@/shared/test/apiStub'
import { CommitBox } from '@/widgets/CommitBox'

function renderBox() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <CommitBox />
      </I18nProvider>
    </QueryClientProvider>,
  )
}

/** Answers GET /status with `status` and POST /commit with `commitResponse`. */
function stubApi(
  status: unknown,
  commitResponse: { status: number; body: unknown } = {
    status: 200,
    body: { revisionId: '0123456789abcdef' },
  },
) {
  return stubRepositoryApi(status, { mutation: commitResponse })
}

const staged = {
  staged: [{ path: 'a.txt', type: 'ADDED', oldPath: null }],
  unstaged: [],
  conflicted: [],
}

const clean = { staged: [], unstaged: [], conflicted: [] }

const initialUiState = useUiStore.getState()

beforeEach(() => {
  useUiStore.setState(initialUiState, true)
  useUiStore.setState({ route: 'repository', currentRepositoryId: 'repo-1' })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('CommitBox', () => {
  it('commits the staged index and clears the message', async () => {
    const calls = stubApi(staged)

    renderBox()

    const message = screen.getByLabelText('커밋 메시지를 입력하세요')
    await userEvent.type(message, 'feat: add a thing')
    // The button unlocks only once the status query reports a non-empty index.
    const submit = screen.getByRole('button', { name: '커밋' })
    await waitFor(() => expect(submit).toBeEnabled())
    await userEvent.click(submit)

    await waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].url).toContain('/repositories/repo-1/commit')
    expect(calls[0].body).toEqual({
      message: 'feat: add a thing',
      amend: false,
    })
    await waitFor(() => expect(message).toHaveValue(''))
    expect(await screen.findByText('커밋 0123456 생성됨')).toBeInTheDocument()
  })

  it('refuses to commit a blank message', async () => {
    stubApi(staged)

    renderBox()
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '커밋' })).toBeDisabled(),
    )

    await userEvent.type(
      screen.getByLabelText('커밋 메시지를 입력하세요'),
      '   ',
    )

    expect(screen.getByRole('button', { name: '커밋' })).toBeDisabled()
  })

  it('blocks committing when nothing is staged, unless amending', async () => {
    stubApi(clean)

    renderBox()
    await userEvent.type(
      screen.getByLabelText('커밋 메시지를 입력하세요'),
      'reword',
    )

    expect(await screen.findByText('스테이지된 변경사항이 없습니다')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '커밋' })).toBeDisabled()

    await userEvent.click(screen.getByLabelText('이전 커밋 수정(amend)'))

    expect(screen.getByRole('button', { name: '커밋' })).toBeEnabled()
  })

  it('refuses to commit while paths are unmerged, even when staged', async () => {
    stubApi({
      staged: [{ path: 'a.txt', type: 'ADDED', oldPath: null }],
      unstaged: [],
      conflicted: [{ path: 'both-edited.txt', resolution: 'UNRESOLVED' }],
    })

    renderBox()
    await userEvent.type(
      screen.getByLabelText('커밋 메시지를 입력하세요'),
      'feat: try anyway',
    )

    expect(
      await screen.findByText('충돌을 먼저 해결해야 커밋할 수 있습니다'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '커밋' })).toBeDisabled()
  })

  it('sends the amend flag and translates a capability failure', async () => {
    const calls = stubApi(clean, {
      status: 400,
      body: {
        code: 'CAPABILITY_NOT_SUPPORTED',
        title: 'Operation not supported',
        status: 400,
      },
    })

    renderBox()
    await userEvent.type(
      screen.getByLabelText('커밋 메시지를 입력하세요'),
      'reword',
    )
    await userEvent.click(screen.getByLabelText('이전 커밋 수정(amend)'))
    await userEvent.click(screen.getByRole('button', { name: '커밋' }))

    await waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].body).toEqual({ message: 'reword', amend: true })
    expect(
      await screen.findByText(
        '커밋하지 못했습니다: 이 VCS는 해당 기능을 지원하지 않습니다',
      ),
    ).toBeInTheDocument()
  })
})
