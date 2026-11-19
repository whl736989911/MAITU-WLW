import { useEffect, useState, type FormEvent } from 'react'
import { legacyDevicesApi, type LegacyDevice } from '@/lib/legacy-devices'
import { dateTime, reported } from '@/lib/format'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Empty, ErrorNotice, Loading, PageHeading, Pager, Status } from '@/components/common'
import { ConfigurationUnitTarget } from '@/components/configuration-unit-target'

const pageSize = 20

export function LegacyDevicesPage() {
  return <ConfigurationUnitTarget label="查看目标单位">{tenantId => <LegacyUnitDevices tenantId={tenantId}/>}</ConfigurationUnitTarget>
}

function LegacyUnitDevices({ tenantId }: { tenantId: string }) {
  const [items, setItems] = useState<LegacyDevice[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [draft, setDraft] = useState('')
  const [keyword, setKeyword] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [reload, setReload] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    setError(null)
    legacyDevicesApi.list(page, pageSize, keyword, controller.signal, tenantId).then(result => {
      if (controller.signal.aborted) return
      setItems(result.items)
      setTotal(result.total)
      setLoading(false)
    }).catch(reason => {
      if (!controller.signal.aborted) {
        setError(reason)
        setLoading(false)
      }
    })
    return () => controller.abort()
  }, [tenantId, page, keyword, reload])

  function search(event: FormEvent) {
    event.preventDefault()
    setPage(1)
    setKeyword(draft.trim())
  }

  return <main className="space-y-6 p-6">
    <PageHeading eyebrow="LEGACY · 试点设备" title="试点设备列表" description="查看真实试点设备及其最近一次上报记录。"/>
    <Card><CardContent className="pt-6">
      <form className="search-form mb-4" onSubmit={search}>
        <Input aria-label="搜索设备名称或 EUI" placeholder="输入设备名称或 EUI" value={draft} onChange={event => setDraft(event.target.value)}/>
        <Button type="submit">搜索</Button>
      </form>
      {loading ? <Loading/> : error !== null ? <ErrorNotice error={error} retry={() => setReload(value => value + 1)}/> : items.length === 0 ? <Empty title="暂无试点设备">没有符合条件的设备记录。</Empty> : <>
        <Table>
          <TableHeader><TableRow><TableHead>设备名</TableHead><TableHead>EUI</TableHead><TableHead>型号</TableHead><TableHead>场所</TableHead><TableHead>旧产品号</TableHead><TableHead>物理状态</TableHead><TableHead>最后上报</TableHead></TableRow></TableHeader>
          <TableBody>{items.map(device => <TableRow key={device.id}>
            <TableCell>{reported(device.name)}</TableCell>
            <TableCell>{reported(device.device_eui)}</TableCell>
            <TableCell>{reported(device.model)}</TableCell>
            <TableCell>{reported(device.place_name)}</TableCell>
            <TableCell>{reported(device.legacy_product_id)}</TableCell>
            <TableCell><Status value={device.physical_state} dimension="physical"/> </TableCell>
            <TableCell>{dateTime(device.last_message_at)}</TableCell>
          </TableRow>)}</TableBody>
        </Table>
        <Pager page={page} size={pageSize} total={total} onChange={setPage}/>
      </>}
    </CardContent></Card>
  </main>
}
