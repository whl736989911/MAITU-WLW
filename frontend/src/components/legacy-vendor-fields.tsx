import { Input } from './ui/input'
import { Label } from './ui/label'
import type { LegacyVendorDraft } from '@/lib/device-registrations'

/**
 * Typed old-ledger add fields shared by management and customer registration. Values are only ever
 * composed into the exact TTL/TSF payload the drivers validate; no free-form JSON is accepted.
 * LoRa credentials are write-only and live in a collapsed section.
 */
export function LegacyVendorFields({ draft, onChange }: { draft: LegacyVendorDraft; onChange: (draft: LegacyVendorDraft) => void }) {
  function setTtl<K extends keyof LegacyVendorDraft['ttl']>(key: K, value: LegacyVendorDraft['ttl'][K]) {
    onChange({ ...draft, ttl: { ...draft.ttl, [key]: value } })
  }
  function setTtlNetwork(value: string) {
    const cleared = value === '1' ? { devAddr: '', nwkSKey: '', appSKey: '' }
      : value === '2' ? { appKey: '', appEui: '' }
      : { appKey: '', appEui: '', devAddr: '', nwkSKey: '', appSKey: '' }
    onChange({ ...draft, ttl: { ...draft.ttl, networkModel: value, ...cleared } })
  }
  function setTsf<K extends keyof LegacyVendorDraft['tsf']>(key: K, value: LegacyVendorDraft['tsf'][K]) {
    onChange({ ...draft, tsf: { ...draft.tsf, [key]: value } })
  }
  if (draft.family === 'LEGACY_TTL') {
    const ttl = draft.ttl
    return <>
      <h3>旧TTL台账字段</h3>
      <div className="field"><Label htmlFor="ttl-product">产品ID(dPid)</Label><Input id="ttl-product" required inputMode="numeric" value={ttl.productId} onChange={event => setTtl('productId', event.target.value)}/></div>
      <div className="field"><Label htmlFor="ttl-app">应用ID(dAid)</Label><Input id="ttl-app" required inputMode="numeric" value={ttl.applicationId} onChange={event => setTtl('applicationId', event.target.value)}/></div>
      <div className="field"><Label htmlFor="ttl-platform">平台ID(dPlatform)</Label><Input id="ttl-platform" required inputMode="numeric" value={ttl.platformId} onChange={event => setTtl('platformId', event.target.value)}/></div>
      <div className="field"><Label htmlFor="ttl-network">网络模式(dNetworkModel)</Label><select id="ttl-network" className="native-select" required value={ttl.networkModel} onChange={event => setTtlNetwork(event.target.value)}><option value="">请选择</option><option value="0">0 · NB-IoT</option><option value="1">1 · OTAA</option><option value="2">2 · ABP</option><option value="101">101 · 其他</option></select></div>
      <details className="device-filter-drawer"><summary>旧TTL可选台账字段</summary><div className="form-stack">
        <div className="field"><Label htmlFor="ttl-parent">父设备ID(dParentId，可选)</Label><Input id="ttl-parent" inputMode="numeric" value={ttl.parentId} onChange={event => setTtl('parentId', event.target.value)}/></div>
        <div className="field"><Label htmlFor="ttl-node">节点号(dNode，可选)</Label><Input id="ttl-node" inputMode="numeric" value={ttl.node} onChange={event => setTtl('node', event.target.value)}/></div>
        <div className="field"><Label htmlFor="ttl-category">产品类别(dProductCategory，可选)</Label><Input id="ttl-category" inputMode="numeric" value={ttl.productCategory} onChange={event => setTtl('productCategory', event.target.value)}/></div>
        <div className="field"><Label htmlFor="ttl-describe">说明(dDescribe，可选)</Label><textarea id="ttl-describe" rows={2} maxLength={500} value={ttl.describe} onChange={event => setTtl('describe', event.target.value)}/></div>
      </div></details>
      <details className="device-filter-drawer"><summary>高级LoRa凭据（写-only，整体加密保存）</summary><div className="form-stack">
        <p className="muted-note">凭据只用于台账add；服务端整体加密保存，详情仅显示字段名，永不回显值。厂商未发布必填矩阵，留空由厂商按型号校验。</p>
        <div className="field"><Label htmlFor="ttl-profile">Profile（可选）</Label><Input id="ttl-profile" maxLength={100} value={ttl.profile} onChange={event => setTtl('profile', event.target.value)}/></div>
        {ttl.networkModel === '1' && <>
          <div className="field"><Label htmlFor="ttl-appkey">OTAA AppKey（32位十六进制）</Label><Input id="ttl-appkey" type="password" autoComplete="off" maxLength={32} value={ttl.appKey} onChange={event => setTtl('appKey', event.target.value)}/></div>
          <div className="field"><Label htmlFor="ttl-appeui">OTAA AppEUI（16位十六进制）</Label><Input id="ttl-appeui" type="password" autoComplete="off" maxLength={16} value={ttl.appEui} onChange={event => setTtl('appEui', event.target.value)}/></div>
        </>}
        {ttl.networkModel === '2' && <>
          <div className="field"><Label htmlFor="ttl-devaddr">ABP DevAddr（8位十六进制）</Label><Input id="ttl-devaddr" type="password" autoComplete="off" maxLength={8} value={ttl.devAddr} onChange={event => setTtl('devAddr', event.target.value)}/></div>
          <div className="field"><Label htmlFor="ttl-nwkskey">ABP NwkSKey（32位十六进制）</Label><Input id="ttl-nwkskey" type="password" autoComplete="off" maxLength={32} value={ttl.nwkSKey} onChange={event => setTtl('nwkSKey', event.target.value)}/></div>
          <div className="field"><Label htmlFor="ttl-appskey">ABP AppSKey（32位十六进制）</Label><Input id="ttl-appskey" type="password" autoComplete="off" maxLength={32} value={ttl.appSKey} onChange={event => setTtl('appSKey', event.target.value)}/></div>
        </>}
        {ttl.networkModel !== '1' && ttl.networkModel !== '2' && <p className="muted-note">只有OTAA/ABP网络模式可填写LoRa凭据；其他模式填写会被拒绝。</p>}
      </div></details>
    </>
  }
  const tsf = draft.tsf
  return <>
    <h3>旧TSF台账字段</h3>
    <div className="field"><Label htmlFor="tsf-product">产品ID(dProductId)</Label><Input id="tsf-product" required inputMode="numeric" value={tsf.productId} onChange={event => setTsf('productId', event.target.value)}/></div>
    <div className="field"><Label htmlFor="tsf-app">应用ID(dAid)</Label><Input id="tsf-app" required inputMode="numeric" value={tsf.applicationId} onChange={event => setTsf('applicationId', event.target.value)}/></div>
    <div className="field"><Label htmlFor="tsf-platform">平台ID(dPlatform)</Label><Input id="tsf-platform" required inputMode="numeric" value={tsf.platformId} onChange={event => setTsf('platformId', event.target.value)}/></div>
    <div className="field"><Label htmlFor="tsf-network">网络模式(dNetworkModel)</Label><Input id="tsf-network" required inputMode="numeric" value={tsf.networkModel} onChange={event => setTsf('networkModel', event.target.value)}/><p className="muted-note">TSF网络模式取值未由公开合同限定，按台账实际值填写。</p></div>
    <div className="field"><Label htmlFor="tsf-place">场所ID(dPid，注意不是产品ID)</Label><Input id="tsf-place" required inputMode="numeric" value={tsf.placeId} onChange={event => setTsf('placeId', event.target.value)}/></div>
    <div className="field"><Label htmlFor="tsf-node">节点号(dNode)</Label><Input id="tsf-node" inputMode="numeric" value={tsf.node} onChange={event => setTsf('node', event.target.value)}/></div>
    <div className="field"><Label htmlFor="tsf-parent">父设备ID(dParentId)</Label><Input id="tsf-parent" inputMode="numeric" value={tsf.parentId} onChange={event => setTsf('parentId', event.target.value)}/><p className="muted-note">节点号与父设备ID至少填写一个。</p></div>
    <div className="field"><Label htmlFor="tsf-address">安装地址(dAddress)</Label><Input id="tsf-address" required maxLength={500} value={tsf.address} onChange={event => setTsf('address', event.target.value)}/></div>
    <div className="field"><Label htmlFor="tsf-gps">GPS坐标(dGps)</Label><Input id="tsf-gps" required maxLength={200} value={tsf.gps} onChange={event => setTsf('gps', event.target.value)}/></div>
    <div className="field"><Label htmlFor="tsf-system">所属系统(dChildSystem)</Label><Input id="tsf-system" required maxLength={100} value={tsf.childSystem} onChange={event => setTsf('childSystem', event.target.value)}/></div>
    <details className="device-filter-drawer"><summary>旧TSF可选台账字段</summary><div className="form-stack">
      <div className="field"><Label htmlFor="tsf-company">单位ID(dCompanyId，可选)</Label><Input id="tsf-company" inputMode="numeric" value={tsf.companyId} onChange={event => setTsf('companyId', event.target.value)}/></div>
      <div className="field"><Label htmlFor="tsf-install">安装信息(dInstallMsg，可选)</Label><textarea id="tsf-install" rows={2} maxLength={1000} value={tsf.installMsg} onChange={event => setTsf('installMsg', event.target.value)}/></div>
      <div className="field"><Label htmlFor="tsf-show">台账显示(dShow)</Label><select id="tsf-show" className="native-select" value={tsf.show} onChange={event => setTsf('show', event.target.value as '' | 'true' | 'false')}><option value="">未指定</option><option value="true">显示</option><option value="false">不显示</option></select></div>
      <div className="field"><Label htmlFor="tsf-push">推送(isPush)</Label><select id="tsf-push" className="native-select" value={tsf.push} onChange={event => setTsf('push', event.target.value as '' | 'true' | 'false')}><option value="">未指定</option><option value="true">开启</option><option value="false">关闭</option></select></div>
      <div className="field"><Label htmlFor="tsf-contacts">联系人ID(contactIds，逗号分隔，可选)</Label><Input id="tsf-contacts" value={tsf.contactIds} onChange={event => setTsf('contactIds', event.target.value)}/></div>
    </div></details>
  </>
}
