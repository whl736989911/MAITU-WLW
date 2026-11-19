import { createMutationIntent } from './mutation-intent'
import { parseRetentionPolicy, parseRetentionRun } from './retention'

/** Two canonical stores: policy configuration and the preview/execute run chain. Keys and bodies are frozen. */
export const useRetentionPolicyIntent = createMutationIntent(parseRetentionPolicy)
export const useRetentionRunIntent = createMutationIntent(parseRetentionRun)
