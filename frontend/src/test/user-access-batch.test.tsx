import { beforeEach, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { UserAccessBatch } from '@/components/user-access-batch'
import { accessApi } from '@/lib/access-roles'
import { ApiError } from '@/lib/api'
import { useSession } from '@/lib/store'
import type { ManagedUser } from '@/lib/types'
import { management } from './fixtures'

const target: ManagedUser = { id: 'target-reader', username: 'target-reader', name: '目标用户', user_type: 'CUSTOMER', tenant_id: 'tenant-a', tenant_name: '单位A', enabled: true, must_change_password: false, revision: 0, is_protected: false, permissions: ['device:read'], tenant_ids: [] }
beforeEach(() => {
  vi.restoreAllMocks()
  useSession.getState().clear()
  useSession.getState().login({ ...management, permissions: ['users:read', 'users:assign-scope'] }, 'management')
  useSession.getState().setScope(['tenant-a'], [])
})

it('checks a changed place-root set with the server instead of calling it unchanged because both device sets are empty', async () => {
  vi.spyOn(accessApi, 'userScope').mockResolvedValue({ user_id: target.id, tenant_id: 'tenant-a', configured: true, mode: 'PLACE_SUBTREE', revision: 3, device_ids: [], place_ids: ['north'] })
  vi.spyOn(accessApi, 'updateUserScope').mockImplementation(async (_platform, _id, body) => {
    if (body.place_ids.length === 2 && body.place_ids[0] === 'south-one' && body.place_ids[1] === 'south-two') throw new ApiError(403, 'SCOPE_TARGET_FORBIDDEN', '目标位置超出可授权范围')
    throw new ApiError(400, 'INVALID_PLACE_IDS', '位置编号未正确规范化')
  })
  const user = userEvent.setup()
  render(<UserAccessBatch users={[target]} platform={false} onCompleted={() => undefined}/>)
  await user.click(screen.getByRole('checkbox', { name: /目标用户/ }))
  await user.selectOptions(screen.getByLabelText('调整对象'), 'SCOPE')
  await user.selectOptions(screen.getByLabelText('目标单位'), 'tenant-a')
  await user.click(screen.getByRole('radio', { name: /PLACE_SUBTREE/ }))
  await user.type(screen.getByLabelText(/位置根 ID/), 'south-one south-two\nsouth-two')
  await user.type(screen.getByLabelText('变更理由'), '调整位置范围')
  await user.click(screen.getByRole('button', { name: '核对批量操作' }))

  await user.click(screen.getByRole('button', { name: '执行批量调整' }))
  const result = await screen.findByRole('status')
  expect(result).toHaveTextContent('目标用户 · REJECTED · 目标位置超出可授权范围')
  expect(result).not.toHaveTextContent(/UNCHANGED|UPDATED/)
})
