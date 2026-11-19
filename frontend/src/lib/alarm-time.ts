import type { Alarm } from './types'
import { dateTime } from './format'

/** A platform registration timestamp is never presented as the device's occurrence time. */
export function alarmTimeText(alarm: Pick<Alarm, 'occurred_at' | 'time_quality'>): string {
  if (alarm.time_quality === 'SOURCE_TIME_MISSING' || !alarm.occurred_at) return '来源发生时间未知'
  const value = dateTime(alarm.occurred_at)
  return alarm.time_quality === 'SOURCE_REPORTED' ? value : `${value}（历史时间来源未核验）`
}
