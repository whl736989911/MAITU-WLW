import { Landmark, MapPin } from 'lucide-react'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { customerAssetsApi } from '@/lib/customer-assets'
import { Card, CardContent } from '@/components/ui/card'
import { Empty, ErrorNotice, Loading } from '@/components/common'
import './customer.css'

export function CustomerUnitSummary() {
  const { audience, user, permissions } = useSession()
  if (audience !== 'customer' || !user?.tenant_id || !permissions.includes('units:read')) return null
  return <Summary tenantId={user.tenant_id}/>
}

function Summary({ tenantId }: { tenantId: string }) {
  const revision = useSession(s => s.scopeTreeRevision)
  const scope = useResource(`customer-unit-summary:${tenantId}:${revision}`, signal => customerAssetsApi.scopeTree(signal))
  const unit = scope.data?.find(item => item.id === tenantId)
  return <Card><CardContent className="customer-unit-card">
    {scope.loading ? <Loading/> : scope.error != null ? <ErrorNotice error={scope.error} retry={scope.refresh}/> : !unit ? <Empty title="未取得本单位资料"/> : <>
      <span className="customer-record-icon"><Landmark size={19} aria-hidden="true"/></span>
      <div className="customer-record-label">
        <h2>本单位</h2>
        <strong>{unit.name}</strong>
        <small><MapPin size={13} aria-hidden="true"/>授权可见位置 {unit.places.length} 处</small>
      </div>
    </>}
  </CardContent></Card>
}
