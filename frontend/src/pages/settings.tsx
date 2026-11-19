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
  return <><PageHeading eyebrow="SETTINGS · 管理设置" title="系统设置" description="维护平台全局基础值；单位和设备覆盖在设备策略中管理。"><RefreshButton onClick={settings.refresh}/><PageHelp title="全局设置的生效范围"><p>离线策略按设备、单位、全局依次继承；全局变化只影响没有更高层覆盖的设备，下次读取列表或概览生效。未上报设备仍是未激活，不改变物理事实。</p><p>默认列表条数仅支持全局设置。此旧接口按当前会话权限、版本号和审计流程保存，不提供独立幂等回执；单位或设备离线规则请使用参数覆盖。</p><p>保存不会启用离线扫描、发送通知或下发设备参数；开发值不表示已批准的生产阈值。</p></PageHelp></PageHeading>{settings.loading ? <Loading/> : settings.error != null ? <ErrorNotice error={settings.error} retry={settings.refresh}/> : settings.data?.items.length ? <div className="settings-grid">{settings.data.items.map(setting => <SettingEditor key={`${setting.key}:${setting.revision}`} setting={setting} refresh={settings.refresh}/>)}</div> : <Empty title="服务端暂无可配置项"/>}</>
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
  return <Card><CardHeader><CardTitle><Settings2 className="inline mr-2" size={18}/>{labels[setting.key] ?? setting.key}</CardTitle><p className="muted-note">{setting.description}</p></CardHeader><CardContent><form className="form-stack" onSubmit={submit}><div className="field"><Label htmlFor={setting.key}>设置值 · {setting.unit}</Label><div className="setting-input"><Input id={setting.key} type="number" min={setting.min} max={setting.max} step={1} required value={value} onChange={e => { setValue(e.target.value); setSaved(false) }} disabled={!canWrite}/><span>{setting.unit}</span></div><p className="muted-note">允许范围：{setting.min}–{setting.max} {setting.unit} · 版本 {setting.revision}</p></div>{error != null && <ErrorNotice error={error}/>}<div className="setting-footer"><span>{saved ? '已保存' : '变更提交后以服务端校验结果为准'}</span>{canWrite && <Button type="submit" disabled={busy}><Save/>{busy ? '保存中…' : '保存设置'}</Button>}</div></form></CardContent></Card>
}
