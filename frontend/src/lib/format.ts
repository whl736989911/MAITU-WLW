export function reported(value: unknown): string {
  return value === null || value === undefined || value === '' ? '未上报' : String(value)
}
export function batteryPolicyLabel(status: 'NOT_CONFIGURED' | 'NO_DATA' | 'LOW' | 'NORMAL') {
  return ({ NOT_CONFIGURED: '未配置阈值（NOT_CONFIGURED）', NO_DATA: '无电量数据（NO_DATA）', LOW: '低电量（LOW）', NORMAL: '电量正常（NORMAL）' } as const)[status]
}
export function dateTime(value: string | null | undefined) {
  if (!value) return '未上报'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '未上报' : date.toLocaleString('zh-CN', { hour12: false })
}
export function displayDecimal(text: string) {
  // Presentation only: retain all significant digits and exponent literals.
  if (!/^-?(?:0|[1-9]\d*)(?:\.\d+)?$/.test(text) || !text.includes('.')) return text
  return text.replace(/0+$/, '').replace(/\.$/, '')
}
export function battery(value: number | null | undefined, exact?: string) {
  if (exact === 'null' || (exact === undefined && value == null)) return '未上报'
  return `${displayDecimal(exact ?? String(value))}%`
}
export const stateNames: Record<string, string> = {
  ONLINE: '在线', OFFLINE: '离线', UNACTIVATED: '未激活', UNKNOWN: '状态未确认',
  NORMAL: '正常', FIRE: '火警', RECOVERY: '恢复事件', RECOVERY_UNVERIFIED: '恢复待核验（缺少来源时间）', FAULT: '故障',
  PENDING: '待复核', UNHANDLED: '待复核', HANDLED: '已处理',
  TRUE_FIRE: '真实火警', FALSE_ALARM: '误报', TEST: '测试', DEVICE_ALARM: '设备报警',
}
export const conclusions = [ ['TRUE_FIRE', '真实火警'], ['FALSE_ALARM', '误报'], ['TEST', '测试'], ['DEVICE_ALARM', '设备报警'] ] as const
export function statusLabel(value: string | null | undefined) { return value ? stateNames[value] ?? `未知状态（${value}）` : '未上报' }
