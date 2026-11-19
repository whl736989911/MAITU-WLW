import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from 'react'
import { Alert, Card, Descriptions, Drawer, Dropdown, Select, Space, Table, Tag, Typography, type MenuProps, type TableColumnsType } from 'antd'
import { ChevronDown } from 'lucide-react'
import { api } from '@/lib/api'
import { dataTransferApi, dataTransferEffects, dataTransferLabels, type DataTransferCapability, type DataTransferExport, type DataTransferFile, type DataTransferFormat, type DataTransferJob, type DataTransferKind, type DataTransferPreview, type DataTransferPreviewRow } from '@/lib/data-transfer'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { useMutationAction } from '@/components/identity-common'
import { DataTransferDownload } from '@/components/data-transfer-download'
import { DataTransferJobViewer } from '@/components/data-transfer-job'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ErrorNotice } from '@/components/common'

export interface DataTransferActionsProps {
  kind: DataTransferKind
  label?: string
  tenantId?: string
  filters?: Record<string, string | number | boolean | null>
  selectedIds?: string[]
  onCompleted?: (result: DataTransferJob | DataTransferExport) => void
}

export function DataTransferActions(props: DataTransferActionsProps) {
  const session = useSession()
  const owner = JSON.stringify([session.authGeneration, session.generation, session.audience, session.permissions, props.kind])
  return <ActionControl key={owner} {...props}/>
}

function ActionControl(props: DataTransferActionsProps) {
  const session = useSession()
  const [mode, setMode] = useState<'import' | 'export' | null>(null)
  const [selection, setSelection] = useState<string[]>([]), [jobId, setJobId] = useState<string | null>(null)
  const entitled = !!session.user && !session.user.must_change_password && session.permissions.some(permission => ['imports:read', 'imports:write', 'exports:read', 'exports:write'].includes(permission))
  const metadata = useResource(`transfer-capabilities:${session.generation}:${session.audience}`, signal => dataTransferApi.capabilities(signal), entitled)
  const capability = metadata.data?.kinds.find(item => item.kind === props.kind && item.audiences.includes(session.audience))
  const canImport = !!capability?.importable && session.permissions.includes('imports:write') && !!capability.write_permission && session.permissions.includes(capability.write_permission)
  const canTemplate = !!capability?.importable && session.permissions.includes('imports:read') && (session.permissions.includes(capability.read_permission) || !!capability.write_permission && session.permissions.includes(capability.write_permission))
  const canExport = !!capability?.exportable && session.permissions.includes('exports:write') && session.permissions.includes(capability.read_permission)
  if (!entitled) return null
  const items: NonNullable<MenuProps['items']> = []
  if (canImport || canTemplate) items.push({ key: 'import', label: `导入${dataTransferLabels[props.kind]}` })
  if (canExport) items.push({ key: 'export', label: `导出${dataTransferLabels[props.kind]}` })
  if (session.permissions.includes('imports:read') || session.permissions.includes('exports:read')) items.push({ key: 'records', label: '我的导入任务与导出文件' })
  return <>
    <Dropdown trigger={['click']} disabled={metadata.loading || items.length === 0} menu={{ items, onClick: ({ key }) => {
      if (key === 'records') { useSession.getState().navigate('bulk-io'); return }
      if (key === 'import' || key === 'export') { setSelection([...(props.selectedIds ?? [])]); setMode(key) }
    } }}><Button variant="outline" disabled={metadata.loading || items.length === 0}>{metadata.loading ? '数据操作…' : props.label ?? '导入 / 导出'}<ChevronDown size={14}/></Button></Dropdown>
    {metadata.error != null && <ErrorNotice error={metadata.error} retry={metadata.refresh}/>}
    {mode && capability && <TransferPanel key={mode} {...props} mode={mode} capability={capability} protectedStorageReady={metadata.data?.protected_storage_ready ?? false} selectedIds={selection} canImport={canImport} canTemplate={canTemplate} onClose={() => setMode(null)} onJobCreated={job => { setMode(null); if (session.permissions.includes('imports:read')) setJobId(job.id); props.onCompleted?.(job) }}/>}
    {jobId && <DataTransferJobViewer jobId={jobId} onClose={() => setJobId(null)} onChanged={result => { if (!result) return; props.onCompleted?.(result) }}/>}
  </>
}

function TransferPanel({ kind, tenantId, filters, selectedIds = [], onCompleted, mode, capability, protectedStorageReady, canImport, canTemplate, onClose, onJobCreated }: DataTransferActionsProps & { mode: 'import' | 'export'; capability: DataTransferCapability; protectedStorageReady: boolean; canImport: boolean; canTemplate: boolean; onClose: () => void; onJobCreated: (job: DataTransferJob) => void }) {
  const session = useSession(), action = useMutationAction()
  const [format, setFormat] = useState<DataTransferFormat>('CSV')
  const [target, setTarget] = useState(tenantId ?? '')
  const [selectedOnly, setSelectedOnly] = useState(false)
  const [file, setFile] = useState<DataTransferFile | null>(null), [fileEpoch, setFileEpoch] = useState(0)
  const [preview, setPreview] = useState<DataTransferPreview | null>(null)
  const [exported, setExported] = useState<DataTransferExport | null>(null)
  const [reason, setReason] = useState(''), [busy, setBusy] = useState(false), [error, setError] = useState<unknown>()
  const pending = useRef<AbortController | null>(null), alive = useRef(true), receipt = useRef<DataTransferJob | DataTransferExport | null>(null)
  const frozenExport = useRef<{ tenantId?: string; filters?: Record<string, string | number | boolean | null>; selectedIds?: string[] } | null>(null)
  const needUnit = mode === 'import' && capability.tenant_required
  const importTenant = session.audience === 'customer' ? session.user?.tenant_id ?? undefined : needUnit ? target || undefined : undefined
  const canChooseUnit = needUnit && session.audience === 'management' && !tenantId
  const units = useResource('transfer-target-units', signal => api.tenants(signal), canChooseUnit && session.permissions.includes('tenants:read'))
  const exportFilters = filters ?? (tenantId && capability.tenant_required ? { unit_ids: tenantId, place_ids: '' } : undefined)
  const emptyScope = !!exportFilters && (Object.hasOwn(exportFilters, 'unit_ids') || Object.hasOwn(exportFilters, 'place_ids')) && !exportFilters.unit_ids && !exportFilters.place_ids
  const locked = busy || action.busy
  useEffect(() => { alive.current = true; return () => { alive.current = false; pending.current?.abort(); receipt.current = null } }, [])

  async function perform<T>(operation: (signal: AbortSignal) => Promise<T>, success: (result: T) => void) {
    if (pending.current) return
    const generation = useSession.getState().generation, authGeneration = useSession.getState().authGeneration
    const controller = new AbortController(); pending.current = controller
    setBusy(true); setError(undefined)
    try {
      const result = await operation(controller.signal)
      if (alive.current && !controller.signal.aborted && useSession.getState().generation === generation && useSession.getState().authGeneration === authGeneration) success(result)
    } catch (error) {
      if (alive.current && !controller.signal.aborted && useSession.getState().generation === generation && useSession.getState().authGeneration === authGeneration) setError(error)
    } finally {
      if (pending.current === controller) pending.current = null
      if (alive.current && !controller.signal.aborted && useSession.getState().generation === generation && useSession.getState().authGeneration === authGeneration) setBusy(false)
    }
  }
  function changeFormat(value: DataTransferFormat) {
    action.touch(); frozenExport.current = null; setFormat(value); setFile(null); setPreview(null); setFileEpoch(value => value + 1); setError(undefined)
  }
  function readFile(event: ChangeEvent<HTMLInputElement>) {
    action.touch(); setFile(null); setPreview(null); setError(undefined)
    const input = event.currentTarget.files?.[0]
    if (!input) return
    void perform(async () => {
      if (input.size === 0 || input.size > 2_000_000) throw new Error('文件须为1字节至2MB')
      if (!input.name.toLowerCase().endsWith(format === 'CSV' ? '.csv' : '.xlsx')) throw new Error('文件后缀与所选格式不一致')
      const bytes = new Uint8Array(await input.arrayBuffer()), chunks: string[] = []
      for (let offset = 0; offset < bytes.length; offset += 32768) chunks.push(String.fromCharCode(...bytes.subarray(offset, offset + 32768)))
      return { filename: input.name, media_type: format === 'CSV' ? 'text/csv;charset=utf-8' : 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', content_base64: btoa(chunks.join('')) }
    }, setFile)
  }
  function makePreview() {
    if (!file || !canImport || !protectedStorageReady || needUnit && !importTenant) return
    action.touch(); setPreview(null)
    void perform(signal => dataTransferApi.preview(kind, format, file, importTenant, signal), setPreview)
  }
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!reason.trim() || locked || mode === 'import' && (!preview || preview.valid_count === 0 || !canImport) || mode === 'export' && (emptyScope || selectedOnly && selectedIds.length === 0)) return
    frozenExport.current = mode === 'export' ? {
      tenantId: session.audience === 'customer' ? session.user?.tenant_id ?? undefined : undefined,
      filters: exportFilters ? { ...exportFilters } : undefined,
      selectedIds: selectedOnly ? [...selectedIds] : undefined,
    } : null
    receipt.current = null
    void action.run(async (key, signal) => {
      receipt.current = mode === 'import'
        ? await dataTransferApi.confirm(preview!, reason.trim(), key, signal)
        : await dataTransferApi.createExport(kind, format, reason.trim(), key, {
          tenant_id: frozenExport.current?.tenantId,
          filters: frozenExport.current?.filters,
          ...(frozenExport.current?.selectedIds ? { selected_ids: frozenExport.current.selectedIds } : {}),
        }, signal)
    }, () => {
      const saved = receipt.current
      if (!saved) return
      receipt.current = null; frozenExport.current = null; setFile(null); setPreview(null); setReason(''); setFileEpoch(value => value + 1)
      if ('plan_id' in saved) onJobCreated(saved)
      else { setExported(saved); onCompleted?.(saved) }
    })
  }
  const previewColumns: TableColumnsType<DataTransferPreviewRow> = [
    { title: '源文件行', dataIndex: 'row_no', width: 100 },
    { title: '操作', dataIndex: 'operation', width: 190 },
    { title: '校验', dataIndex: 'status', width: 90, render: value => <Tag color={value === 'VALID' ? 'green' : 'orange'}>{value === 'VALID' ? '通过' : '未通过'}</Tag> },
    { title: '原因', dataIndex: 'errors', render: (value: string[]) => value.join('；') || '—' },
  ]
  const applyNow = capability.effect === 'APPLY_ON_CONFIRM'
  return <Drawer open destroyOnHidden size="min(1080px, 100vw)" onClose={onClose} title={`${mode === 'import' ? '导入' : '导出'}${dataTransferLabels[kind]}`}>
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      {exported ? <>
        <Alert type="success" showIcon title="导出快照已生成" description="下载时会重新核验权限与范围，文件生成不代表浏览器已保存。"/>
        <Descriptions size="small" bordered column={{ xs: 1, sm: 2 }} items={[
          { key: 'rows', label: '实际行数', children: exported.row_count }, { key: 'format', label: '格式', children: exported.format },
          { key: 'expires', label: '可下载至', children: exported.expires_at }, { key: 'id', label: '快照ID', children: exported.id },
          { key: 'sha', label: 'SHA-256', children: <Typography.Text copyable className="mono">{exported.content_sha256}</Typography.Text>, span: 2 },
        ]}/>
        {session.permissions.includes('exports:read') ? <DataTransferDownload expectedDigest={exported.content_sha256} load={signal => dataTransferApi.downloadExport(exported.id, signal)}>核验并下载文件</DataTransferDownload> : <Alert type="warning" title="未授予exports:read，当前账户不可下载快照"/>}
      </> : <>
        <Alert type={applyNow && mode === 'import' ? 'warning' : 'info'} showIcon title={mode === 'import' ? dataTransferEffects[capability.effect] : '只读导出'} description={mode === 'import' ? '先校验文件，再明确确认。按逐行结果核对写入；成功行不会因其他行失败而回滚。' : '导出当前页面带入范围内的全部匹配数据，不限当前列表页；勾选模式只包含明确选定的记录。'}/>
        <form className="form-stack" onSubmit={submit}>
          <div className="provider-grid">
            <div className="field"><Label htmlFor="transfer-format">文件格式</Label><Select id="transfer-format" value={format} disabled={locked || !!preview} options={[{ value: 'CSV', label: 'CSV（UTF-8）' }, { value: 'XLSX', label: 'Excel XLSX' }]} onChange={changeFormat}/></div>
            {mode === 'import' && <div className="field"><Label htmlFor="transfer-target-unit">写入目标</Label>{!needUnit ? <Tag>全局主数据 / 配置</Tag> : session.audience === 'customer' || tenantId ? <Typography.Text id="transfer-target-unit">{session.audience === 'customer' ? session.user?.tenant_id : tenantId}</Typography.Text> : session.permissions.includes('tenants:read') ? <Select id="transfer-target-unit" placeholder="明确选择已授权单位" showSearch optionFilterProp="label" value={target || undefined} disabled={locked} loading={units.loading} options={(units.data?.items ?? []).map(unit => ({ value: unit.id, label: `${unit.name} · ${unit.id}` }))} onChange={value => { setTarget(value); setPreview(null); action.touch() }}/> : <Input id="transfer-target-unit" placeholder="输入已授权单位ID" value={target} disabled={locked} onChange={event => { setTarget(event.target.value); setPreview(null); action.touch() }}/>}</div>}
            {mode === 'export' && <div className="field"><Label htmlFor="transfer-selection">导出范围</Label><Select id="transfer-selection" value={selectedOnly ? 'SELECTED' : 'ALL'} disabled={locked} options={[{ value: 'ALL', label: '当前页面范围与筛选' }, { value: 'SELECTED', label: `勾选记录（${selectedIds.length}）`, disabled: selectedIds.length === 0 }]} onChange={value => { setSelectedOnly(value === 'SELECTED'); action.touch(); frozenExport.current = null }}/></div>}
          </div>
          {units.error != null && <ErrorNotice error={units.error} retry={units.refresh}/>}
          {mode === 'export' && emptyScope && <Alert type="warning" title="尚未选择单位或位置范围；请返回页面选择范围" showIcon/>}
          {mode === 'import' && <>
            <div className="list-toolbar">
              <DataTransferDownload disabled={!canTemplate || locked || needUnit && !importTenant} load={signal => dataTransferApi.template(kind, 'CSV', importTenant, signal)}>下载 CSV 模板</DataTransferDownload>
              <DataTransferDownload disabled={!canTemplate || locked || needUnit && !importTenant} load={signal => dataTransferApi.template(kind, 'XLSX', importTenant, signal)}>下载 XLSX 模板</DataTransferDownload>
            </div>
            <Typography.Paragraph type="secondary">最多2MB、10000行；编号、EUI、手机号等须为文本单元格，禁止公式和宏。使用所下载的精确表头。</Typography.Paragraph>
            {!protectedStorageReady && <Alert type="warning" showIcon title="服务器尚未配置导入计划独立加密密钥，禁止上传入库"/>}
            <div className="field"><Label htmlFor="transfer-file">选择文件</Label><Input key={fileEpoch} id="transfer-file" type="file" accept={format === 'CSV' ? '.csv' : '.xlsx'} disabled={!canImport || !protectedStorageReady || locked} onChange={readFile}/></div>
            {file && <Typography.Text type="secondary">已读取：{file.filename}</Typography.Text>}
            <Button type="button" variant="outline" disabled={!file || !canImport || !protectedStorageReady || locked || needUnit && !importTenant} onClick={makePreview}>{busy ? '正在校验…' : '校验并预览'}</Button>
            {preview && <Card size="small" title={`校验结果：${preview.valid_count} 行通过，${preview.error_count} 行未通过`}>
              <Table<DataTransferPreviewRow> rowKey="row_no" size="small" columns={previewColumns} dataSource={preview.rows} scroll={{ x: 700 }} pagination={{ pageSize: 15, showSizeChanger: false }}/>
              <Typography.Paragraph type="secondary" className="mono">源文件 SHA-256：{preview.source_sha256}</Typography.Paragraph>
            </Card>}
          </>}
          {error != null && <ErrorNotice error={error}/>}
          {(mode === 'export' || preview && canImport && preview.valid_count > 0) && <>
            <div className="field"><Label htmlFor="transfer-reason">{mode === 'export' ? '导出用途' : '导入 / 发布原因'}</Label><Input id="transfer-reason" required maxLength={1000} value={reason} disabled={locked} onChange={event => { setReason(event.target.value); action.touch() }}/></div>
            <div className="notice">目标：{dataTransferLabels[kind]} · {format}{mode === 'import' && preview ? ` · ${preview.valid_count} 行通过` : selectedOnly ? ` · 勾选 ${selectedIds.length} 条` : ' · 页面筛选全部匹配'}。{mode === 'export' ? '生成快照不改写业务数据，下载时仍重新核验当前权限与范围。' : applyNow ? '将调用真实配置命令直接改变本地参数，不向硬件发送指令。' : dataTransferEffects[capability.effect]}</div><Button type="submit" disabled={locked || action.busy || !reason.trim() || mode === 'export' && emptyScope}>{action.busy ? '正在提交…' : mode === 'export' ? '确认生成导出快照' : applyNow ? '确认发布并生效' : '确认提交导入任务'}</Button>
          </>}
        </form>
      </>}
    </Space>
  </Drawer>
}
