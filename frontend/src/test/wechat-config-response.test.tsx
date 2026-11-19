import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { WechatConfigPanel } from '@/components/wechat-config'
import { useSession } from '@/lib/store'
import { management, response } from './fixtures'

const wireConfig = {
  "configured": false,
  "enabled": false,
  "app_id_masked": null,
  "app_secret_configured": false,
  "callback_token_configured": false,
  "aes_key_configured": false,
  "oauth_redirect_uri": null,
  "callback_url": null,
  "fire_template_id": null,
  "fault_template_id": null,
  "fire_keys": {
    "device_key": "thing1",
    "address_key": "thing3",
    "time_key": "time2",
    "event_key": "const4"
  },
  "fault_keys": {
    "device_key": "thing1",
    "address_key": "thing3",
    "time_key": "time2",
    "event_key": "const4"
  },
  "api_credentials_ready": false,
  "oauth_ready": false,
  "callback_ready": false,
  "fire_template_ready": false,
  "fault_template_ready": false,
  "secret_storage_ready": true,
  "token_state": "NOT_FETCHED",
  "token_expires_at": null,
  "token_current": false,
  "revision": 0,
  "credentials_revision": 0,
  "updated_at": "2026-11-12T09:40:56.753559+08:00",
  "diagnostics": {
    "active_bindings": 0,
    "stale_bindings": 0,
    "contact_links": 0,
    "stale_contact_links": 0,
    "rules_with_wechat": 0,
    "pending_oauth_requests": 0,
    "last_callback_at": null,
    "last_callback_event": null,
    "last_callback_status": null,
    "last_oauth_callback_at": null,
    "last_oauth_callback_status": null
  }
}

beforeEach(() => {
  useSession.getState().clear()
  useSession.getState().login({ ...management, permissions: ['notification-config:read', 'notification-config:write'] }, 'management')
})
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

it('真实蛇形配置可编辑，刷新得到未确认类型时移除旧表单而不是伪造可用配置', async () => {
  let payload: unknown = structuredClone(wireConfig)
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url !== '/api/management/wechat-official-account') throw new Error('Unexpected endpoint')
    return Promise.resolve(response(payload))
  }))
  render(<WechatConfigPanel/>)
  const appId = await screen.findByLabelText('AppID') as HTMLInputElement
  expect(appId.closest('fieldset')?.disabled).toBe(false)
  expect((screen.getByLabelText('启用公众号模板通知') as HTMLInputElement).checked).toBe(false)
  fireEvent.change(appId, { target: { value: 'isolated-edit-not-saved' } })
  expect(appId.value).toBe('isolated-edit-not-saved')
  payload = { ...wireConfig, enabled: 'false' }
  fireEvent.click(screen.getByRole('button', { name: '刷新配置与诊断' }))
  await waitFor(() => expect(screen.queryByLabelText('AppID')).toBeNull())
  expect(screen.getByRole('button', { name: '重试' })).toBeTruthy()
})
