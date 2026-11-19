import { useRef, useState, type FormEvent } from 'react'
import { useSession } from '@/lib/store'
import type { Device, ScopeUnit } from '@/lib/types'
import { placePath } from '@/lib/place-tree'
import { useMutationAction, OperationConfirmation } from '@/components/identity-common'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ErrorNotice } from '@/components/common'
import { parseVendorDeviceGrid, parseVendorDeviceImport, vendorDeviceCsvTemplate, type VendorDeviceImportRow } from '@/lib/vendor-device-csv'
import { vendorDevicesApi, type LegacyRealtimeProfile, type VendorDeviceSource } from '@/lib/vendor-devices'
import { saveDataTransferFile } from '@/lib/data-transfer'
import { LegacyProfilePicker } from '@/components/vendor-device-profile-picker'

const MAX_FILE_BYTES = 1_000_000

async function readBase64(file: File): Promise<string> {
  const bytes = new Uint8Array(await file.arrayBuffer()), chunks: string[] = []
  for (let offset = 0; offset < bytes.length; offset += 32768) chunks.push(String.fromCharCode(...bytes.subarray(offset, offset + 32768)))
  return btoa(chunks.join(''))
}

export function VendorDeviceImporter({ units, onImported }: {
  units: ScopeUnit[]
  onImported: (devices: Device[]) => void
}) {
  const [source, setSource] = useState<VendorDeviceSource>('LEGACY_REALTIME')
  const [commonProfile, setCommonProfile] = useState<LegacyRealtimeProfile | null>(null)
  const [classicUnitId, setClassicUnitId] = useState('')
  const [preview, setPreview] = useState<{ rows: VendorDeviceImportRow[]; unit: ScopeUnit; modelLabels: Record<string, string> } | null>(null)
  const [error, setError] = useState<unknown>()
  const [parsing, setParsing] = useState(false)
  const [templateBusy, setTemplateBusy] = useState(false)
  const [fileEpoch, setFileEpoch] = useState(0)
  const loadSeq = useRef(0)
  const action = useMutationAction()
  const imported = useRef<Device[] | null>(null)
  const canAssociate = useSession(state => state.permissions.includes('integrations:write'))
  const legacy = source === 'LEGACY_REALTIME'
  const classicUnit = units.find(unit => unit.id === classicUnitId)
  const rows = preview?.rows ?? []
  const places = preview?.unit.places ?? classicUnit?.places ?? []
  function reset() {
    loadSeq.current++
    setPreview(null); setError(undefined); setParsing(false); setFileEpoch(value => value + 1); action.touch()
  }
  function changeSource(next: VendorDeviceSource) {
    if (next === source) return
    setSource(next); setClassicUnitId(''); setCommonProfile(null); reset()
  }
  function changeCommonProfile(profile: LegacyRealtimeProfile | null) {
    setCommonProfile(profile); reset()
  }
  async function loadFile(file?: File) {
    reset()
    if (!file) return
    if (file.size === 0 || file.size > MAX_FILE_BYTES) { setError(new Error('表格文件须为1字节至1MB')); return }
    const name = file.name.toLowerCase()
    const extension = name.endsWith('.xlsx') ? 'XLSX' : name.endsWith('.csv') ? 'CSV' : source === 'LEGACY_REALTIME' && name.endsWith('.xls') ? 'XLS' : ''
    if (!extension) { setError(new Error(source === 'LEGACY_REALTIME' ? '请选择原平台 XLS 或本平台 CSV、XLSX 文件' : '请选择 CSV 或 XLSX 文件')); return }
    if ((!legacy || extension !== 'XLSX') && !classicUnit) { setError(new Error('这个模板不含归属单位，请先在网页选择本次归属单位')); return }
    const sequence = ++loadSeq.current
    setParsing(true)
    try {
      let parsed: VendorDeviceImportRow[], modelLabels: Record<string, string> = {}, unit = classicUnit
      if (extension === 'CSV' && unit) parsed = parseVendorDeviceImport(await file.text(), source, unit.places, commonProfile?.id ?? '')
      else if (extension === 'XLS' && unit) parsed = (await vendorDevicesApi.previewLegacyTerminal(await readBase64(file), unit.id, commonProfile?.id)).rows
      else {
        const workbook = await vendorDevicesApi.previewSpreadsheet(source, await readBase64(file))
        if (workbook.unit) {
          const target = workbook.unit
          unit = units.find(candidate => candidate.id === target.id && candidate.name === target.name)
          if (!unit) throw new Error('Excel 所选单位不在当前可用范围，请刷新后重新核对')
        }
        if (!unit) throw new Error('这份旧版 XLSX 不含归属单位，请展开「旧模板与 CSV 选项」，选择归属单位后重新上传')
        parsed = parseVendorDeviceGrid(workbook.rows, source, unit.places, commonProfile?.id ?? '')
        modelLabels = workbook.modelLabels
      }
      if (sequence === loadSeq.current && unit) setPreview({ rows: parsed, unit, modelLabels })
    } catch (cause) {
      if (sequence === loadSeq.current) setError(cause)
    } finally {
      if (sequence === loadSeq.current) setParsing(false)
    }
  }
  async function downloadTemplate(format: 'CSV' | 'XLSX') {
    setError(undefined); setTemplateBusy(true)
    try {
      if (format === 'CSV') {
        const blob = new Blob([vendorDeviceCsvTemplate(source)], { type: 'text/csv;charset=utf-8' })
        const url = URL.createObjectURL(blob), anchor = document.createElement('a')
        anchor.href = url; anchor.download = source === 'LEGACY_REALTIME' ? '旧烟感批量新建模板.csv' : '海康已有设备关联模板.csv'
        anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 0)
      } else {
        // Excel keeps serial/EUI text cells intact only in XLSX; CSV round-trips may lose column encoding.
        await saveDataTransferFile(await vendorDevicesApi.importTemplate(source))
      }
    } catch (cause) { setError(cause) }
    finally { setTemplateBusy(false) }
  }
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!preview?.rows.length) { action.setError(new Error('请先选择并校验非空表格')); return }
    if (!action.confirming) { action.setError(undefined); action.setConfirming(true); return }
    const body = { source, rows }
    imported.current = null
    void action.run(async (key, signal) => {
      const result = await vendorDevicesApi.importRows(body, key, preview.unit.id, signal)
      if (!Array.isArray(result.items)) throw new Error('批量导入回执缺少设备列表；结果未知，请刷新设备列表核对，勿在未核对前更换内容重复提交')
      imported.current = result.items
    }, () => {
      if (!imported.current) return
      const devices = imported.current
      reset()
      onImported(devices)
    })
  }
  const classicTarget = <div className="field"><Label htmlFor="vendor-device-import-tenant">归属单位</Label><select id="vendor-device-import-tenant" className="native-select" value={classicUnitId} disabled={parsing || action.busy || action.confirming} onChange={event => { setClassicUnitId(event.target.value); reset() }}><option value="">请选择本次归属单位</option>{units.map(unit => <option key={unit.id} value={unit.id}>{unit.name}</option>)}</select></div>
  return <section className="form-stack" aria-label="批量新增与已有设备关联">
    <p className="muted-note">每份文件只导入一个单位，最多 200 台；任一行失败，整批不建档。</p>
    <div className="field"><Label htmlFor="vendor-device-import-source">批量处理方式</Label><select id="vendor-device-import-source" className="native-select" value={source} disabled={action.busy || action.confirming} onChange={event => changeSource(event.target.value as VendorDeviceSource)}><option value="LEGACY_REALTIME">批量新建旧烟感（本平台直接建档）</option><option value="HIKVISION_FIRECLOUD" disabled={!canAssociate}>关联海康已有设备（仅限已发现设备{canAssociate ? '' : '；需云接入管理权限'}）</option></select></div>
    {legacy ? <p>直接下载 Excel。在「设备」表每行先选归属单位，再选择该单位的安装位置和设备型号。同一个单位可向下填充，不用填内部 ID。</p> : classicTarget}
    <div className="identity-row-actions">
      <Button type="button" variant={legacy ? 'default' : 'outline'} disabled={templateBusy || action.busy} onClick={() => void downloadTemplate('XLSX')}>{legacy ? '下载 Excel 模板（带下拉）' : '下载 XLSX 模板'}</Button>
      {!legacy && <Button type="button" variant="outline" disabled={templateBusy || action.busy} onClick={() => void downloadTemplate('CSV')}>下载 CSV 模板</Button>}
    </div>
    {legacy ? <details className="form-stack">
      <summary>旧模板与 CSV 选项</summary>
      <p className="muted-note">仅原平台 XLS、旧版 XLSX 和 CSV 需要在此选择归属单位；新 Excel 以表内单位为准。</p>
      {classicTarget}
      <LegacyProfilePicker value={commonProfile} onChange={changeCommonProfile} label="共用设备型号（可选）" idPrefix="vendor-device-import-profile" disabled={parsing || action.busy || action.confirming} note="仅旧模板和 CSV 使用此设置；新 Excel 以每行下拉选择的型号为准。"/>
      <Button type="button" variant="outline" disabled={templateBusy || action.busy} onClick={() => void downloadTemplate('CSV')}>下载 CSV 模板</Button>
      {!!classicUnit?.places.length && <details className="field"><summary>当前单位真实位置路径参考</summary><ul>{classicUnit.places.map(place => <li key={place.id}>{placePath(place, classicUnit.places)} · <span className="mono">{place.id}</span></li>)}</ul></details>}
    </details> : <p className="muted-note">请勿修改表头或合并单元格。CSV 须保存为 UTF-8。</p>}
    {legacy && <p className="muted-note">原平台「无线终端批量导入模板.xls」仍可上传。</p>}
    <div className="field"><Label htmlFor="vendor-device-import-file">{legacy ? '选择原平台 XLS 或本平台 CSV、XLSX 文件' : '选择 CSV 或 XLSX 文件'}</Label><Input key={fileEpoch} id="vendor-device-import-file" type="file" accept={legacy ? '.xls,.csv,.xlsx' : '.csv,.xlsx'} disabled={(!legacy && !classicUnit?.places.length) || parsing || action.busy || action.confirming} onChange={event => void loadFile(event.target.files?.[0])}/></div>
    {parsing && <p className="muted-note" role="status">正在读取并校验表格…</p>}
    {preview && <div role="status" className="notice"><strong>归属单位：{preview.unit.name}</strong><br/>已预检 {rows.length} 行。确认前未创建设备。</div>}
    {rows.length > 0 && <details className="field"><summary>提交前核对已解析 {rows.length} 行</summary><ul>{rows.map((row, index) => {
      const place = places.find(item => item.id === row.place_id)
      const model = row.legacy_profile_id && (preview && Object.hasOwn(preview.modelLabels, row.legacy_profile_id) ? preview.modelLabels[row.legacy_profile_id] : commonProfile?.id === row.legacy_profile_id ? commonProfile.name : null)
      return <li key={row.serial}>第 {row.source_row ?? index + 2} 行：{row.name} · {row.serial} · {place ? placePath(place, places) : row.place_id}{model ? ` · ${model}` : ''}{row.upstream_device_id ? ` · 上报 ${row.upstream_device_id}` : ''}</li>
    })}</ul></details>}
    {legacy && commonProfile && <p className="muted-note">共用型号：{commonProfile.name} · {commonProfile.manufacturer} / {commonProfile.model} · {commonProfile.legacy_product_id}</p>}
    {!legacy && places.length > 0 && <details className="field"><summary>当前单位真实位置路径参考</summary><ul>{places.map(place => <li key={place.id}>{placePath(place, places)} · <span className="mono">{place.id}</span></li>)}</ul></details>}
    {error != null && <ErrorNotice error={error}/>}
    <form className="form-stack" onSubmit={submit}>
      {!action.confirming ? <Button type="submit" variant="outline" disabled={!rows.length || parsing || action.busy}>核对并确认{legacy ? '批量新建' : '关联'} {rows.length || ''} 行</Button>
        : <OperationConfirmation action={action} title={legacy ? '确认批量新建旧烟感本地资产' : '确认关联海康已有设备'} submitLabel={`确认原子${legacy ? '新建' : '关联'} ${rows.length} 行`} refresh={() => action.setConfirming(false)} warning="提交整批表格至平台原子导入接口；不会调用外部厂商开户、硬件或收费接口。">
          <p>处理方式：{legacy ? '批量新建旧烟感（本平台直接建档）' : '关联海康已有设备（仅限已发现设备）'}</p><p>目标单位：{preview?.unit.name} · 设备数：{rows.length}</p>
        </OperationConfirmation>}
    </form>
  </section>
}
