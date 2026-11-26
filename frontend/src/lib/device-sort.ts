export type DeviceColumnKey = 'eui' | 'name' | 'model' | 'product' | 'version' | 'unit' | 'address' | 'placement' | 'system' | 'category' | 'online' | 'business' | 'customer' | 'battery' | 'last'
export type DeviceSort = { column: DeviceColumnKey; direction: 'asc' | 'desc' }

export const defaultDeviceSort: DeviceSort = { column: 'last', direction: 'desc' }

export function nextDeviceSort(current: DeviceSort, column: DeviceColumnKey): DeviceSort {
  if (column === current.column) return { column, direction: current.direction === 'asc' ? 'desc' : 'asc' }
  return column === 'last' ? defaultDeviceSort : { column, direction: 'asc' }
}

export function applyDeviceSort(query: URLSearchParams, sort?: DeviceSort) {
  if (!sort) return
  query.set('sort', sort.column)
  query.set('direction', sort.direction)
}
