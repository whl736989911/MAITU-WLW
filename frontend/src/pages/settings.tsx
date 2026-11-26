import { useState, type FormEvent } from 'react'
import { Save, Settings2 } from 'lucide-react'
import { api } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import type { Setting } from '@/lib/types'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Empty, ErrorNotice, Loading, PageHeading, RefreshButton } from '@/components/common'
import { PageHelp } from '@/components/page-tools'
const labels: Record<string, string> = { offline_threshold_minutes: '设备离线判定阈值', default_page_size: '默认列表条数' }
export function SettingsPage() {
  const settings = useResource('settings', signal => api.settings(signal))
  return <><PageHeading eyebrow="SETTINGS · 管理设置" title="全局基础设置" description="这里仅列出全平台通用且已有业务消费者的参数。"><RefreshButton onClick={settings.refresh}/><PageHelp title="设置应到哪里修改"><p>上报超时阈值在此设全局默认；按单位、位置、分组或单台设备设置，请进入「参数覆盖」。高层级覆盖不随全局值变化，未上报设备仍是未激活。</p><p>默认列表条数只支持全局设置。报警、通知与海康的业务设置在各自的工作区维护。</p><p>保存不会开启扫描、发送通知或下发设备参数。</p></PageHelp></PageHeading>{settings.loading ? <Loading/> : settings.error != null ? <ErrorNotice error={settings.error} retry={settings.refresh}/> : settings.data?.items.length ? <div className="settings-grid">{settings.data.items.map(setting => <SettingEditor key={`${setting.key}:${setting.revision}`} setting={setting} refresh={settings.refresh}/>)}</div> : <Empty title="服务端暂无可配置项"/>}</>
}
function SettingEditor({ setting, refresh }: { setting: Setting; refresh: () => void }) {
  const canWrite = useSession(s => s.permissions.includes('settings:write'))
  const [value, setValue] = useState(String(setting.value))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>()
  const [saved, setSaved] = useState(false)
  async function submit(event: FormEvent) {
    event.preventDefault(); setError(undefined); setSaved(false)
    if (value.trim() === '' || !Number.isInteger(Number(value)) || Number(value) < setting.min || Number(value) > setting.max) { setError(new Error(`请输入 ${setting.min} 至 ${setting.max} 之间的整数`)); return }
    setBusy(true)
    try { await api.updateSetting(setting, Number(value)); setSaved(true); refresh() }
    catch (error) { setError(error) }
    finally { setBusy(false) }
  }
  const rangeId = `${setting.key}-range`
  return <Card className="setting-editor">
    <CardHeader>
      <CardTitle><Settings2 className="inline mr-2" size={18}/>{labels[setting.key] ?? setting.key}</CardTitle>
      <p className="muted-note">{setting.description}</p>
    </CardHeader>
    <CardContent>
      <form className="form-stack" onSubmit={submit}>
        <dl className="setting-current"><dt>当前全局值</dt><dd>{setting.value} {setting.unit}</dd></dl>
        {canWrite && <div className="field"><Label htmlFor={setting.key}>设置值 · {setting.unit}</Label>
          <div className="setting-input"><Input id={setting.key} aria-describedby={rangeId} type="number" min={setting.min} max={setting.max} step={1}
            required value={value} onChange={e => { setValue(e.target.value); setSaved(false) }}/><span>{setting.unit}</span></div>
          <p className="muted-note" id={rangeId}>允许范围：{setting.min}–{setting.max} {setting.unit}</p>
        </div>}
        {error != null && <ErrorNotice error={error}/>}
        <div className="setting-footer"><span>版本 {setting.revision}{saved && ' · 已保存'}</span>
          {canWrite && <Button type="submit" disabled={busy}><Save/>{busy ? '保存中…' : '保存设置'}</Button>}
        </div>
      </form>
    </CardContent>
  </Card>
}
