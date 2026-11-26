import type { Audience } from './types'

export function portalAudience(path: string): Audience {
  return /^\/customer(?:\/login)?\/?$/.test(path) ? 'customer' : 'management'
}

export function portalPath(audience: Audience, login: boolean): string {
  return `/${audience}${login ? '/login' : ''}`
}

// History stores a non-secret tab marker, never credentials, identity or permissions.
export function customerResumeTag() {
  const tag = history.state?.customerResumeTag
  return typeof tag === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(tag) ? tag : null
}

export function setCustomerResumeTag(tag: string | null) {
  if (tag === null && history.state?.customerResumeTag == null) return
  const state = { ...history.state }
  if (tag === null) delete state.customerResumeTag
  else state.customerResumeTag = tag
  history.replaceState(state, '')
}
