import { useEffect, useRef, useState, type ReactNode } from 'react'
import { api } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { scopeTargetTenantId } from '@/lib/operation-target'
import { Empty, ErrorNotice, Loading } from './common'
import { Label } from './ui/label'

export function ConfigurationUnitTarget({ children, label = '配置目标单位' }: { children: (tenantId: string) => ReactNode; label?: string }) {
  const { scopeTenantIds, scopePlaceIds, scopeInitialized, scopeTreeRevision } = useSession()
  const initialScope = useRef({ tenantIds: [...scopeTenantIds], placeIds: [...scopePlaceIds] })
  const tree = useResource('configuration-unit-targets', signal => api.scopeTree(signal), scopeInitialized)
  const seenTreeRevision = useRef(scopeTreeRevision)
  useEffect(() => {
    if (seenTreeRevision.current === scopeTreeRevision) return
    seenTreeRevision.current = scopeTreeRevision
    tree.refresh()
  }, [scopeTreeRevision, tree.refresh])
  const [selected, setSelected] = useState<string | undefined>()
  const units = tree.data ?? []
  useEffect(() => {
    if (!tree.data || selected !== undefined) return
    setSelected(scopeTargetTenantId(tree.data, initialScope.current.tenantIds, initialScope.current.placeIds) ?? '')
  }, [tree.data, selected])
  const target = units.find(unit => unit.id === selected)
  return <>
    <div className="field"><Label htmlFor="configuration-target-unit">{label}</Label><select id="configuration-target-unit" className="native-select" value={target?.id ?? ''} onChange={event => setSelected(event.target.value)}><option value="">请选择已授权单位</option>{units.map(unit => <option key={unit.id} value={unit.id}>{unit.name} · {unit.id}</option>)}</select></div>
    {tree.loading && !tree.data ? <Loading/> : tree.error != null ? <ErrorNotice error={tree.error} retry={tree.refresh}/> : target ? <section key={target.id}>{children(target.id)}</section> : <Empty title="请选择目标单位">多个单位可同时浏览，但本次操作必须明确目标；选择只影响本页，不改变单位/位置筛选或其他操作。</Empty>}
  </>
}
