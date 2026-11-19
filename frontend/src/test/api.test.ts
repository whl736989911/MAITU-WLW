import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, errorMessage, request } from '@/lib/api'
import { useSession } from '@/lib/store'
import { management, customer, alarm, response } from './fixtures'

describe('真实API边界与认证', () => {
  beforeEach(() => { useSession.getState().clear(); localStorage.clear(); sessionStorage.clear() })
  it('公开登录发送audience，不持久化Bearer', async () => {
    const fetcher = vi.fn().mockResolvedValue(response(management)); vi.stubGlobal('fetch', fetcher)
    const auth = await api.login('admin', 'not-a-real-password', 'management')
    useSession.getState().login(auth, 'management')
    const [, init] = fetcher.mock.calls[0]
    expect(init.headers.has('Authorization')).toBe(false)
    expect(JSON.parse(init.body)).toEqual({ username: 'admin', password: 'not-a-real-password', audience: 'management' })
    expect(localStorage.length).toBe(0); expect(sessionStorage.length).toBe(0)
  })
  it('聚合读取沿用多单位范围且不改变用户选择', async () => {
    const fetcher = vi.fn().mockImplementation(async () => response({})); vi.stubGlobal('fetch', fetcher)
    useSession.getState().login(management, 'management')
    useSession.getState().setScope(['tenant-a', 'tenant-b'], [])
    await api.summary(undefined, ['tenant-a', 'tenant-b'], [])
    expect(fetcher.mock.calls[0][1].headers.has('X-Tenant-Id')).toBe(false)
    const query = new URL(fetcher.mock.calls[0][0], 'http://localhost').searchParams
    expect(query.get('scope_tenant_ids')).toBe('tenant-a,tenant-b')
    expect(useSession.getState().scopeTenantIds).toEqual(['tenant-a', 'tenant-b'])
    useSession.getState().login(customer, 'customer'); await api.summary()
    expect(fetcher.mock.calls[1][0]).toBe('/api/customer/summary')
    expect(fetcher.mock.calls[1][1].headers.has('X-Tenant-Id')).toBe(false)
  })
  it('401清空当前认证与权限，并包含requestId错误', async () => {
    useSession.getState().login(management, 'management')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ code: 'UNAUTHORIZED', message: '会话过期', request_id: 'req-123' }, 401)))
    const failure = await api.summary().catch(e => e)
    expect(failure).toBeInstanceOf(ApiError); expect(errorMessage(failure)).toContain('req-123')
    expect(useSession.getState()).toMatchObject({ token: null, user: null, permissions: [] })
  })
  it('旧请求的401不得清除更新的会话', async () => {
    useSession.getState().login(management, 'management')
    let resolve!: (value: Response) => void
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => new Promise<Response>(r => { resolve = r })))
    const pending = api.me().catch(e => e)
    useSession.getState().login(customer, 'customer')
    resolve(response({ code: 'UNAUTHORIZED' }, 401)); await pending
    expect(useSession.getState().token).toBe(customer.access_token)
  })
  it('网络中断和HTTP500拒绝，不返回假列表或摘要', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    await expect(request('/health', { public: true })).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ code: 'INTERNAL_ERROR', message: '读取失败', request_id: 'r500' }, 500)))
    await expect(request('/health', { public: true })).rejects.toMatchObject({ status: 500, requestId: 'r500' })
  })
  it('复核提交revision与幂等头，不合成恢复状态；409原样交给UI', async () => {
    useSession.getState().login(customer, 'customer')
    const fetcher = vi.fn().mockResolvedValue(response({ ...alarm, response_state: 'HANDLED', conclusion: 'FALSE_ALARM' })); vi.stubGlobal('fetch', fetcher)
    const result = await api.review(alarm, 'FALSE_ALARM', '现场核查为误报', 'idempotency-test')
    expect(result.physical_state).toBe('FIRE')
    expect(fetcher.mock.calls[0][1].headers.get('Idempotency-Key')).toBe('idempotency-test')
    expect(JSON.parse(fetcher.mock.calls[0][1].body)).toEqual({ expected_revision: 1, conclusion: 'FALSE_ALARM', remark: '现场核查为误报' })
    fetcher.mockResolvedValue(response({ code: 'REVISION_CONFLICT', message: '版本已更新', request_id: 'r409' }, 409))
    await expect(api.review(alarm, 'TEST', '测试', 'other')).rejects.toMatchObject({ status: 409, requestId: 'r409' })
  })
  it('204改密响应可正确读取', async () => {
    useSession.getState().login(management, 'management')
    const fetcher = vi.fn().mockResolvedValue(new Response(null, { status: 204 })); vi.stubGlobal('fetch', fetcher)
    await expect(api.changePassword('current', 'replacement')).resolves.toBeUndefined()
  })
})
