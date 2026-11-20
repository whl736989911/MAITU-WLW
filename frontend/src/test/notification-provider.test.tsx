import { afterEach, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useSession } from '@/lib/store'
import type { NotificationProvider } from '@/lib/types'
import { NotificationProviderPage } from '@/pages/notification-provider'
import { management, response } from './fixtures'

const channels = [
  { label: '短信', idLabel: '短信模板 ID', id: 'sms_template_code', mapping: 'sms_template_variables', enabled: 'sms_enabled', old: 'SMS_OLD', otherMapping: 'voice_template_variables', otherLabel: '语音' },
  { label: '语音', idLabel: '语音 TTS 模板 ID', id: 'tts_code', mapping: 'voice_template_variables', enabled: 'voice_enabled', old: 'TTS_OLD', otherMapping: 'sms_template_variables', otherLabel: '短信' },
] as const

function setup() {
  useSession.getState().login({ ...management, permissions: ['notification-config:read', 'notification-config:write'] }, 'management')
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
  let saved: NotificationProvider = {
    sms_enabled: false, voice_enabled: false, sms_sign_name: 'Fixture Sign', sms_template_code: 'SMS_OLD', tts_code: 'TTS_OLD',
    tts_called_show_number: null, sms_endpoint: 'https://sms.invalid/', voice_endpoint: 'https://voice.invalid/',
    sms_template_variables: { name: 'device_name' }, voice_template_variables: { site: 'address' },
    access_key_id_masked: '****fake', secret_configured: true, test_mode_enabled: false, test_phone_masked: null, revision: 4, updated_at: null,
  }
  const original = saved
  const updates: Record<string, unknown>[] = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const path = new URL(url, 'http://local').pathname
    if (path.endsWith('/management/notification-provider')) {
      if (init?.method === 'PUT') {
        const body = JSON.parse(String(init.body))
        updates.push(body)
        saved = { ...saved, ...body, revision: saved.revision + 1 }
      }
      return response(saved)
    }
    if (path.endsWith('/management/notification-provider/status')) {
      return response({ real_enabled: false, sms_ready: false, voice_ready: false, secret_storage_ready: true })
    }
    return response({})
  }))
  render(<NotificationProviderPage />)
  return { updates, original, confirm, user: userEvent.setup() }
}

const save = () => screen.getByRole('button', { name: '保存全部通道配置' })
const review = (channel: string) => screen.getByRole('checkbox', { name: `已核对${channel}模板 ID 与变量映射` })
const noReview = (channel: string) => screen.queryByRole('checkbox', { name: `已核对${channel}模板 ID 与变量映射` })

async function saved() { await screen.findByText(/通知通道配置已保存/) }

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); useSession.getState().clear() })

it.each(channels)('requires a fresh $label review even when reusing the same mapping', async channel => {
  const { updates, original, confirm, user } = setup()
  const id = await screen.findByDisplayValue(channel.old)
  await user.clear(id)
  await user.type(id, `${channel.old}_NEW`)
  await user.click(save())
  expect(updates).toEqual([])
  expect(noReview(channel.otherLabel)).not.toBeInTheDocument()
  await user.click(review(channel.label))

  await user.type(id, '2')
  expect(review(channel.label)).not.toBeChecked()
  await user.click(save())
  expect(updates).toEqual([])
  await user.click(review(channel.label))
  const mapping = screen.getByLabelText(`${channel.label}模板变量映射`)
  await user.type(mapping, ' ')
  expect(review(channel.label)).not.toBeChecked()
  await user.click(review(channel.label))
  confirm.mockReturnValueOnce(false)
  await user.click(save())
  expect(updates).toEqual([])
  await user.click(save())
  await saved()
  expect(updates).toHaveLength(1)
  expect(updates[0]).toMatchObject({
    [channel.id]: `${channel.old}_NEW2`, [channel.mapping]: original[channel.mapping],
    [`${channel.mapping}_confirmed`]: true, expected_revision: 4,
  })
  expect(updates[0][`${channel.otherMapping}_confirmed`]).not.toBe(true)
})

it.each(channels)('stages a blank $label mapping while disabled and requires review to enable with an empty object', async channel => {
  const { updates, user } = setup()
  const id = await screen.findByDisplayValue(channel.old)
  await user.type(id, '_STAGED')
  await user.clear(screen.getByLabelText(`${channel.label}模板变量映射`))
  await user.click(save())
  await saved()
  expect(updates[0]).toMatchObject({ [channel.id]: `${channel.old}_STAGED`, [channel.enabled]: false, [channel.mapping]: null })
  expect(updates[0][`${channel.mapping}_confirmed`]).not.toBe(true)

  await user.click(await screen.findByRole('checkbox', { name: `启用${channel.label}通道` }))
  await user.click(save())
  expect(updates).toHaveLength(1)
  await user.type(screen.getByLabelText(`${channel.label}模板变量映射`), '{{}')
  await user.click(review(channel.label))
  await user.click(save())
  await saved()
  expect(updates[1]).toMatchObject({
    [channel.id]: `${channel.old}_STAGED`, [channel.enabled]: true, [channel.mapping]: {},
    [`${channel.mapping}_confirmed`]: true, expected_revision: 5,
  })
})

it('saves an unrelated edit without reconfirming either unchanged template', async () => {
  const { updates, user } = setup()
  const sign = await screen.findByDisplayValue('Fixture Sign')
  await user.type(sign, ' Updated')
  expect(noReview('短信')).not.toBeInTheDocument()
  expect(noReview('语音')).not.toBeInTheDocument()
  await user.click(save())
  await saved()
  expect(updates[0]).toMatchObject({ sms_sign_name: 'Fixture Sign Updated', expected_revision: 4 })
  expect(updates[0].sms_template_variables_confirmed).not.toBe(true)
  expect(updates[0].voice_template_variables_confirmed).not.toBe(true)
})
