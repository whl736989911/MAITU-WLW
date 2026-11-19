import { describe, expect, it } from 'vitest'
import {
  buildLegacyVendorPayload, emptyLegacyVendorDraft, legacyVendorFamily,
  validateLegacyName, validateLegacySerial, type LegacyVendorDraft,
} from '@/lib/device-registrations'

function ttlDraft(): LegacyVendorDraft {
  const draft = emptyLegacyVendorDraft('LEGACY_TTL')
  if (!draft) throw new Error('TTL draft missing')
  draft.ttl = {
    ...draft.ttl,
    productId: '1001', applicationId: '2002', platformId: '3003', networkModel: '1',
    parentId: '0', node: '12', productCategory: '4', describe: ' 一层大厅 ',
    profile: 'default', appKey: 'A'.repeat(32), appEui: 'b'.repeat(16),
  }
  return draft
}

function tsfDraft(): LegacyVendorDraft {
  const draft = emptyLegacyVendorDraft('LEGACY_TSF')
  if (!draft) throw new Error('TSF draft missing')
  draft.tsf = {
    ...draft.tsf,
    productId: '1001', applicationId: '2002', platformId: '3003', networkModel: '1',
    placeId: '4004', node: '7', address: '一号楼', gps: '120.1,30.2', childSystem: '火灾报警',
    installMsg: '  已联调  ', show: 'true', push: 'false', contactIds: '5, 6',
  }
  return draft
}

describe('legacy ledger registration fields', () => {
  it('maps only the audited TTL add fields and keeps LoRa credentials inside deviceAttr', () => {
    expect(buildLegacyVendorPayload(ttlDraft())).toEqual({
      productId: 1001, applicationId: 2002, platformId: 3003, networkModel: 1,
      parentId: 0, node: 12, productCategory: 4, describe: '一层大厅',
      deviceAttr: { AppKey: 'A'.repeat(32), AppEUI: 'b'.repeat(16), Profile: 'default' },
    })
  })

  it('maps ABP credentials and rejects OTAA credentials in ABP mode', () => {
    const abp = ttlDraft()
    abp.ttl = { ...abp.ttl, networkModel: '2', appKey: '', appEui: '', devAddr: '1a2b3c4d', nwkSKey: 'c'.repeat(32), appSKey: 'd'.repeat(32) }
    expect(buildLegacyVendorPayload(abp).deviceAttr).toEqual({
      DevAddr: '1a2b3c4d', NwkSKey: 'c'.repeat(32), AppSKey: 'd'.repeat(32), Profile: 'default',
    })
    abp.ttl = { ...abp.ttl, appKey: 'e'.repeat(32) }
    expect(() => buildLegacyVendorPayload(abp)).toThrow('OTAA')
  })

  it('rejects malformed ledgers instead of guessing defaults', () => {
    const invalidNetwork = ttlDraft()
    invalidNetwork.ttl = { ...invalidNetwork.ttl, networkModel: '9' }
    expect(() => buildLegacyVendorPayload(invalidNetwork)).toThrow('网络模式')
    const shortKey = ttlDraft()
    shortKey.ttl = { ...shortKey.ttl, appKey: 'abcd' }
    expect(() => buildLegacyVendorPayload(shortKey)).toThrow('32位十六进制')
    const emptyProduct = ttlDraft()
    emptyProduct.ttl = { ...emptyProduct.ttl, productId: '' }
    expect(() => buildLegacyVendorPayload(emptyProduct)).toThrow('产品ID')
  })

  it('requires TSF node or parent and emits typed booleans and contact ids', () => {
    expect(buildLegacyVendorPayload(tsfDraft())).toMatchObject({
      productId: 1001, applicationId: 2002, platformId: 3003, networkModel: 1, placeId: 4004,
      node: 7, address: '一号楼', gps: '120.1,30.2', childSystem: '火灾报警', installMsg: '已联调',
      show: true, push: false, contactIds: [5, 6],
    })
    const noAnchor = tsfDraft()
    noAnchor.tsf = { ...noAnchor.tsf, node: '', parentId: '' }
    expect(() => buildLegacyVendorPayload(noAnchor)).toThrow('节点号')
    const badContacts = tsfDraft()
    badContacts.tsf = { ...badContacts.tsf, contactIds: '5,x' }
    expect(() => buildLegacyVendorPayload(badContacts)).toThrow('联系人ID')
  })

  it('recognizes only the two real ledger drivers and enforces their serial/name contracts', () => {
    expect(legacyVendorFamily('LEGACY_TTL')).toBe('LEGACY_TTL')
    expect(legacyVendorFamily('LEGACY_TSF')).toBe('LEGACY_TSF')
    expect(legacyVendorFamily('HIKVISION_FIRECLOUD')).toBeNull()
    expect(emptyLegacyVendorDraft('HIKVISION_FIRECLOUD')).toBeNull()
    expect(validateLegacySerial('LEGACY_TTL', '1234')).toContain('5-32')
    expect(validateLegacySerial('LEGACY_TTL', '12345')).toBeNull()
    expect(validateLegacyName('LEGACY_TSF', 'x'.repeat(21))).toContain('20')
    expect(validateLegacyName('LEGACY_TTL', '任意名称')).toBeNull()
  })
})
