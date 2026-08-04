import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider } from '@/shared/i18n'
import { ProxyPanel } from '@/widgets/ProxyPanel'

function renderPanel() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const result = render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <ProxyPanel />
      </I18nProvider>
    </QueryClientProvider>,
  )
  return { ...result, queryClient }
}

interface RecordedCall {
  url: string
  method: string
  body: unknown
}

function stubProxy(proxy: unknown, status = 200) {
  const calls: RecordedCall[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const method = init?.method ?? 'GET'
      if (method !== 'GET') {
        calls.push({
          url,
          method,
          body: init?.body ? JSON.parse(String(init.body)) : null,
        })
        return Promise.resolve(
          new Response(JSON.stringify({ url: null, bypass: '' }), {
            status: 200,
            headers: { 'content-type': 'application/json' },
          }),
        )
      }
      return Promise.resolve(
        new Response(JSON.stringify(proxy), {
          status,
          headers: { 'content-type': 'application/json' },
        }),
      )
    }),
  )
  return calls
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('ProxyPanel', () => {
  it('shows the not-configured state when no proxy is stored', async () => {
    stubProxy({ url: null, bypass: '' })

    renderPanel()

    expect(await screen.findByText('프록시가 설정되지 않았습니다. 연결은 직접 이루어집니다.')).toBeInTheDocument()
  })

  it('shows the active proxy and pre-fills the form', async () => {
    stubProxy({ url: 'http://proxy.corp:3128', bypass: 'localhost' })

    renderPanel()

    expect(await screen.findByText('http://proxy.corp:3128')).toBeInTheDocument()
    expect(screen.getByLabelText('프록시 URL')).toHaveValue('http://proxy.corp:3128')
    expect(screen.getByLabelText('제외할 호스트')).toHaveValue('localhost')
  })

  it('saves the entered proxy', async () => {
    const calls = stubProxy({ url: null, bypass: '' })

    renderPanel()
    await screen.findByText('프록시가 설정되지 않았습니다. 연결은 직접 이루어집니다.')

    await userEvent.type(screen.getByLabelText('프록시 URL'), 'http://proxy.corp:3128')
    await userEvent.type(screen.getByLabelText('제외할 호스트'), 'localhost')
    await userEvent.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].method).toBe('PUT')
    expect(calls[0].url).toContain('/settings/proxy')
    expect(calls[0].body).toEqual({ url: 'http://proxy.corp:3128', bypass: 'localhost' })
  })

  it('removes the configured proxy', async () => {
    const calls = stubProxy({ url: 'http://proxy.corp:3128', bypass: '' })

    renderPanel()
    await userEvent.click(await screen.findByRole('button', { name: '프록시 사용 안 함' }))

    await waitFor(() => expect(calls).toHaveLength(1))
    expect(calls[0].method).toBe('DELETE')
    expect(calls[0].url).toContain('/settings/proxy')
  })

  it('keeps the save button disabled until a URL is entered', async () => {
    stubProxy({ url: null, bypass: '' })

    renderPanel()

    const save = await screen.findByRole('button', { name: '저장' })
    expect(save).toBeDisabled()

    await userEvent.type(screen.getByLabelText('프록시 URL'), 'http://proxy.corp:3128')
    expect(save).toBeEnabled()
  })

  it('keeps in-progress edits when a background refetch returns the same stored value', async () => {
    stubProxy({ url: 'http://proxy.corp:3128', bypass: '' })

    const { queryClient } = renderPanel()
    await screen.findByText('http://proxy.corp:3128')

    const urlField = screen.getByLabelText('프록시 URL')
    await userEvent.clear(urlField)
    await userEvent.type(urlField, 'http://not-saved-yet.corp:8080')

    // A window refocus or reconnect triggers exactly this: the same query re-runs and
    // resolves to the same server value, but must not stomp the user's unsaved edit.
    await queryClient.refetchQueries({ queryKey: ['proxy'] })

    expect(screen.getByLabelText('프록시 URL')).toHaveValue('http://not-saved-yet.corp:8080')
  })

  it('surfaces a translated error when loading fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify({ code: 'INTERNAL_ERROR', title: 'boom', status: 500 }), {
            status: 500,
            headers: { 'content-type': 'application/problem+json' },
          }),
        ),
      ),
    )

    renderPanel()

    expect(await screen.findByText(/프록시 설정을 불러오지 못했습니다/)).toBeInTheDocument()
  })
})
