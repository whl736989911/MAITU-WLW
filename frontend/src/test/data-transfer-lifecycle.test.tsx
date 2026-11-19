import { beforeEach, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { DataTransferActions } from '@/components/data-transfer-actions'
import { dataTransferApi, type DataTransferPreview } from '@/lib/data-transfer'
import { useSession } from '@/lib/store'
import { management } from './fixtures'

beforeEach(() => {
  vi.restoreAllMocks()
  useSession.getState().clear()
  useSession.getState().login({ ...management, permissions: ['imports:read', 'imports:write', 'device:read', 'device:write'] }, 'management')
  useSession.getState().setScope(['tenant-a'], [])
})

it('closing a pending import prevents its late plan from authorizing a newly selected file', async () => {
  vi.spyOn(dataTransferApi, 'capabilities').mockResolvedValue({ protected_storage_ready: true, kinds: [{ kind: 'DEVICES', columns: ['name'], read_permission: 'device:read', write_permission: 'device:write', importable: true, exportable: false, tenant_required: true, audiences: ['management'], effect: 'MASTER_DATA' }] })
  let resolvePreview!: (value: DataTransferPreview) => void
  vi.spyOn(dataTransferApi, 'preview').mockImplementation(() => new Promise(resolve => { resolvePreview = resolve }))
  const user = userEvent.setup()
  render(<DataTransferActions kind="DEVICES" tenantId="tenant-a"/>)
  async function open() {
    await user.click(await screen.findByRole('button', { name: '导入 / 导出' }))
    await user.click(await screen.findByRole('menuitem', { name: '导入设备资产' }))
  }
  async function choose(name: string) {
    const file = new File(['name\n一个设备'], name, { type: 'text/csv' })
    Object.defineProperty(file, 'arrayBuffer', { value: async () => new TextEncoder().encode('name\n一个设备').buffer })
    fireEvent.change(screen.getByLabelText('选择文件'), { target: { files: [file] } })
    await screen.findByText(`已读取：${name}`)
  }
  await open()
  await choose('first.csv')
  await user.click(screen.getByRole('button', { name: '校验并预览' }))
  await user.click(screen.getByRole('button', { name: /^close$|^关闭$/i }))
  await open()
  await choose('second.csv')
  await act(async () => resolvePreview({ plan_id: 'old-plan', kind: 'DEVICES', format: 'CSV', tenant_id: 'tenant-a', source_sha256: 'a'.repeat(64), row_count: 1, valid_count: 1, error_count: 0, rows: [{ row_no: 2, operation: 'CREATE', status: 'VALID', errors: [] }] }))
  expect(screen.getByText('已读取：second.csv')).toBeInTheDocument()
  expect(screen.queryByText(/校验结果：/)).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '核对并提交' })).not.toBeInTheDocument()
})
