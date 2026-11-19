import { useState } from 'react'
import { beforeEach, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigurationUnitTarget } from '@/components/configuration-unit-target'
import { AuthorizedReadScope } from '@/components/authorized-read-scope'
import { api } from '@/lib/api'
import { useSession } from '@/lib/store'
import type { ScopeUnit } from '@/lib/types'
import { management } from './fixtures'

function Draft({ tenantId }: { tenantId: string }) {
  const [value, setValue] = useState('')
  return <label>未提交说明<input aria-label="未提交说明" data-tenant-id={tenantId} value={value} onChange={event => setValue(event.target.value)}/></label>
}
beforeEach(() => {
  vi.restoreAllMocks()
  useSession.getState().clear()
  useSession.getState().login(management, 'management')
  useSession.getState().setScope(['tenant-a'], [])
  useSession.setState({ page: 'devices' })
})

it('preserves the draft across an empty browse selection and unit-tree refresh but destroys it when the identity changes', async () => {
  const units: ScopeUnit[] = [{ id: 'tenant-a', name: '单位A', places: [] }, { id: 'tenant-b', name: '单位B', places: [] }]
  let finishRefresh!: (value: ScopeUnit[]) => void
  vi.spyOn(api, 'scopeTree').mockResolvedValueOnce(units).mockImplementationOnce(() => new Promise(resolve => { finishRefresh = resolve }))
  render(<AuthorizedReadScope><ConfigurationUnitTarget>{tenant => <Draft tenantId={tenant}/>}</ConfigurationUnitTarget></AuthorizedReadScope>)
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText('未提交说明'), '不能被普通刷新清空的草稿')
  act(() => useSession.getState().setScope([], []))
  expect(screen.getByLabelText('未提交说明')).toHaveValue('不能被普通刷新清空的草稿')
  expect(screen.getByLabelText('配置目标单位')).toHaveValue('tenant-a')
  act(() => useSession.getState().refreshScopeTree())
  await waitFor(() => expect(finishRefresh).toBeTypeOf('function'))
  expect(screen.getByLabelText('未提交说明')).toHaveValue('不能被普通刷新清空的草稿')
  await act(async () => finishRefresh([{ ...units[0], name: '更新后的单位A' }, units[1]]))
  expect(screen.getByLabelText('未提交说明')).toHaveValue('不能被普通刷新清空的草稿')
  expect(screen.getByLabelText('配置目标单位')).toHaveValue('tenant-a')
  act(() => useSession.getState().login({ ...management, user: { ...management.user, id: 'different-actor' } }, 'management'))
  expect(screen.queryByDisplayValue('不能被普通刷新清空的草稿')).not.toBeInTheDocument()
})
