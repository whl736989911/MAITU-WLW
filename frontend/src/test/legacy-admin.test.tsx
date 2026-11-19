import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useSession } from '@/lib/store'
import { management } from './fixtures'
import type { AuthResponse } from '@/lib/types'
import { legacyApi } from '@/lib/legacy-api'
import { LegacyIngestPage } from '@/pages/legacy/ingest'

vi.mock('@/lib/data-transfer', () => ({ saveDataTransferFile: vi.fn(async () => undefined) }))
vi.mock('@/lib/legacy-api', () => ({
  legacyApi: {
    messages: vi.fn(), message: vi.fn(), differences: vi.fn(), units: vi.fn(),
    exportFile: vi.fn(), previewDictionaries: vi.fn(), confirmDictionaries: vi.fn(),
    previewDevices: vi.fn(), confirmDevices: vi.fn(), decoderRevisions: vi.fn(),
    importDecoder: vi.fn(), previewDecoderSource: vi.fn(), previewDecoderRevision: vi.fn(), quarantines: vi.fn(),
  },
}))
import { saveDataTransferFile } from '@/lib/data-transfer'

const mockApi = vi.mocked(legacyApi)
const legacyAdmin: AuthResponse = { ...management, permissions: [...management.permissions, 'legacy-ingest:read', 'legacy-ingest:write'] }
const digest = 'a'.repeat(64)
const preview = {
  source_sha256: digest, source_companies: 36, source_places: 284, source_products: 81, source_attributes: 2590,
  source_decoder_files: 61, damaged_product_rows: 3, duplicate_model_rows: 7, empty_lines: 0,
  places_tenant_missing: 12, places_identity_conflict: 0, places_archived: 1, versions_existing: 0,
  decoders_existing: 0, hierarchy_configured: true, hierarchy_source_rows: 284, grant_legacy_units: 36,
}
const devicePreview = {
  source_sha256: digest, total_rows: 1998, selected_rows: 3, importable_new: 2, existing: 1, archived: 0,
  moved: 0, place_missing: 0, model_unmatched: 0, model_ambiguous: 1, model_single: 2, identity_invalid: 0,
  eui_missing: 0, eui_conflict: 0, alias_conflict: 0,
  companies: [{ company_id: '294', rows: 3 }, { company_id: '296', rows: 5 }],
}
const revision = { legacy_product_id: '323', revision: 1, source_sha256: digest, bytes: 120, created_at: '2026-11-01T00:00:00Z', active: true }

beforeEach(() => {
  vi.clearAllMocks()
  useSession.getState().clear()
  useSession.getState().login(legacyAdmin, 'management')
  useSession.getState().setScope(['tenant-a'], [], true)
  mockApi.messages.mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
  mockApi.differences.mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
  mockApi.units.mockResolvedValue([{ id: 'tenant-a', name: '单位 A' }])
  mockApi.previewDictionaries.mockResolvedValue(preview)
  mockApi.confirmDictionaries.mockResolvedValue({ tenants: 36, places: 284, products: 81, versions: 81, attributes: 2590, decoders: 61, differences: 12, hierarchy_run_id: 'run', hierarchy_source_sha256: digest, hierarchy_applied: 277, hierarchy_skipped: 7 })
  mockApi.previewDevices.mockResolvedValue(devicePreview)
  mockApi.confirmDevices.mockResolvedValue({ devices: 2, aliases: 4, differences: 1 })
  mockApi.decoderRevisions.mockResolvedValue([revision])
  mockApi.importDecoder.mockResolvedValue({ ...revision, revision: 2, active: true })
  mockApi.previewDecoderRevision.mockResolvedValue({ ok: true, code: null, message: null, events: 1, events_json: '[{"method":"thing.event.alarm"}]', source_sha256: digest })
  mockApi.exportFile.mockResolvedValue({ kind: 'MESSAGES', filename: 'legacy-messages.csv', media_type: 'text/csv;charset=utf-8', content_base64: 'aWQ=', content_sha256: digest, row_count: 1 })
})

describe('旧平台管理工作台', () => {
  it('字典导入先只读预览，再以来源摘要与原因确认', async () => {
    render(<LegacyIngestPage />)
    fireEvent.click(screen.getByRole('button', { name: '字典导入' }))
    fireEvent.click(screen.getByRole('button', { name: '读取只读预览' }))
    await screen.findByRole('button', { name: '确认导入字典' })
    expect(mockApi.previewDictionaries).toHaveBeenCalledTimes(1)
    expect(screen.getAllByText('284').length).toBeGreaterThan(0)
    fireEvent.change(screen.getByLabelText('字典导入原因'), { target: { value: '核对导入' } })
    fireEvent.click(screen.getByRole('button', { name: '确认导入字典' }))
    await waitFor(() => expect(mockApi.confirmDictionaries).toHaveBeenCalledWith(
      expect.objectContaining({ expected_sha256: digest, reason: '核对导入' }), expect.any(String)))
    expect(await screen.findByRole('status')).toHaveTextContent('本次结果：单位 36')
  })

  it('设备导入必须明确选择来源单位后才允许确认', async () => {
    render(<LegacyIngestPage />)
    fireEvent.click(screen.getByRole('button', { name: '设备导入' }))
    fireEvent.click(screen.getByRole('button', { name: '只读预览全部来源单位' }))
    await screen.findByRole('checkbox', { name: /294/ })
    expect(mockApi.previewDevices).toHaveBeenCalledWith([])
    const confirm = screen.getByRole('button', { name: /确认导入/ }) as HTMLButtonElement
    expect(confirm.disabled).toBe(true)
    fireEvent.click(screen.getByRole('checkbox', { name: /294/ }))
    fireEvent.change(screen.getByLabelText('设备导入原因'), { target: { value: '试点导入' } })
    fireEvent.click(screen.getByRole('button', { name: '确认导入 1 个单位' }))
    await waitFor(() => expect(mockApi.confirmDevices).toHaveBeenCalledWith(
      expect.objectContaining({ expected_sha256: digest, company_ids: ['294'], reason: '试点导入' }), expect.any(String)))
    expect(await screen.findByRole('status')).toHaveTextContent('本次结果：设备 2')
  })

  it('解码器预览返回真实沙箱事件并允许导入新修订', async () => {
    render(<LegacyIngestPage />)
    fireEvent.click(screen.getByRole('button', { name: '解码器' }))
    fireEvent.change(screen.getByLabelText('旧产品标识'), { target: { value: '323' } })
    fireEvent.click(screen.getByRole('button', { name: '读取修订' }))
    await screen.findByText(/最新生效/)
    fireEvent.change(screen.getByLabelText('报文十六进制'), { target: { value: '0A1B' } })
    fireEvent.click(screen.getByRole('button', { name: '预览已选修订' }))
    expect(await screen.findByText(/沙箱返回 1 个事件/)).toBeInTheDocument()
    expect(screen.getByText(/thing.event.alarm/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('解码器源码'), { target: { value: 'function rawDataToProtocol(){return {method:"thing.event.alarm"}}' } })
    fireEvent.change(screen.getByLabelText('解码器导入原因'), { target: { value: '更新解码器' } })
    fireEvent.click(screen.getByRole('button', { name: '导入为新修订' }))
    await waitFor(() => expect(mockApi.importDecoder).toHaveBeenCalledWith(
      expect.objectContaining({ legacy_product_id: '323', reason: '更新解码器'}), expect.any(String)))
    expect(await screen.findByRole('status')).toHaveTextContent('已导入修订 2')
  })

  it('报文导出使用明确单位与原因，并校验文件摘要后下载', async () => {
    render(<LegacyIngestPage />)
    fireEvent.click(screen.getByRole('button', { name: '导出报文' }))
    await screen.findByLabelText('导出单位')
    fireEvent.change(screen.getByLabelText('导出单位'), { target: { value: 'tenant-a' } })
    fireEvent.change(screen.getByLabelText('导出原因'), { target: { value: '导出核对' } })
    fireEvent.click(screen.getByRole('button', { name: '导出并下载' }))
    await waitFor(() => expect(mockApi.exportFile).toHaveBeenCalledWith(
      expect.objectContaining({ kind: 'MESSAGES', target: 'TENANT', tenant_id: 'tenant-a', reason: '导出核对'}),
      undefined, ['tenant-a'], []))
    await waitFor(() => expect(saveDataTransferFile).toHaveBeenCalledWith(expect.objectContaining({ content_sha256: digest })))
    expect(await screen.findByRole('status')).toHaveTextContent('已导出 1 行')
  })

  it('归属异常面板仅在有独立权限时读取，且不展示重放操作', async () => {
    useSession.getState().revalidate('test-only-token', useSession.getState().authGeneration, legacyAdmin.user, [...legacyAdmin.permissions, 'ownership-exceptions:read'])
    mockApi.quarantines.mockResolvedValue({ items: [{ id: 'q1', vendor_platform: 'aep_turbo', vendor_device_id: 'vendor-1', received_at: '2026-11-01T00:00:00Z', observed_at: null, reason: 'OWNERSHIP_TIME_UNKNOWN', candidate_device_id: 'd1' }], total: 1, page: 1, size: 20 })
    render(<LegacyIngestPage />)
    fireEvent.click(screen.getByRole('button', { name: '归属异常' }))
    expect(await screen.findByText('OWNERSHIP_TIME_UNKNOWN')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /重放/ })).toBeNull()
  })
})
