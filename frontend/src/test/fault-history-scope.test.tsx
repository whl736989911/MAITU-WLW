import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { FaultHistoryPage } from '@/pages/fault-history'
import * as duty from '@/lib/duty-api'
import { useSession } from '@/lib/store'
import { management } from './fixtures'

const fault: duty.FaultEvent = {
  id: 'fault-old-scope', tenant_id: 'tenant-a', device_id: 'device-a', kind: 'FAULT',
  method: 'thing.event.faults', fault_code: '1002003', fault_label: '防拆报警',
  observed_at: null, source_message_at: null, source_time_field: null,
  source_fault_id: null, source_recovered: null, source_recovered_at: null,
  received_at: '2026-11-12T01:00:00Z', created_at: '2026-11-12T01:00:00Z',
  device_name: '旧范围设备', device_address: '验收地址', place_id: 'place-a', handling: null,
}
const page = (items: duty.FaultEvent[], current = 1, total = items.length) => ({ items, total, page: current, size: 20 })
const detail = () => screen.queryByRole('heading', { name: '故障事件详情' })
async function openOldFault() {
  await userEvent.click(await screen.findByRole('button', { name: /旧范围设备/ }))
  expect(detail()).toBeInTheDocument()
}
beforeEach(() => {
  useSession.getState().clear()
  useSession.getState().login({ ...management, permissions: ['device:read'] }, 'management')
  useSession.getState().setScope(['tenant-a'], ['place-a'], true)
})
afterEach(() => vi.restoreAllMocks())

describe('故障历史选择上下文隔离', () => {
  it.each(['单位范围', '位置范围', '登录身份'] as const)('%s变化时立即销毁旧详情，不等待新查询', async boundary => {
    vi.spyOn(duty, 'queryFaults').mockResolvedValueOnce(page([fault])).mockImplementation(() => new Promise(() => {}))
    render(<FaultHistoryPage />)
    await openOldFault()
    act(() => {
      if (boundary === '单位范围') useSession.getState().setScope(['tenant-b'], [], true)
      else if (boundary === '位置范围') useSession.getState().setScope(['tenant-a'], ['place-b'], true)
      else {
        useSession.getState().login({ ...management, user: { ...management.user, id: 'other-reader' }, permissions: ['device:read'] }, 'management')
        useSession.getState().setScope(['tenant-a'], ['place-a'], true)
      }
    })
    expect(detail()).not.toBeInTheDocument()
    expect(screen.queryByText('fault-old-scope', { selector: 'dd' })).not.toBeInTheDocument()
  })

  it('提交筛选与清除筛选都关闭旧详情', async () => {
    vi.spyOn(duty, 'queryFaults').mockResolvedValue(page([fault]))
    render(<FaultHistoryPage />)
    await openOldFault()
    const user = userEvent.setup()
    await user.click(screen.getByText('筛选故障记录'))
    await user.type(screen.getByRole('textbox', { name: '设备名、位置或事件方法' }), '新条件')
    await user.click(screen.getByRole('button', { name: '筛选历史' }))
    expect(detail()).not.toBeInTheDocument()
    await openOldFault()
    await user.click(screen.getByRole('button', { name: '清除筛选' }))
    expect(detail()).not.toBeInTheDocument()
  })

  it('翻页后不保留上一页选中的事件', async () => {
    vi.spyOn(duty, 'queryFaults').mockImplementation(async current => current === 1
      ? page([fault], 1, 21)
      : page([{ ...fault, id: 'fault-next-page', device_name: '下一页设备' }], 2, 21))
    const { container } = render(<FaultHistoryPage />)
    await openOldFault()
    const next = container.querySelector<HTMLButtonElement>('.ant-pagination-next button')!
    await userEvent.click(next)
    expect(await screen.findByRole('button', { name: /下一页设备/ })).toBeInTheDocument()
    expect(detail()).not.toBeInTheDocument()
  })
})
