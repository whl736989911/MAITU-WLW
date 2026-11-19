import { request } from './api'
import type { Paginated, Product } from './types'

export type ProductLifecycleState = Product['state']
export interface ProductMetadataBody { name: string; description: string; expected_revision: number; reason: string }
export interface ProductLifecycleBody { expected_revision: number; reason: string }

export function productState(product: Product): ProductLifecycleState {
  if (product.state === 'ACTIVE' || product.state === 'ARCHIVED') return product.state
  throw new Error('产品生命周期状态响应未确认')
}

export function productsByState(page: number, keyword: string, state: 'ACTIVE' | 'ARCHIVED' | 'ALL', signal?: AbortSignal) {
  const query = new URLSearchParams({ page: String(page), state })
  if (keyword) query.set('keyword', keyword)
  return request<Paginated<Product>>(`/management/products?${query}`, { signal })
}
export function updateProductMetadata(id: string, body: ProductMetadataBody, key: string, signal?: AbortSignal) {
  return request<Product>(`/management/products/${encodeURIComponent(id)}`, { method: 'PUT', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal })
}
export function archiveProduct(id: string, body: ProductLifecycleBody, key: string, signal?: AbortSignal) {
  return request<Product>(`/management/products/${encodeURIComponent(id)}/archive`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal })
}
export function reactivateProduct(id: string, body: ProductLifecycleBody, key: string, signal?: AbortSignal) {
  return request<Product>(`/management/products/${encodeURIComponent(id)}/reactivate`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body), signal })
}
