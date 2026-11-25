import { useState } from 'react'
import { useResource } from '@/lib/use-resource'
import { deviceRegistrationsApi, legacyVendorFamily, type RegistrationMode } from '@/lib/device-registrations'
import { Button } from './ui/button'
import { Card, CardContent } from './ui/card'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from './ui/sheet'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from './ui/table'
import { Empty, ErrorNotice, Loading, Pager, RefreshButton } from './common'
import { RegistrationDetailView } from './device-registration-workspace'

const modeLabel: Record<RegistrationMode, string> = { NEW_VENDOR: '厂商新增', MATCH_EXISTING: '匹配已有资源', PRODUCT_MODEL: '产品模型' }
const cloudLabel: Record<string, string> = { NOT_REQUIRED: '无云端', NOT_ATTEMPTED: '未提交', IN_FLIGHT: '提交中/未确认', SUCCEEDED: '云端已接受', FAILED: '失败', UNKNOWN: '结果未知' }
const importedCloudLabel: Record<string, string> = { NOT_REQUIRED: '无云端', NOT_ATTEMPTED: '未核对', IN_FLIGHT: '核对中', SUCCEEDED: '远端核对+导入匹配成功', FAILED: '核对/匹配未通过', UNKNOWN: '核对未确认' }
const localLabel: Record<string, string> = { NOT_READY: '未就绪', READY: '待验收', SUCCEEDED: '验收完成', FAILED: '验收失败' }

export function DeviceRegistrationQueue({ onCompleted }: { onCompleted: () => void }) {
  const [page, setPage] = useState(1)
  const [selected, setSelected] = useState<string | null>(null)
  const list = useResource(`device-registrations:queue:${page}`, signal => deviceRegistrationsApi.list(page, undefined, signal))
  const completed = () => { list.refresh(); onCompleted() }
  return <>
    <div className="version-actions"><p className="muted-note">登记、云端步骤与本地验收分开记录。云端已成功但本地失败时可继续本地验收；UNKNOWN 只允许核对，不能重新开户。</p><RefreshButton onClick={list.refresh}/></div>
    <Card className="list-card"><CardContent>
      {list.loading ? <Loading/> : list.error != null ? <ErrorNotice error={list.error} retry={list.refresh}/> : list.data && (list.data.items.length ? <>
        <Table><TableHeader><TableRow><TableHead>序列号 / 名称</TableHead><TableHead>方式</TableHead><TableHead>云端状态</TableHead><TableHead>本地状态</TableHead><TableHead>单位/版本</TableHead><TableHead>操作</TableHead></TableRow></TableHeader><TableBody>
          {list.data.items.map(item => {
            const family = legacyVendorFamily(item.driver_key)
            const imported = family !== null && item.mode === 'MATCH_EXISTING'
            const cloudLabels = imported ? importedCloudLabel : cloudLabel
            return <TableRow key={item.id}>
              <TableCell><strong className="mono">{item.external_serial}</strong><div className="muted-note">{item.device_name ?? '名称未填'} · {item.driver_key} · {item.id}</div></TableCell>
              <TableCell>{family ? (item.mode === 'MATCH_EXISTING' ? '关联导入旧台账' : '旧台账新增') : modeLabel[item.mode]}</TableCell>
              <TableCell>{cloudLabels[item.cloud_state] ?? item.cloud_state}{item.external_device_id ? <div className="mono muted-note">{item.external_device_id}</div> : null}</TableCell>
              <TableCell>{localLabel[item.local_state] ?? item.local_state}</TableCell>
              <TableCell className="mono">{item.tenant_id ?? '未确定'}<div className="muted-note">{item.product_version_id ?? (imported ? '导入版本待验收' : '版本未定')}</div></TableCell>
              <TableCell><Button variant="ghost" size="sm" onClick={() => setSelected(item.id)}>查看与处理</Button></TableCell>
            </TableRow>
          })}
        </TableBody></Table>
        <Pager page={list.data.page} size={list.data.size} total={list.data.total} onChange={setPage}/>
      </> : <Empty title="暂无待完成接入">接入登记保留在此处，可继续云端核对或本地验收。</Empty>)}
    </CardContent></Card>
    <Sheet open={!!selected} onOpenChange={open => { if (!open) setSelected(null) }}><SheetContent className="review-sheet sm:max-w-2xl">
      {selected && <><SheetHeader className="review-header"><SheetTitle>接入登记处理</SheetTitle><SheetDescription>云端、本地与来源事实分别展示；UNKNOWN 只读核对、不重新开户。</SheetDescription></SheetHeader><RegistrationDetailView registrationId={selected} onChanged={completed}/></>}
    </SheetContent></Sheet>
  </>
}

