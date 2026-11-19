import { useState } from 'react'
import { api, ApiError } from '@/lib/api'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { conclusions, dateTime, reported, statusLabel } from '@/lib/format'
import type { Alarm, AlarmCoverage, AlarmTraceEntry, AlarmTraceState } from '@/lib/types'
import { Button } from './ui/button'
import { Empty, ErrorNotice, Loading, Pager, Status } from './common'
export const safeRevision = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const actions: Record<string, string> = { FIRE_CREATED: '火警创建', REVIEWED: '人工复核', ALARM_RECOVERED: '指定报警恢复', DEVICE_RECOVERED: '设备侧恢复', MIGRATION_BASELINE: '迁移基线快照' }
export function revisionText(value: number | null | undefined) { return value === null ? '无前版本记录（null）' : safeRevision(value) ? String(value) : '版本未确认' }
export function validCoverage(value?: AlarmCoverage, total?: number) {
  if (!value || !safeRevision(value.current_revision) || !safeRevision(value.entry_total) || value.entry_total !== total || !['FROM_CREATION', 'SINCE_MIGRATION', 'PARTIAL'].includes(value.kind)) return false
  if (value.baseline_recorded_at !== null && (typeof value.baseline_recorded_at !== 'string' || !Number.isFinite(Date.parse(value.baseline_recorded_at)))) return false
  if (value.entry_total === 0) return value.kind === 'PARTIAL' && value.first_revision === null && value.journal_through_revision === null && value.baseline_recorded_at === null
  if (!safeRevision(value.first_revision) || !safeRevision(value.journal_through_revision) || value.first_revision > value.journal_through_revision || value.journal_through_revision > value.current_revision || value.entry_total > value.journal_through_revision - value.first_revision + 1) return false
  if (value.kind === 'FROM_CREATION') return value.first_revision === 0 && value.journal_through_revision === value.current_revision && value.entry_total === value.current_revision + 1 && value.baseline_recorded_at === null
  if (value.kind === 'SINCE_MIGRATION') return value.baseline_recorded_at !== null && value.journal_through_revision === value.current_revision && value.entry_total === value.current_revision - value.first_revision + 1
  return true
}
export function CoverageView({ coverage, total, pageEvidenceValid = true }: { coverage?: AlarmCoverage; total: number; pageEvidenceValid?: boolean }) {
  if (!pageEvidenceValid || !validCoverage(coverage, total)) return <div className="notice trace-coverage" role="status"><strong>覆盖信息未确认，不认定完整</strong><p>未知kind、坏revision或不一致聚合不会补0、伪造连续记录或猜测过去。</p></div>
  const item = coverage!
  const titles = { FROM_CREATION: '从创建开始连续记录（FROM_CREATION）', SINCE_MIGRATION: '仅迁移以来记录（SINCE_MIGRATION）', PARTIAL: '轨迹不完整（PARTIAL）' }
  return <div className={`notice trace-coverage ${item.kind === 'PARTIAL' ? 'partial' : ''}`}><strong>{titles[item.kind]}</strong><p>{item.kind === 'FROM_CREATION' ? '这是服务端全部轨迹聚合的覆盖结论，不是由当前页或最小revision=0推断。仅覆盖报警业务留痕，不等于完整生命周期/通知/下行。' : item.kind === 'SINCE_MIGRATION' ? '只从真实迁移基线开始；基线之前创建/复核/恢复、操作者与时刻未知，不把最后备注猜成过去轨迹。' : '可能存在内部版本缺口、未覆盖当前版本或空历史；不补写、不把缺失当作以前正常，也不宣称完整。'}</p><div>首条revision：{item.first_revision === null ? '无记录' : item.first_revision} · 轨迹记录至revision：{item.journal_through_revision === null ? '无记录' : item.journal_through_revision}</div><div>历史读取快照的当前revision：{item.current_revision} · 实际轨迹行数：{item.entry_total}</div>{item.baseline_recorded_at !== null && <div>基线实际记录时间：{dateTime(item.baseline_recorded_at)}（不冒充旧发生时间）</div>}</div>
}
export function TraceState({ state }: { state?: AlarmTraceState | Pick<Alarm, 'physical_state' | 'response_state' | 'conclusion'> | null }) {
  if (!state) return <span className="muted-note">状态快照未提供，不推断正常</span>
  const physicalKnown = state.physical_state === 'FIRE' || state.physical_state === 'NORMAL', responseKnown = state.response_state === 'UNHANDLED' || state.response_state === 'HANDLED'
  return <div className="trace-state"><span>物理：{physicalKnown ? <Status value={state.physical_state} dimension="physical"/> : '物理状态未确认'}</span><span>人工：{responseKnown ? <Status value={state.response_state} dimension="response"/> : '人工状态未确认'}</span><span>结论：{state.conclusion === null ? '未定（null）' : conclusions.some(([value]) => value === state.conclusion) ? statusLabel(state.conclusion) : '结论未确认（未知枚举）'}</span></div>
}
function TraceActor({ entry }: { entry: AlarmTraceEntry }) {
  const audience = useSession(s => s.audience)
  if (audience === 'customer') return <span>{entry.actor_label}{entry.is_self === true && entry.actor_label === '本人' ? ' · 自身操作' : ''}</span>
  if (entry.actor_kind === 'SYSTEM') return <span>系统处理 · 无用户身份上下文，不猜某个人</span>
  if (entry.actor_kind === 'LEGACY_UNKNOWN') return <span>历史未知 · 没有过去操作者证据</span>
  if (entry.actor_kind !== 'PLATFORM' && entry.actor_kind !== 'CUSTOMER') return <span>操作者类型未确认</span>
  return <span>{typeof entry.actor_name === 'string' && entry.actor_name ? `${entry.actor_name}（当时快照）` : reported(entry.actor_label)}<small className="mono">身份类型 {entry.actor_kind} · actor ID {reported(entry.actor_id)}</small></span>
}
export function AlarmTrace({ alarmId, tenantId }: { alarmId: string; tenantId?: string }) {
  const [page, setPage] = useState(1)
  const history = useResource(`alarm-history:${tenantId ?? 'customer'}:${alarmId}:${page}`, signal => api.alarmHistory(alarmId, page, signal, tenantId))
  const data = history.data
  const pageEvidenceValid = data?.items.every(entry => entry.alarm_id === alarmId && Object.hasOwn(actions, entry.action) && safeRevision(entry.revision_after) && (entry.revision_before === null ? (entry.action === 'FIRE_CREATED' && entry.revision_after === 0) || entry.action === 'MIGRATION_BASELINE' : safeRevision(entry.revision_before) && entry.revision_after === entry.revision_before + 1)) === true
  const validPage = !!data && safeRevision(data.total) && safeRevision(data.page) && data.page >= 1 && safeRevision(data.size) && data.size >= 1 && Array.isArray(data.items) && data.items.length <= data.total && data.items.length <= data.size
  return <section className="alarm-trace"><div className="section-header"><h3>不可变业务轨迹</h3><Button type="button" variant="outline" onClick={history.refresh}>刷新业务轨迹</Button></div><p className="muted-note">分页行与coverage来自同一次历史读取快照；当前详情单独重读可能更晚。轨迹after永远不是当前态，不使用它覆盖物理状态。只有既有业务事务可写轨迹，此处无补写/编辑/删除入口。</p>{history.loading ? <Loading/> : history.error != null ? <>{history.error instanceof ApiError && history.error.status === 403 && <div className="notice" role="status">业务轨迹访问已拒绝（403），旧轨迹已清除；当前详情仍可读，不退出仍合法会话。</div>}<ErrorNotice error={history.error} retry={history.refresh}/></> : !validPage ? <div className="notice">历史分页信息未确认，不补默认页数或0条记录。</div> : data && <><CoverageView coverage={data.coverage} total={data.total} pageEvidenceValid={pageEvidenceValid}/>{data.items.length ? <div className="trace-items">{data.items.map(entry => <article className="trace-entry" key={entry.id} data-trace-id={entry.id}><header><strong>{actions[entry.action] ?? '未知业务动作，不推断完成'}</strong><span className="mono">{entry.action} · {entry.id}</span></header><p className="mono">revision {revisionText(entry.revision_before)} → {revisionText(entry.revision_after)} · {entry.origin === 'MIGRATION_SNAPSHOT' ? '迁移时快照，不追溯伪造过去' : entry.origin === 'APPLICATION' ? '应用业务事务' : '来源未确认'}</p><div className="trace-before-after"><div><h4>不可变before</h4>{entry.before === null ? <p className="muted-note">{entry.action === 'FIRE_CREATED' || entry.action === 'MIGRATION_BASELINE' ? '未提供先态（创建/基线），不代表先前正常' : '异常缺失先态，不推断正常或创建过程'}</p> : <TraceState state={entry.before}/>}</div><div><h4>不可变after（不是当前态）</h4><TraceState state={entry.after}/></div></div><dl className="detail-list"><div><dt>实际记录时间 recorded_at</dt><dd>{dateTime(entry.recorded_at)}</dd></div><div><dt>业务观测时间 occurred_at</dt><dd>{entry.occurred_at === null ? '无发生时刻（null），不根据记录时间补猜' : dateTime(entry.occurred_at)}<small> · 不作为可信厂商设备钟</small></dd></div><div><dt>操作者安全投影</dt><dd><TraceActor entry={entry}/></dd></div><div><dt>{entry.action === 'MIGRATION_BASELINE' ? '迁移时末次备注（非过去过程）' : '本次业务备注'}</dt><dd className="trace-remark">{entry.remark === null ? '无备注（null）' : entry.remark === '' ? '空备注（保留旧兼容）' : entry.remark}</dd></div></dl></article>)}</div> : <Empty title="暂无业务轨迹">空历史也只能PARTIAL，不补造创建、操作者或已成功通知。</Empty>}<Pager page={data.page} size={data.size} total={data.total} onChange={setPage}/></>}</section>
}
