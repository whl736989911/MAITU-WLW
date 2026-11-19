import { createMutationIntent } from './mutation-intent'
import { decodeArchiveDetail } from './fire-archives'

export const useArchiveIntent = createMutationIntent(decodeArchiveDetail)
