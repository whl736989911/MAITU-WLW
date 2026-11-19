import { useEffect, useState } from 'react'
import { Alert, Card, Select, Space, Table, Tabs, Tag, Typography, type TableColumnsType } from 'antd'
import { DataTransferActions } from '@/components/data-transfer-actions'
import { DataTransferDownload } from '@/components/data-transfer-download'
import { DataTransferJobStatusTag, DataTransferJobViewer } from '@/components/data-transfer-job'
import { Button } from '@/components/ui/button'
import { Empty, ErrorNotice, PageHeading } from '@/components/common'
import { dataTransferApi, dataTransferEffects, dataTransferLabels, type DataTransferExport, type DataTransferJob, type DataTransferKind } from '@/lib/data-transfer'
import { dateTime } from '@/lib/format'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'

const globalExportKinds: Partial<Record<DataTransferKind, true>> = { ROLES: true, PLATFORM_USERS: true, PRODUCT_DRAFTS: true }

export function BulkIoPage() {
  const session = useSession()
  const allowed = !!session.user && !session.user.must_change_password && session.permissions.some(permission => ['imports:read', 'imports:write', 'exports:read', 'exports:write'].includes(permission))
  if (!allowed) return <Empty title="当前账户无导入导出权限"/>
  return <BulkContent key={`${session.authGeneration}:${session.generation}:${session.audience}`}/>
}

function BulkContent() {
  const session = useSession()
  const [choice, setChoice] = useState<DataTransferKind | null>(null), [jobPage, setJobPage] = useState(1), [exportPage, setExportPage] = useState(1), [selectedJob, setSelectedJob] = useState<string | null>(null)
  const canReadJobs = session.permissions.includes('imports:read'), canReadExports = session.permissions.includes('exports:read')
  const metadata = useResource('bulk-transfer-capabilities', signal => dataTransferApi.capabilities(signal))
  const jobs = useResource(`bulk-transfer-jobs:${jobPage}`, signal => dataTransferApi.jobs(jobPage, signal), canReadJobs)
  const exports = useResource(`bulk-transfer-exports:${exportPage}`, signal => dataTransferApi.exports(exportPage, signal), canReadExports)
  const available = metadata.data?.kinds ?? [], selected = available.find(item => item.kind === choice) ?? available[0]
  const filters = selected && !globalExportKinds[selected.kind] ? { unit_ids: session.scopeTenantIds.join(','), place_ids: session.scopePlaceIds.join(',') } : undefined
  const running = jobs.data?.items.some(job => job.status === 'QUEUED' || job.status === 'RUNNING') ?? false
  useEffect(() => {
    if (!running) return
    const timer = window.setInterval(() => { if (!jobs.loading) jobs.refresh() }, 2000)
    return () => window.clearInterval(timer)
  }, [running, jobs.loading, jobs.refresh])

  const jobColumns: TableColumnsType<DataTransferJob> = [
    { title: '数据类型', dataIndex: 'kind', width: 160, render: (value: DataTransferKind) => dataTransferLabels[value] },
    { title: '目标单位', dataIndex: 'tenant_id', width: 185, ellipsis: true, render: value => value ?? '全局主数据 / 配置' },
    { title: '实际状态', dataIndex: 'status', width: 160, render: (_, job) => <DataTransferJobStatusTag status={job.status}/> },
    { title: '总行数', dataIndex: 'row_count', width: 85, align: 'right' },
    { title: '成功', dataIndex: 'success_count', width: 80, align: 'right' },
    { title: '未成功', dataIndex: 'error_count', width: 85, align: 'right' },
    { title: '创建时间', dataIndex: 'created_at', width: 175, render: value => dateTime(value) },
    { title: '操作', key: 'details', width: 110, fixed: 'right', render: (_, job) => <Button variant="ghost" size="sm" onClick={() => setSelectedJob(job.id)}>逐行结果</Button> },
  ]
  const exportColumns: TableColumnsType<DataTransferExport> = [
    { title: '数据类型', dataIndex: 'kind', width: 160, render: (value: DataTransferKind) => dataTransferLabels[value] },
    { title: '单位范围', dataIndex: 'tenant_id', width: 170, ellipsis: true, render: value => value ?? '创建时的授权与筛选范围' },
    { title: '格式', dataIndex: 'format', width: 75 },
    { title: '实际行数', dataIndex: 'row_count', width: 95, align: 'right' },
    { title: '创建时间', dataIndex: 'created_at', width: 175, render: value => dateTime(value) },
    { title: '可下载至', dataIndex: 'expires_at', width: 175, render: value => dateTime(value) },
    { title: '状态', dataIndex: 'status', width: 90, render: value => <Tag color={value === 'COMPLETED' ? 'green' : undefined}>{value === 'COMPLETED' ? '已生成' : '已过期'}</Tag> },
    { title: '操作', key: 'download', width: 100, fixed: 'right', render: (_, file) => <DataTransferDownload disabled={file.status === 'EXPIRED'} expectedDigest={file.content_sha256} load={signal => dataTransferApi.downloadExport(file.id, signal)}>下载</DataTransferDownload> },
  ]
  return <>
    <PageHeading eyebrow={session.audience === 'management' ? '系统治理 / 数据交换' : '我的 / 数据交换'} title="导入与导出" description="校验、确认、逐行结果与下载记录。只展示当前账户仍获授权的任务和文件。">
      <Button variant="outline" onClick={() => { metadata.refresh(); if (canReadJobs) jobs.refresh(); if (canReadExports) exports.refresh() }}>刷新</Button>
    </PageHeading>
    {metadata.error != null && <ErrorNotice error={metadata.error} retry={metadata.refresh}/>}
    <Tabs items={[
      ...(canReadJobs ? [{ key: 'imports', label: '我的导入任务', children: <Card size="small">
        {jobs.error != null && <ErrorNotice error={jobs.error} retry={jobs.refresh}/>}
        <Table<DataTransferJob> size="small" rowKey="id" columns={jobColumns} dataSource={jobs.data?.items ?? []} loading={jobs.loading} scroll={{ x: 1140 }} pagination={{ current: jobs.data?.page ?? jobPage, pageSize: jobs.data?.size ?? 20, total: jobs.data?.total ?? 0, showSizeChanger: false, onChange: setJobPage }}/>
      </Card> }] : []),
      ...(canReadExports ? [{ key: 'exports', label: '我的导出文件', children: <Card size="small">
        {exports.error != null && <ErrorNotice error={exports.error} retry={exports.refresh}/>}
        <Table<DataTransferExport> size="small" rowKey="id" columns={exportColumns} dataSource={exports.data?.items ?? []} loading={exports.loading} scroll={{ x: 1140 }} pagination={{ current: exports.data?.page ?? exportPage, pageSize: exports.data?.size ?? 20, total: exports.data?.total ?? 0, showSizeChanger: false, onChange: setExportPage }}/>
      </Card> }] : []),
    ]}/>
    <Card size="small" title="新建数据操作" loading={metadata.loading}>
      {selected ? <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        <Space wrap><Select aria-label="数据类型" value={selected.kind} style={{ width: 260 }} options={available.map(item => ({ value: item.kind, label: dataTransferLabels[item.kind] }))} onChange={setChoice}/><DataTransferActions key={selected.kind} kind={selected.kind} filters={filters} onCompleted={receipt => { if ('plan_id' in receipt) jobs.refresh(); else exports.refresh() }}/></Space>
        <Typography.Text type="secondary">{dataTransferEffects[selected.effect]}。{selected.tenant_required ? '导入时另行明确写入单位；不会任意选取第一家单位。' : '全局数据无需借用单位作为写入目标。'}</Typography.Text>
        {session.audience === 'customer' && <Alert type="info" showIcon title="仅限本单位及已授权设备" description="客户入口不提供平台用户、全局角色、厂商密钥或全局通知通道导入。"/>}
      </Space> : <Empty title="当前没有获授权的数据操作类型">仍可在下方查看允许读取的既有记录。</Empty>}
    </Card>
    {selectedJob && <DataTransferJobViewer jobId={selectedJob} onClose={() => setSelectedJob(null)} onChanged={jobs.refresh}/>}
  </>
}
