import { createMutationIntent } from './mutation-intent'
import { decodeApplicationDetail } from './applications'

export const useApplicationIntent = createMutationIntent(decodeApplicationDetail)
