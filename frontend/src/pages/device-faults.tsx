import { useState, type FormEvent } from 'react'
import { Alert, Button as AntButton, Card, Col, Descriptions, Drawer, Row, Space, Statistic, Table, Tabs, Tag, Typography, type TableColumnsType } from 'antd'
import { AlarmConfigVoice } from '@/components/alarm-config-voice'
import { ConvertFaultToWorkOrder } from '@/components/convert-fault-to-work-order'
import { DataTransferActions } from '@/components/data-transfer-actions'
import { useMutationAction } from '@/components/identity-common'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { PageHelp } from '@/components/page-tools'
import { Empty, ErrorNotice, PageHeading } from '@/components/common'
import { dateTime } from '@/lib/format'
import { useSession } from '@/lib/store'
import { publishObjectChange } from '@/lib/object-events'
import { useResource } from '@/lib/use-resource'
import { queryDeviceFaults, queryDeviceFaultEvents, getFaultHandlingHistory, setFaultHandling, sourceFaultRecoveryText, type FaultEvent, type FaultHandling, type DeviceFaultSummary } from '@/lib/duty-api'
import './duty-faults.css'

export function DeviceFaultsPage() {
  const session = useSession()
  const scope = JSON.stringify([session.generation, session.audience, session.scopeTenantIds, session.scopePlaceIds, session.scopeInitialized])
  return <FaultsContent key={scope}/>
}

function FaultsContent() {
  const session = useSession()
  const [page, setPage] = useState(1)
  const [keyword, setKeyword] = useState(''), [from, setFrom] = useState(''), [to, setTo] = useState('')
  const [applied, setApplied] = useState({ keyword: '', from: '', to: '' })
  const [filterError, setFilterError] = useState('')
  const [selected, setSelected] = useState<DeviceFaultSummary | null>(null)
  const empty = session.scopeInitialized && session.scopeTenantIds.length === 0 && session.scopePlaceIds.length === 0
  const enabled = session.scopeInitialized && !empty && session.permissions.includes('device:read')
  const scopeKey = JSON.stringify([session.scopeTenantIds, session.scopePlaceIds])
  const result = useResource(`device-faults:${scopeKey}:${page}:${JSON.stringify(applied)}`, signal => queryDeviceFaults(page, applied.keyword, applied.from, applied.to, signal), enabled)
  const filters = { unit_ids: session.scopeTenantIds.join(','), place_ids: session.scopePlaceIds.join(','), ...applied }

  function applyFilters(event: FormEvent) {
    event.preventDefault()
    const start = from ? new Date(from) : null, end = to ? new Date(to) : null
    if ((start && !Number.isFinite(start.getTime())) || (end && !Number.isFinite(end.getTime())) || (start && end && start > end)) {
      setFilterError('请选择有效的起止时间，结束时间不得早于开始时间。')
      return
    }
    setFilterError(''); setPage(1); setSelected(null)
    setApplied({ keyword: keyword.trim(), from: start?.toISOString() ?? '', to: end?.toISOString() ?? '' })
  }

  const columns: TableColumnsType<DeviceFaultSummary> = [
    { title: '设备', dataIndex: 'device_name', key: 'device', width: 210, ellipsis: true, render: (value, device) => <Typography.Text title={device.device_id}>{value ?? device.device_id}</Typography.Text> },
    { title: '安装地址', dataIndex: 'device_address', width: 240, ellipsis: true, render: value => value ?? '未记录' },
    { title: '故障类型', key: 'types', width: 290, ellipsis: true, render: (_, device) => {
      const summary = device.fault_types.map(type => `${type.fault_label || '类型待识别'} × ${type.count}`).join('；') || '来源未提供类型'
      return <Typography.Text ellipsis={{ tooltip: summary }}>{summary}</Typography.Text>
    } },
    { title: '历史记录数', dataIndex: 'event_count', width: 100, align: 'right' },
    { title: '最近观察时间', dataIndex: 'latest_observed_at', width: 175, render: value => value ? dateTime(value) : <Typography.Text type="secondary">来源未提供</Typography.Text> },
    { title: '最近接收时间', dataIndex: 'latest_received_at', width: 175, render: value => value ? dateTime(value) : '未记录' },
    { title: '操作', key: 'actions', width: 100, fixed: 'right', render: (_, device) => <AntButton type="link" size="small" onClick={() => setSelected(device)}>查看历史</AntButton> },
  ]

  return <section className="duty-fault-page">
    <PageHeading eyebrow="运行中心 / 故障" title="设备故障" description="筛选发生故障的设备、来源事件及人工处置。">
      <PageHelp title="故障状态口径"><div className="form-stack"><p>只包含已报告的持久化故障；设备离线另行统计。来源观察时间未知时不使用接收时间补齐。</p><p>来源恢复与本地人工处置是独立事实；人工处置不会自动恢复设备物理状态。</p></div></PageHelp>
      <Button variant="outline" onClick={result.refresh}>刷新</Button>
      {enabled && <DataTransferActions kind="FAULTS" tenantId={session.audience === 'customer' ? session.user?.tenant_id ?? undefined : undefined} filters={filters}/>}
    </PageHeading>
    <details className="secondary-controls"><summary>筛选故障设备{(applied.keyword || applied.from || applied.to) && ' · 已启用筛选'}</summary>
    <Card size="small">
      <form className="device-workbench-grid" onSubmit={applyFilters}>
        <div className="field"><Label htmlFor="fault-keyword">设备、地址或故障来源</Label><Input id="fault-keyword" value={keyword} onChange={event => setKeyword(event.target.value)} placeholder="输入设备名、地址或来源方法"/></div>
        <div className="field"><Label htmlFor="fault-from">观察时间从</Label><Input id="fault-from" type="datetime-local" value={from} onChange={event => setFrom(event.target.value)}/></div>
        <div className="field"><Label htmlFor="fault-to">至</Label><Input id="fault-to" type="datetime-local" value={to} onChange={event => setTo(event.target.value)}/></div>
        <Space><Button type="submit">筛选</Button><Button variant="outline" onClick={() => { setKeyword(''); setFrom(''); setTo(''); setApplied({ keyword: '', from: '', to: '' }); setPage(1); setFilterError(''); setSelected(null) }}>重置</Button></Space>
      </form>
      
      {filterError && <Alert type="error" title={filterError} showIcon/>}
    </Card>
    </details>
    {empty ? <Empty title="当前范围未选择"/> : <>
      {result.error != null && <ErrorNotice error={result.error} retry={result.refresh}/>}
      {result.data && <Row gutter={[16, 16]} style={{ margin: '0 0 16px' }}>
        <Col span={12}><Card size="small"><Statistic title="匹配故障设备" value={result.data.total}/></Card></Col>
        <Col span={12}><Card size="small"><Statistic title="本页来源事件" value={result.data.items.reduce((total, device) => total + device.event_count, 0)}/></Card></Col>
      </Row>}
      <Card title="有故障记录的设备" size="small">
        <Table<DeviceFaultSummary> rowKey={device => JSON.stringify([device.tenant_id, device.device_id])} size="small" columns={columns} dataSource={result.data?.items ?? []} loading={result.loading} scroll={{ x: 1290 }} locale={{ emptyText: enabled ? '当前筛选没有已报告的故障' : '范围正在确认' }} pagination={{ current: result.data?.page ?? page, pageSize: result.data?.size ?? 20, total: result.data?.total ?? 0, showSizeChanger: false, onChange: setPage }}/>
      </Card>
    </>}
    {selected && <FaultDeviceDrawer key={JSON.stringify([selected.tenant_id, selected.device_id])} device={selected} onClose={() => setSelected(null)}/>}
  </section>
}

function FaultDeviceDrawer({ device, onClose }: { device: DeviceFaultSummary; onClose: () => void }) {
  const session = useSession()
  const [page, setPage] = useState(1), [tab, setTab] = useState('history')
  const [selected, setSelected] = useState<FaultEvent | null>(null)
  const [selectedIds, setSelectedIds] = useState<string[]>([])
  const [note, setNote] = useState(''), [notice, setNotice] = useState('')
  const action = useMutationAction()
  const canHandle = session.permissions.includes('faults:handle')
  const scopeKey = JSON.stringify([session.scopeTenantIds, session.scopePlaceIds])
  const events = useResource(`device-fault-events:${scopeKey}:${device.tenant_id}:${device.device_id}:${page}`, signal => queryDeviceFaultEvents(device.device_id, page, signal))
  const history = useResource(`fault-handling-history:${selected?.tenant_id}:${selected?.id}`, signal => getFaultHandlingHistory(selected!.id, selected!.tenant_id, signal), !!selected)
  const filters = { unit_ids: session.scopeTenantIds.join(','), place_ids: session.scopePlaceIds.join(','), device_id: device.device_id }

  function inspect(event: FaultEvent) {
    if (action.busy) return
    action.touch(); setSelected(event); setNote(event.handling?.note ?? ''); setNotice(''); setTab('detail')
  }
  function refreshRecord() {
    action.touch(); setSelected(null); setTab('history'); events.refresh()
  }
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!selected || !canHandle) return
    const target = selected
    void action.run((key, signal) => setFaultHandling(target, { expected_revision: target.handling?.revision ?? 0, handled: !target.handling?.handled, note }, key, signal), () => {
      publishObjectChange('events')
      setNotice('处置记录已保存；未修改设备上报的原始故障或恢复事实。')
      action.setConfirming(false); setSelected(null); setTab('history'); events.refresh()
    })
  }

  const eventColumns: TableColumnsType<FaultEvent> = [
    { title: '故障类型', dataIndex: 'fault_label', key: 'type', width: 220, ellipsis: true, render: label => <Typography.Text ellipsis={{ tooltip: label || '类型待识别' }}>{label || '类型待识别'}</Typography.Text> },
    { title: '来源观察时间', dataIndex: 'observed_at', width: 170, render: value => value ? dateTime(value) : '观察时间未知' },
    { title: '平台接收时间', dataIndex: 'received_at', width: 170, render: value => value ? dateTime(value) : '接收时间未知' },
    { title: '来源恢复', key: 'source-recovery', width: 180, render: (_, event) => <Tag color={event.source_recovered === true || Boolean(event.source_recovered_at) ? 'green' : undefined}>{sourceFaultRecoveryText(event, dateTime)}</Tag> },
    { title: '本地处置', key: 'handling', width: 120, render: (_, event) => <Tag color={event.handling?.handled ? 'green' : undefined}>{event.handling?.handled ? '已处置' : event.handling ? '未处置' : '尚未记录'}</Tag> },
    { title: '操作', key: 'inspect', width: 90, fixed: 'right', render: (_, event) => <AntButton type="link" size="small" disabled={action.busy} onClick={() => inspect(event)}>查看详情</AntButton> },
  ]
  const handlingColumns: TableColumnsType<FaultHandling> = [
    { title: '版本', dataIndex: 'revision', width: 70 },
    { title: '处置', dataIndex: 'handled', width: 90, render: value => value ? '已处置' : '未处置' },
    { title: '操作时间', dataIndex: 'updated_at', width: 175, render: dateTime },
    { title: '说明', dataIndex: 'note', render: value => value || '未填写' },
  ]
  return <Drawer open destroyOnHidden size="min(1040px, 100vw)" title={device.device_name ?? device.device_id} onClose={onClose}>
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Text type="secondary">{device.device_address ?? '地址未记录'}</Typography.Text>
      {notice && <Alert type="success" title={notice} showIcon/>}
      <Tabs activeKey={tab} onChange={key => { if (!action.busy) setTab(key) }} items={[
        { key: 'history', label: '全部来源故障历史', children: <>
          <div className="list-toolbar"><Button variant="outline" onClick={events.refresh}>刷新历史</Button><DataTransferActions kind="FAULTS" tenantId={session.audience === 'customer' ? session.user?.tenant_id ?? undefined : undefined} filters={filters} selectedIds={selectedIds}/></div>
          {events.error != null && <ErrorNotice error={events.error} retry={events.refresh}/>}
          <Table<FaultEvent> rowKey="id" size="small" columns={eventColumns} dataSource={events.data?.items ?? []} loading={events.loading} scroll={{ x: 940 }} rowSelection={{ selectedRowKeys: selectedIds, onChange: keys => setSelectedIds(keys.map(String)), preserveSelectedRowKeys: true }} pagination={{ current: events.data?.page ?? page, pageSize: events.data?.size ?? 20, total: events.data?.total ?? 0, showSizeChanger: false, onChange: setPage }}/>
        </> },
        { key: 'detail', label: '事件详情与处置', disabled: !selected, children: selected && <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          <Descriptions size="small" column={{ xs: 1, sm: 2 }} bordered items={[
            { key: 'type', label: '故障类型', children: selected.fault_label || '类型待识别', span: 2 },
            { key: 'observed', label: '来源观察时间', children: selected.observed_at ? dateTime(selected.observed_at) : '观察时间未知' },
            { key: 'received', label: '平台接收时间', children: selected.received_at ? dateTime(selected.received_at) : '接收时间未知' },
            { key: 'recovery', label: '来源恢复', children: sourceFaultRecoveryText(selected, dateTime) },
            { key: 'handling', label: '本地处置', children: selected.handling ? selected.handling.handled ? '已处置' : '未处置' : '尚未记录' },
          ]}/>
          <details className="secondary-controls" style={{ width: '100%' }}>
            <summary>来源与编码</summary>
            <Descriptions size="small" column={{ xs: 1, sm: 2 }} bordered items={[
              { key: 'code', label: '原始故障编码', children: selected.fault_code ?? '来源未提供编码' },
              { key: 'method', label: '来源协议方法', children: selected.method ?? '来源未提供' },
              { key: 'sourceTime', label: '来源报文时间', children: selected.source_message_at ? dateTime(selected.source_message_at) : '来源未提供' },
              { key: 'timeField', label: '报文时间字段', children: selected.source_time_field ?? '未声明' },
              { key: 'id', label: '平台来源事件ID', children: <Typography.Text copyable>{selected.id}</Typography.Text>, span: 2 },
              { key: 'sourceId', label: '厂商故障ID', children: selected.source_fault_id ?? '来源未提供', span: 2 },
            ]}/>
          </details>
          <details className="secondary-controls" style={{ width: '100%' }}>
            <summary>本地处置历史</summary>
            {history.error != null && <ErrorNotice error={history.error} retry={history.refresh}/>}
            <Table<FaultHandling> rowKey="revision" size="small" columns={handlingColumns} dataSource={history.data ?? []} loading={history.loading} pagination={false} scroll={{ x: 600 }}/>
          </details>
          {selected.device_id && <ConvertFaultToWorkOrder key={selected.id} faultId={selected.id} deviceId={selected.device_id} tenantId={selected.tenant_id} defaultTitle={selected.fault_label || undefined}/>}
          <Alert type="info" showIcon title="人工处置不等于设备恢复" description="这里只记录本地处置与审计，不改写来源故障、观察时间或设备物理状态，不向设备发送指令。"/>
          {canHandle && <form className="form-stack" onSubmit={submit}>
            <div className="field"><Label htmlFor="fault-handling-note">处置说明</Label><Input id="fault-handling-note" maxLength={1000} value={note} disabled={action.busy} onChange={event => { setNote(event.target.value); action.touch() }}/></div>
            {action.error != null && <ErrorNotice error={action.error}/>}
            <Button type="submit" disabled={action.busy}>{action.busy ? '保存中…' : selected.handling?.handled ? '重新打开本地处置' : '记录本地处置'}</Button>
          </form>}
          {session.audience === 'management' && session.permissions.includes('alarm-base:read') && <AlarmConfigVoice tenantId={selected.tenant_id} sourceId={selected.id} eventType="FAULT" occurredAt={selected.observed_at ?? '来源未提供发生时间'}/>}
        </Space> },
      ]}/>
    </Space>
  </Drawer>
}
