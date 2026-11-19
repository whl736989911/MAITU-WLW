import { jsonFieldText, parseJsonDocument } from '@/lib/json-document'
import { createMutationIntent } from '@/lib/mutation-intent'
import { policyResponse, revisionInteger } from '@/lib/parameter-json'
import { useSession } from '@/lib/store'

export const offlineThresholdPath = '/unit-parameters/offline_threshold_minutes'

export const useUnitParameterWrite = createMutationIntent(raw => {
  parseJsonDocument(raw)
  const policy = policyResponse(jsonFieldText(raw, 'policy')!)
  const tenant = useSession.getState().user?.tenant_id
  if (!tenant || policy.key !== 'offline_threshold_minutes' || policy.scope !== 'TENANT' || policy.tenant_id !== tenant || policy.scope_id !== tenant || typeof policy.has_override !== 'boolean') {
    throw new Error('服务端未确认本单位参数写入结果')
  }
  revisionInteger(policy.revision)
  return policy
})
