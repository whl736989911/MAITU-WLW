import { useState } from 'react'
import { useSession } from '@/lib/store'
import { useResource } from '@/lib/use-resource'
import { platformOperationsApi, type Deployment, type HealthCheck, type HealthState, type OpsEvidence, type OpsManifest } from '@/lib/platform-operations'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Empty, ErrorNotice, Loading, PageHeading, Pager } from '@/components/common'
import { PageHelp } from '@/components/page-tools'

const PAGE = 'releases-migrations'
const PAGE_SIZE = 20
const tone: Record<HealthState, string> = { READY: 'success', CONFIGURED: 'neutral', DISABLED: 'neutral', UNKNOWN: 'warning', DEGRADED: 'danger' }

export function PlatformOperationsPage() {
  const s = useSession()
  if (!s.token || !s.user || s.user.user_type !== 'PLATFORM' || s.user.must_change_password || s.audience !== 'management'
    || !s.permissions.includes('platform-ops:read') || String(s.page) !== PAGE) return <Empty title="当前无平台运维证据读取权限"/>
  const frame = `${s.authGeneration}:${s.permissions.join(',')}`
  return <Content key={frame}/>
}

function Content() {
  const [page, setPage] = useState(1)
  const overview = useResource('platform-operations-overview', signal => platformOperationsApi.overview(signal), true)
  const schema = useResource(`platform-operations-schema:${page}`, signal => platformOperationsApi.schema(page, PAGE_SIZE, signal), true)
  const data = overview.data, history = schema.data
  function refresh() { overview.refresh(); schema.refresh() }
  return <section className="replica-page" aria-label="平台运维证据">
    <PageHeading eyebrow="系统管理 / 平台运维" title="平台运维证据" description="只读展示当前部署身份、真实 Flyway 历史、显式健康状态与受控证据清单。">
      <PageHelp title="运维证据边界"><div className="form-stack">
        <p>本页只读，不提供备份、恢复、重启、删除、后端shell或任意文件/URL读取。备份/恢复/迁移成功只来自根受控脚本写入的固定证据目录，不由本页执行或推断。</p>
        <p>未配置证据目录或未提供清单时显示空状态；清单格式、大小、路径或跨字段一致性不符合合同时整体失败关闭，不展示部分成功。</p>
        <p>健康状态区分已配置、已关闭和未知；“已配置”不证明厂商连通、通知送达或备份可恢复。Flyway 只读取真实历史表，不推断 classpath 待执行项。</p>
      </div></PageHelp>
      <Button type="button" variant="outline" onClick={refresh}>刷新运维证据</Button>
    </PageHeading>
    {overview.loading ? <Loading/> : overview.error != null ? <ErrorNotice error={overview.error} retry={overview.refresh}/> : !data ? <Empty title="运维状态尚未确认"/> : <>
      <section className="dashboard-stat-strip" aria-label="部署摘要">
        <div><span>发布标识</span><strong style={{ overflowWrap: 'anywhere' }}>{data.deployment.release_id ?? '未提供'}</strong></div>
        <div><span>构建 SHA-256</span><strong style={{ overflowWrap: 'anywhere' }}>{data.deployment.artifact_sha256 ?? '未提供'}</strong></div>
        <div><span>存储</span><strong>{data.deployment.storage}</strong></div>
        <div><span>Flyway 当前版本</span><strong>{data.schema.available ? (data.schema.current_version ?? '无版本行') : '未知'}</strong></div>
        <div><span>已应用迁移</span><strong>{data.schema.available ? data.schema.applied : '未知'}</strong></div>
        <div><span>失败迁移</span><strong>{data.schema.available ? data.schema.failed : '未知'}</strong></div>
        <div><span>证据清单</span><strong>{data.evidence.available ? '已加载' : '未提供'}</strong></div>
        <div><span>读取时间（UTC）</span><strong className="mono">{data.generated_at}</strong></div>
      </section>

      <section className="duty-ops-grid">
        <Card><CardContent><h2>部署与构建身份</h2><DeploymentBlock deployment={data.deployment}/></CardContent></Card>
        <Card><CardContent><h2>实时健康状态</h2><HealthTable checks={data.health}/></CardContent></Card>
      </section>

      <Card><CardContent>
        <div className="list-toolbar"><h2>Flyway schema 历史</h2><span className="muted-note">{data.schema.available ? `${data.schema.applied} 已应用 · ${data.schema.failed} 失败 · 校验状态 ${data.schema.validation} · classpath 待执行项未检查` : '历史表不可读'}</span></div>
        {schema.loading ? <Loading/> : schema.error != null ? <ErrorNotice error={schema.error} retry={schema.refresh}/> : !history || !history.available ? <Empty title="Flyway历史表不可读">{history?.reason ?? '不推断迁移成功。'}</Empty> : history.items.length ? <>
          <Table><TableHeader><TableRow><TableHead>rank</TableHead><TableHead>版本</TableHead><TableHead>描述</TableHead><TableHead>类型</TableHead><TableHead>脚本</TableHead><TableHead>校验和</TableHead><TableHead>安装者</TableHead><TableHead>安装时间（UTC）</TableHead><TableHead>耗时 ms</TableHead><TableHead>成功</TableHead></TableRow></TableHeader><TableBody>{history.items.map(item => <TableRow key={item.rank}>
            <TableCell>{item.rank}</TableCell><TableCell className="mono">{item.version ?? '无版本'}</TableCell><TableCell>{item.description ?? '未记录'}</TableCell><TableCell>{item.type ?? '未记录'}</TableCell><TableCell className="mono">{item.script ?? '未记录'}</TableCell><TableCell className="mono">{item.checksum ?? '未记录'}</TableCell><TableCell>{item.installed_by ?? '未记录'}</TableCell><TableCell className="mono">{item.installed_on ?? '未记录'}</TableCell><TableCell>{item.execution_time ?? '未记录'}</TableCell><TableCell><Badge variant="outline" className={`status status-${item.success ? 'success' : 'danger'}`}>{item.success ? '成功' : '失败'}</Badge></TableCell>
          </TableRow>)}</TableBody></Table>
          <Pager page={history.page} size={history.size} total={history.total} onChange={setPage}/>
        </> : <Empty title="Flyway历史表没有记录"/>}
      </CardContent></Card>

      <Card><CardContent>
        <div className="list-toolbar"><h2>备份 / 恢复 / 迁移验证证据</h2><span className="muted-note">固定目录 {data.evidence.configured ? '已配置' : '未配置'} · {data.evidence.file_name ?? '无文件'}</span></div>
        <EvidenceBlock evidence={data.evidence}/>
      </CardContent></Card>
    </>}
  </section>
}

function DeploymentBlock({ deployment }: { deployment: Deployment }) {
  return <dl className="detail-list">
    <div><dt>身份状态</dt><dd><Badge variant="outline" className={`status status-${deployment.identity_status === 'PROVIDED' ? 'success' : deployment.identity_status === 'INVALID' ? 'danger' : 'warning'}`}>{deployment.identity_status}</Badge></dd></div>
    <div><dt>发布标识</dt><dd className="mono">{deployment.release_id ?? '未提供'}</dd></div>
    <div><dt>构建 SHA-256</dt><dd className="mono" style={{ overflowWrap: 'anywhere' }}>{deployment.artifact_sha256 ?? '未提供'}</dd></div>
    <div><dt>源码 commit</dt><dd className="mono">{deployment.source_commit ?? '未提供'}</dd></div>
    <div><dt>构建时间（UTC）</dt><dd className="mono">{deployment.built_at ?? '未提供'}</dd></div>
    <div><dt>进程启动（UTC）</dt><dd className="mono">{deployment.started_at}</dd></div>
    <div><dt>已运行</dt><dd>{formatUptime(deployment.uptime_seconds)}</dd></div>
    <div><dt>profiles</dt><dd>{deployment.profiles.length ? deployment.profiles.join(', ') : '未声明'}</dd></div>
    <div><dt>roles</dt><dd>{deployment.roles.join(', ')}</dd></div>
  </dl>
}

function HealthTable({ checks }: { checks: HealthCheck[] }) {
  return <Table><TableHeader><TableRow><TableHead>检查项</TableHead><TableHead>状态</TableHead><TableHead>说明</TableHead></TableRow></TableHeader><TableBody>
    {checks.map(check => <TableRow key={check.name}><TableCell className="mono">{check.name}</TableCell>
      <TableCell><Badge variant="outline" className={`status status-${tone[check.state]}`}>{check.state}</Badge></TableCell>
      <TableCell>{check.detail}</TableCell></TableRow>)}
  </TableBody></Table>
}

function EvidenceBlock({ evidence }: { evidence: OpsEvidence }) {
  if (!evidence.configured) return <Empty title="未配置运维证据目录">部署环境未提供 APP_OPS_EVIDENCE_DIR；本页不推断备份、恢复或迁移成功。</Empty>
  if (!evidence.available) return <Empty title="未提供运维证据清单">{evidence.reason === 'DIRECTORY_NOT_FOUND' ? '已配置的目录不存在；没有推断成功。' : '固定文件 operations-evidence.json 不存在；没有推断成功。'}</Empty>
  if (!evidence.manifest) return <Empty title="证据清单尚未确认"/>
  return <>
    <dl className="detail-list">
      <div><dt>文件</dt><dd className="mono">{evidence.file_name}</dd></div>
      <div><dt>SHA-256</dt><dd className="mono" style={{ overflowWrap: 'anywhere' }}>{evidence.sha256}</dd></div>
      <div><dt>大小</dt><dd>{evidence.bytes} 字节</dd></div>
      <div><dt>文件修改时间（UTC）</dt><dd className="mono">{evidence.modified_at}</dd></div>
      <div><dt>清单生成时间（UTC）</dt><dd className="mono">{evidence.manifest.generated_at}</dd></div>
      <div><dt>生成者 / 环境</dt><dd>{evidence.manifest.producer} · {evidence.manifest.environment}</dd></div>
    </dl>
    <ManifestSections manifest={evidence.manifest}/>
  </>
}

function ManifestSections({ manifest }: { manifest: OpsManifest }) {
  return <div className="duty-ops-grid">
    <section aria-label="备份证据"><h3>备份</h3><StatusLine status={manifest.backup.status} reason={manifest.backup.reason}/>
      <dl className="detail-list"><div><dt>完成时间（UTC）</dt><dd className="mono">{manifest.backup.completed_at ?? '未记录'}</dd></div>
        <div><dt>数据库</dt><dd>{manifest.backup.database_engine ?? '未记录'}</dd></div><div><dt>文件</dt><dd className="mono">{manifest.backup.file_name ?? '未记录'}</dd></div>
        <div><dt>文件 SHA-256</dt><dd className="mono" style={{ overflowWrap: 'anywhere' }}>{manifest.backup.file_sha256 ?? '未记录'}</dd></div>
        <div><dt>字节数</dt><dd>{manifest.backup.file_bytes ?? '未记录'}</dd></div><div><dt>加密 / 离线</dt><dd>{manifest.backup.encrypted == null ? '未记录' : manifest.backup.encrypted ? '加密' : '未加密'} · {manifest.backup.offline == null ? '未记录' : manifest.backup.offline ? '离线' : '非离线'}</dd></div></dl></section>
    <section aria-label="恢复证据"><h3>恢复验证</h3><StatusLine status={manifest.restore.status} reason={manifest.restore.reason}/>
      <dl className="detail-list"><div><dt>验证时间（UTC）</dt><dd className="mono">{manifest.restore.validated_at ?? '未记录'}</dd></div>
        <div><dt>隔离目标</dt><dd>{manifest.restore.isolated_target == null ? '未记录' : manifest.restore.isolated_target ? '是' : '否'}</dd></div>
        <div><dt>备份 SHA-256</dt><dd className="mono" style={{ overflowWrap: 'anywhere' }}>{manifest.restore.backup_sha256 ?? '未记录'}</dd></div>
        <div><dt>恢复后 schema</dt><dd>{manifest.restore.restored_schema_version ?? '未记录'}</dd></div>
        <div><dt>schema 一致</dt><dd>{manifest.restore.schema_version_matches == null ? '未记录' : manifest.restore.schema_version_matches ? '是' : '否'}</dd></div></dl></section>
    <section aria-label="迁移验证证据"><h3>迁移验证</h3><StatusLine status={manifest.migration.status} reason={manifest.migration.reason}/>
      <dl className="detail-list"><div><dt>验证时间（UTC）</dt><dd className="mono">{manifest.migration.validated_at ?? '未记录'}</dd></div>
        <div><dt>源 schema</dt><dd>{manifest.migration.source_schema_version ?? '未记录'}</dd></div><div><dt>目标 schema</dt><dd>{manifest.migration.target_schema_version ?? '未记录'}</dd></div>
        <div><dt>隔离副本</dt><dd>{manifest.migration.isolated_copy == null ? '未记录' : manifest.migration.isolated_copy ? '是' : '否'}</dd></div>
        <div><dt>校验和验证</dt><dd>{manifest.migration.checksum_validation ?? '未记录'}</dd></div></dl></section>
    <section aria-label="清单发布身份"><h3>清单发布身份</h3><dl className="detail-list">
      <div><dt>发布标识</dt><dd className="mono">{manifest.release.release_id}</dd></div><div><dt>构建 SHA-256</dt><dd className="mono" style={{ overflowWrap: 'anywhere' }}>{manifest.release.artifact_sha256}</dd></div>
      <div><dt>源码 commit</dt><dd className="mono">{manifest.release.source_commit ?? '未提供'}</dd></div><div><dt>构建时间（UTC）</dt><dd className="mono">{manifest.release.built_at}</dd></div></dl>
      <p className="muted-note">清单由根受控发布流程写入；本页只按固定文件名读取并校验，不接受调用方路径或URL，也不执行备份/恢复。</p></section>
  </div>
}

function StatusLine({ status, reason }: { status: 'VERIFIED' | 'NOT_VERIFIED'; reason: string | null }) {
  return <p><Badge variant="outline" className={`status status-${status === 'VERIFIED' ? 'success' : 'warning'}`}>{status === 'VERIFIED' ? '已验证' : '未验证'}</Badge>{status === 'NOT_VERIFIED' && <span className="muted-note"> · {reason ?? '未提供原因'}</span>}</p>
}

function formatUptime(seconds: number) {
  const days = Math.floor(seconds / 86400), hours = Math.floor(seconds % 86400 / 3600), minutes = Math.floor(seconds % 3600 / 60)
  return `${days}天 ${hours}小时 ${minutes}分`
}
