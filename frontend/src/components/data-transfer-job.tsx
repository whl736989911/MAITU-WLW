import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Alert, Card, Descriptions, Drawer, Space, Table, Tag, Typography, type TableColumnsType } from 'antd'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ErrorNotice } from '@/components/common'
import { useMutationAction } from '@/components/identity-common'
import { DataTransferDownload } from '@/components/data-transfer-download'
import { dataTransferApi, dataTransferEffects, dataTransferLabels, type DataTransferJob, type DataTransferJobRow, type DataTransferJobStatus } from '@/lib/data-transfer'
import { useResource } from '@/lib/use-resource'
import { useSession } from '@/lib/store'
import { dateTime } from '@/lib/format'

export const dataTransferJobLabels: Record<DataTransferJobStatus, string> = { QUEUED: '已排队', RUNNING: '执行中', COMPLETED: '全部成功', COMPLETED_WITH_ERRORS: '部分行未成功', RECONFIRM_REQUIRED: '等待重新确认', CANCELLED: '已停止剩余行' }
const jobColors: Record<DataTransferJobStatus, string | undefined> = { QUEUED: 'blue', RUNNING: 'processing', COMPLETED: 'green', COMPLETED_WITH_ERRORS: 'orange', RECONFIRM_REQUIRED: 'gold', CANCELLED: undefined }
const rowLabels: Record<DataTransferJobRow['status'], string> = { PENDING: '待处理', SUCCEEDED: '成功', FAILED: '未成功', INVALID: '预览未通过' }

export function DataTransferJobStatusTag({ status }: { status: DataTransferJobStatus }) {
  return <Tag color={jobColors[status]}>{dataTransferJobLabels[status]}</Tag>
}

export function DataTransferJobViewer({ jobId, onClose, onChanged }: { jobId: string; onClose: () => void; onChanged?: (job?: DataTransferJob) => void }) {
  const generation = useSession(state => state.generation)
  return <JobContent key={`${generation}:${jobId}`} jobId={jobId} onClose={onClose} onChanged={onChanged}/>
}

function JobContent({ jobId, onClose, onChanged }: { jobId: string; onClose: () => void; onChanged?: (job?: DataTransferJob) => void }) {
  const session = useSession()
  const job = useResource(`transfer-job:${jobId}`, signal => dataTransferApi.job(jobId, signal))
  const rows = useResource(`transfer-job-rows:${jobId}`, signal => dataTransferApi.rows(jobId, signal))
  const capabilities = useResource('transfer-job-capabilities', signal => dataTransferApi.capabilities(signal))
  const [control, setControl] = useState<'reconfirm' | 'cancel' | null>(null)
  const [reason, setReason] = useState(''), [notice, setNotice] = useState('')
  const action = useMutationAction()
  const current = job.data
  const capability = capabilities.data?.kinds.find(item => item.kind === current?.kind)
  const canControl = session.permissions.includes('imports:write') && !!capability?.write_permission && session.permissions.includes(capability.write_permission)
  const running = current?.status === 'QUEUED' || current?.status === 'RUNNING'
  const resumable = current?.status === 'RECONFIRM_REQUIRED' || current?.status === 'COMPLETED_WITH_ERRORS'
  const hasRemainingRows = rows.data?.some(row => row.status === 'PENDING' || row.status === 'FAILED') ?? false
  const settledVersion = useRef<string | null>(null)
  useEffect(() => {
    if (!current || current.status === 'QUEUED' || current.status === 'RUNNING') return
    const version = `${current.status}:${current.success_count}:${current.error_count}:${current.updated_at}`
    if (settledVersion.current === version) return
    settledVersion.current = version
    onChanged?.(current)
  }, [current, onChanged])

  useEffect(() => {
    if (!running) return
    const timer = window.setInterval(() => { if (!job.loading) job.refresh(); if (!rows.loading) rows.refresh() }, 2000)
    return () => window.clearInterval(timer)
  }, [running, job.loading, job.refresh, rows.loading, rows.refresh])

  function chooseControl(value: 'reconfirm' | 'cancel') {
    action.touch(); setReason(''); setNotice(''); setControl(value)
  }
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!current || !control || !canControl || !reason.trim()) return
    void action.run((key, signal) => dataTransferApi.control(current, control, reason.trim(), key, signal), () => {
      setNotice(control === 'cancel' ? '已请求停止剩余行；以实际任务及逐行结果为准。' : '已重新确认；已成功行不会重新执行。')
      setControl(null); setReason(''); job.refresh(); rows.refresh(); onChanged?.()
    })
  }
  const columns: TableColumnsType<DataTransferJobRow> = [
    { title: '源文件行', dataIndex: 'row_no', width: 95 },
    { title: '结果', dataIndex: 'status', width: 135, render: (value: DataTransferJobRow['status']) => <Tag color={value === 'SUCCEEDED' ? 'green' : value === 'FAILED' || value === 'INVALID' ? 'orange' : undefined}>{rowLabels[value]}</Tag> },
    { title: '写入结果ID', dataIndex: 'result_id', width: 220, ellipsis: true, render: value => value ?? '未生成' },
    { title: '原因', dataIndex: 'errors', render: (value: string[]) => value.length ? value.join('；') : '—' },
  ]
  return <Drawer open destroyOnHidden size="min(1080px, 100vw)" onClose={onClose} title={current ? `${dataTransferLabels[current.kind]} · 导入任务` : '导入任务'}>
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      {job.error != null && <ErrorNotice error={job.error} retry={job.refresh}/>}
      {capabilities.error != null && <ErrorNotice error={capabilities.error} retry={capabilities.refresh}/>}
      {notice && <Alert type="info" title={notice} showIcon/>}
      {current && <>
        <Descriptions bordered size="small" column={{ xs: 1, sm: 2 }} items={[
          { key: 'status', label: '实际状态', children: <DataTransferJobStatusTag status={current.status}/> },
          { key: 'counts', label: '逐行进度', children: `共 ${current.row_count} 行 · 成功 ${current.success_count} · 未成功 ${current.error_count}` },
          { key: 'scope', label: '目标单位', children: current.tenant_id ?? '全局配置' },
          { key: 'effect', label: '生效方式', children: capability ? dataTransferEffects[capability.effect] : '正在核验能力' },
          { key: 'created', label: '创建时间', children: dateTime(current.created_at) },
          { key: 'updated', label: '更新时间', children: dateTime(current.updated_at) },
          { key: 'id', label: '任务ID', children: <Typography.Text copyable>{current.id}</Typography.Text>, span: 2 },
          { key: 'hash', label: '源文件SHA-256', children: <Typography.Text copyable className="mono">{current.source_sha256}</Typography.Text>, span: 2 },
        ]}/>
        <div className="list-toolbar">
          <Button variant="outline" onClick={() => { job.refresh(); rows.refresh() }}>刷新实际结果</Button>
          <DataTransferDownload load={signal => dataTransferApi.errorsDownload(jobId, 'CSV', signal)}>错误报告 CSV</DataTransferDownload>
          <DataTransferDownload load={signal => dataTransferApi.errorsDownload(jobId, 'XLSX', signal)}>错误报告 XLSX</DataTransferDownload>
          {canControl && resumable && <Button disabled={action.busy || !hasRemainingRows} onClick={() => chooseControl('reconfirm')}>继续未成功行</Button>}
          {canControl && (running || resumable) && <Button variant="destructive" disabled={action.busy} onClick={() => chooseControl('cancel')}>停止剩余行</Button>}
        </div>
        {resumable && <Alert type="warning" showIcon title="重新确认不会重做成功行" description="预览过期、字段错误或版本已变更时，请下载错误报告，修正对应行后重新上传；不会自动绕过权限或版本冲突。"/>}
      </>}
      {control && current && <Card size="small" title={control === 'cancel' ? '停止剩余行' : '继续未成功行'}>
        <form className="form-stack" onSubmit={submit}>
          <div className="field"><Label htmlFor="transfer-control-reason">操作原因</Label><Input id="transfer-control-reason" value={reason} maxLength={1000} required disabled={action.busy} onChange={event => { setReason(event.target.value); action.touch() }}/></div>
          <div className="notice">目标：{dataTransferLabels[current.kind]} · {current.id}。{control === 'cancel' ? '已经成功写入的数据不回滚。' : '只执行未成功行，仍重新校验当前权限、原始配置版本和预览有效期。'}</div><Space><Button type="submit" disabled={action.busy}>{action.busy ? '正在提交…' : control === 'cancel' ? '确认停止剩余行' : '确认继续未成功行'}</Button><Button variant="outline" onClick={() => { action.touch(); setControl(null) }}>取消操作</Button></Space>
        </form>
      </Card>}
      {rows.error != null && <ErrorNotice error={rows.error} retry={rows.refresh}/>}
      <Card size="small" title="逐行实际结果"><Table<DataTransferJobRow> size="small" rowKey="row_no" columns={columns} dataSource={rows.data ?? []} loading={rows.loading || job.loading} scroll={{ x: 770 }} pagination={{ pageSize: 20, showSizeChanger: false }}/></Card>
    </Space>
  </Drawer>
}
