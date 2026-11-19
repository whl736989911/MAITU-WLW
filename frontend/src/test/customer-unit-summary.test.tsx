import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { CustomerUnitSummary } from '@/customer/unit-summary'
import { customerAssetsApi } from '@/lib/customer-assets'
import { useSession } from '@/lib/store'
import type { CustomerUnit } from '@/lib/customer-assets'
import { customer } from './fixtures'

const unit: CustomerUnit = { id: 'tenant-a', name: '蓝湾中心', places: [
  { id: 'p1', tenant_id: 'tenant-a', tenant_name: '蓝湾中心', name: '西楼', parent_id: null, legacy_place_id: null, device_count: 0, level: 1, revision: 1 },
  { id: 'p2', tenant_id: 'tenant-a', tenant_name: '蓝湾中心', name: '东楼', parent_id: null, legacy_place_id: null, device_count: 0, level: 1, revision: 2 },
] }

beforeEach(() => {
  useSession.getState().clear(); localStorage.clear(); sessionStorage.clear()
})

describe('客户本单位身份摘要', () => {
  it('从 scope-tree 解析本单位名称与位置数，不展示 opaque tenant_id', async () => {
    useSession.getState().login({ ...customer, permissions: ['units:read'] }, 'customer')
    vi.spyOn(customerAssetsApi, 'scopeTree').mockResolvedValue([unit])
    render(<CustomerUnitSummary/>)
    expect(await screen.findByText('蓝湾中心')).toBeInTheDocument()
    expect(screen.getByText(/授权可见位置 2 处/)).toBeInTheDocument()
    expect(screen.queryByText('tenant-a')).not.toBeInTheDocument()
  })
  it('scope-tree 读取失败时展示错误而非伪造单位名称', async () => {
    useSession.getState().login({ ...customer, permissions: ['units:read'] }, 'customer')
    vi.spyOn(customerAssetsApi, 'scopeTree').mockRejectedValue(new Error('CUSTOMER_UNIT_REQUIRED'))
    render(<CustomerUnitSummary/>)
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.queryByText('蓝湾中心')).not.toBeInTheDocument()
  })
  it('无位置读取权限时，不请求位置树', () => {
    useSession.getState().login(customer, 'customer')
    const readTree = vi.spyOn(customerAssetsApi, 'scopeTree')
    render(<CustomerUnitSummary/>)
    expect(readTree).not.toHaveBeenCalled()
  })
})
