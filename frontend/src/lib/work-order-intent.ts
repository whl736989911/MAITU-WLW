import { createMutationIntent } from './mutation-intent'
import { decodeWorkOrderDetail } from './maintenance'

export const useWorkOrderIntent = createMutationIntent(decodeWorkOrderDetail)
