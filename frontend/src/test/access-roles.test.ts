import { beforeEach, describe, expect, it, vi } from 'vitest'
import { accessApi, accessDecoders } from '@/lib/access-roles'
import { useSession } from '@/lib/store'
import { management, customer } from './fixtures'
import { allowedPage } from '@/lib/types'

const role = { id: 'role-1', code: 'inspect', name: '设备只读', user_type: 'CUSTOMER', enabled: true, deleted: false, revision: 1, permissions: ['device:read'] }
const userAccess = { user_id: 'user-1', user_type: 'CUSTOMER', revision: 8, direct_permissions: [], effective_permissions: ['device:read'], roles: [role] }
const scope = { user_id: 'user-1', tenant_id: 'tenant-1', configured: true, mode: 'DEVICE_SET', revision: 2, device_ids: [], place_ids: [] }

describe('N04 access contracts', () => {
  beforeEach(() => { useSession.getState().clear() })

  it('strictly decodes effective/direct access and accepts an explicit empty DEVICE_SET', () => {
    expect(accessDecoders.userAccess(userAccess)).toEqual(userAccess)
    expect(accessDecoders.scope(scope)).toEqual(scope)
    expect(accessDecoders.scope({ ...scope, configured: false, mode: null, revision: 0 })).toEqual({ ...scope, configured: false, mode: null, revision: 0 })
    expect(accessDecoders.scope({ ...scope, mode: 'PLACE_SUBTREE', device_ids: [], place_ids: ['place-1'] })).toMatchObject({ mode: 'PLACE_SUBTREE', device_ids: [], place_ids: ['place-1'] })
  })

  it('rejects extra response keys and inconsistent scope combinations', () => {
    expect(() => accessDecoders.userAccess({ ...userAccess, effective: [] })).toThrow()
    expect(() => accessDecoders.scope({ ...scope, mode: 'ALL_TENANT', device_ids: ['d-1'] })).toThrow()
    expect(() => accessDecoders.scope({ ...scope, mode: 'DEVICE_SET', device_ids: ['d-1'], place_ids: ['place-1'] })).toThrow()
    expect(() => accessDecoders.scope({ ...scope, mode: 'PLACE_SUBTREE', device_ids: ['d-1'], place_ids: ['place-1'] })).toThrow()
    expect(() => accessDecoders.scope({ ...scope, mode: 'PLACE_SUBTREE', device_ids: [], place_ids: [] })).toThrow()
    expect(() => accessDecoders.scope({ ...scope, configured: false })).toThrow()
  })

  it.each(['-0', '1.0', '1e0', '9007199254740992', '9007199254740991.1', '1,"revision":2'])('does not confirm a successful write with ambiguous revision token %s', async token => {
    useSession.getState().login(management, 'management')
    const wire = JSON.stringify(role).replace('"revision":1', `"revision":${token}`)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(wire, { status: 200, headers: { 'Content-Type': 'application/json' } })))
    await expect(accessApi.updateRole(role.id, { name: role.name, enabled: true, permissions: role.permissions,
      expected_revision: 1, reason: 'Explicit template correction'}, 'original-intent-key'))
      .rejects.toMatchObject({ status: 200, code: 'INVALID_RESPONSE' })
  })

  it.each(['management', 'customer'] as const)('lands a device-only %s account on an authorized page without granting overview access', audience => {
    useSession.getState().login({ ...(audience === 'customer' ? customer : management), permissions: ['device:read'] }, audience)
    const state = useSession.getState()
    expect(allowedPage(state.page, audience, state.permissions)).toBe(true)
    expect(state.permissions).not.toContain('summary:read')
  })

  it('keeps customer browsing and export filters fixed to its own unit after authorization changes', () => {
    useSession.getState().login(customer, 'customer')
    expect(useSession.getState()).toMatchObject({ scopeInitialized: true, scopeTenantIds: [customer.user.tenant_id], scopePlaceIds: [] })
    const { token, authGeneration } = useSession.getState()
    useSession.getState().setScope(['foreign-unit'], ['foreign-place'])
    useSession.getState().revalidate(token!, authGeneration, customer.user, ['device:read'])
    expect(useSession.getState()).toMatchObject({ scopeInitialized: true, scopeTenantIds: [customer.user.tenant_id], scopePlaceIds: [] })
    useSession.getState().login(management, 'management')
    expect(useSession.getState()).toMatchObject({ scopeInitialized: false, scopeTenantIds: [], scopePlaceIds: [] })
  })

  it('advances session generation only for the current token when revalidation changes authorization', () => {
    useSession.getState().login(management, 'management')
    const token = useSession.getState().token!, authGeneration = useSession.getState().authGeneration, generation = useSession.getState().generation
    expect(useSession.getState().revalidate('stale-token', authGeneration, customer.user, customer.permissions)).toBe(false)
    expect(useSession.getState().generation).toBe(generation)
    expect(useSession.getState().revalidate(token, authGeneration, management.user, [...management.permissions, 'roles:read'])).toBe(true)
    expect(useSession.getState().generation).toBe(generation + 1)
    expect(useSession.getState().permissions).toContain('roles:read')
  })
})
