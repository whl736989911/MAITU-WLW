import { beforeEach, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { CustomerWriteGate } from '@/components/customer-asset-write'
import { createMutationIntent } from '@/lib/mutation-intent'
import { useSession } from '@/lib/store'
import { customer } from './fixtures'

beforeEach(() => {
  useSession.getState().clear(); localStorage.clear(); sessionStorage.clear()
  useSession.getState().login({ ...customer, permissions: ['units:read', 'units:write'] }, 'customer')
})

it('结果未确认时沿原意图与幂等键重试，不重建请求', async () => {
  const failures: Array<(error: Error) => void> = []
  const fetchMock = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) => {
    const { promise, reject } = Promise.withResolvers<Response>()
    failures.push(error => reject(error))
    return promise
  })
  vi.stubGlobal('fetch', fetchMock)
  const store = createMutationIntent<{ id: string }>(raw => JSON.parse(raw))
  render(<CustomerWriteGate store={store} title="新建位置" warning="位置写入" submitLabel="创建位置" summary={<p>位置资料</p>} buildRequest={() => ({ path: '/places', method: 'POST', body: { name: '回归位置', parent_place_id: null }, display: '新建位置' })} onSaved={() => {}}><div/></CustomerWriteGate>)

  await userEvent.click(screen.getByRole('button', { name: '创建位置' }))
  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
  await act(async () => failures[0](new TypeError('Connection lost before response')))
  const retry = await screen.findByRole('button', { name: '用原请求确认结果' })
  expect(retry).toBeEnabled()
  const originalKey = store.getState().key
  expect(originalKey).not.toBeNull()
  expect(store.getState().intent?.body).toEqual({ name: '回归位置', parent_place_id: null })

  await userEvent.click(retry)
  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
  expect(store.getState().key).toBe(originalKey)
  const first = fetchMock.mock.calls[0][1]!
  const second = fetchMock.mock.calls[1][1]!
  expect(new Headers(first.headers).get('Idempotency-Key')).toBe(originalKey)
  expect(new Headers(second.headers).get('Idempotency-Key')).toBe(originalKey)
  expect(JSON.parse(second.body as string)).toEqual(JSON.parse(first.body as string))
  expect(second.body).toBe(first.body)
})
