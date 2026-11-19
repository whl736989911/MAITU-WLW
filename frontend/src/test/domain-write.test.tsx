import { beforeEach, expect, it, vi } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { ApiError } from '@/lib/api'
import { useDomainWrite } from '@/lib/domain-write'

beforeEach(() => vi.restoreAllMocks())

it('结果未知时阻止替换操作；同一身份重试沿原键，成功后才能开始新操作', async () => {
  const calls: Array<{ key: string; resolve: () => void; reject: (error: Error) => void }> = []
  const action = vi.fn((key: string) => new Promise<void>((resolve, reject) => calls.push({ key, resolve, reject })))
  const { result } = renderHook(() => useDomainWrite())
  act(() => { result.current.setReason('维护资源') })
  act(() => result.current.submit(['resource.update', { name: '原名称'}], action))
  expect(calls).toHaveLength(1)
  await act(async () => calls[0].reject(new TypeError('Response lost')))
  await waitFor(() => expect(result.current.saving).toBe(false))

  act(() => result.current.submit(['resource.update', { name: '其他名称'}], action))
  expect(calls).toHaveLength(1)
  expect(result.current.error).toBeInstanceOf(Error)
  act(() => result.current.submit(['resource.update', { name: '原名称'}], action))
  expect(calls).toHaveLength(2)
  expect(calls[1].key).toBe(calls[0].key)
  await act(async () => calls[1].resolve())
  await waitFor(() => expect(result.current.saving).toBe(false))

  act(() => result.current.submit(['resource.update', { name: '其他名称'}], action))
  expect(calls).toHaveLength(3)
  expect(calls[2].key).not.toBe(calls[0].key)
  await act(async () => calls[2].resolve())
})

it('服务端明确拒绝且未执行时，允许改正输入后以新键提交', async () => {
  const calls: Array<{ key: string; resolve: () => void; reject: (error: Error) => void }> = []
  const action = vi.fn((key: string) => new Promise<void>((resolve, reject) => calls.push({ key, resolve, reject })))
  const { result } = renderHook(() => useDomainWrite())
  act(() => { result.current.setReason('维护资源') })
  act(() => result.current.submit(['resource.update', { name: '' }], action))
  await act(async () => calls[0].reject(new ApiError(400, 'INVALID_REQUEST', 'Name required')))
  await waitFor(() => expect(result.current.saving).toBe(false))
  act(() => result.current.submit(['resource.update', { name: '有效名称' }], action))
  expect(calls).toHaveLength(2)
  expect(calls[1].key).not.toBe(calls[0].key)
  await act(async () => calls[1].resolve())
})
