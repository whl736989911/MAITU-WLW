import { useEffect, useState, type ReactNode } from 'react'
import { Card, CardContent } from '@/components/ui/card'
import './dashboard-charts.css'

export type ChartPoint = { label: string; value: number }

export function DashboardLineChart({ points, label, unit = '' }: { points: ChartPoint[]; label: string; unit?: string }) {
  const width = 720, height = 220, padX = 30, padY = 20, last = points[points.length - 1]
  const min = Math.min(0, ...points.map(point => point.value)), max = Math.max(1, ...points.map(point => point.value)), range = max - min || 1
  const coords = points.map((point, index) => ({ ...point, x: padX + index * (width - padX * 2) / Math.max(1, points.length - 1), y: height - padY - (point.value - min) / range * (height - padY * 2) }))
  return <figure className="dashboard-chart" aria-label={label}>
    {points.length < 2 ? <p className="muted-note">可绘制数据点不足</p> : <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={label}>
      <title>{label}</title><line x1={padX} x2={width - padX} y1={height - padY} y2={height - padY} className="chart-axis" />
      <polyline points={coords.map(point => `${point.x},${point.y}`).join(' ')} className="chart-line" />
      {coords.map(point => <circle key={point.label} cx={point.x} cy={point.y} r="3.5" className="chart-dot"><title>{point.label}: {point.value}{unit}</title></circle>)}
    </svg>}
    {points.length > 0 && <div className="dashboard-chart-labels"><span>{points[0].label}</span><strong>{last?.value}{unit} 最新</strong><span>{last?.label}</span></div>}
  </figure>
}

export function DashboardBars({ points, label, unit = '' }: { points: ChartPoint[]; label: string; unit?: string }) {
  const max = Math.max(1, ...points.map(point => point.value))
  return <figure className="dashboard-bars" aria-label={label}>
    {points.length ? points.map((point, index) => <div className="dashboard-bar-row" key={`${point.label}:${index}`}>
      <span title={point.label}>{point.label}</span><span className="dashboard-bar-track"><i style={{ width: `${Math.max(point.value > 0 ? 2 : 0, point.value / max * 100)}%` }} /></span><b>{point.value}{unit}</b>
    </div>) : <p className="muted-note">当前范围无可展示数据</p>}
  </figure>
}

export function DashboardPanel({ title, children }: { title: string; children: ReactNode }) {
  const [fullscreen, setFullscreen] = useState(false), elementId = `dashboard-${title}`
  useEffect(() => {
    const sync = () => setFullscreen(document.fullscreenElement?.id === elementId)
    document.addEventListener('fullscreenchange', sync)
    return () => document.removeEventListener('fullscreenchange', sync)
  }, [elementId])
  async function toggle() {
    const node = document.getElementById(elementId)
    if (!node) return
    try {
      if (document.fullscreenElement) await document.exitFullscreen()
      else await node.requestFullscreen()
    } catch { setFullscreen(false) }
  }
  return <Card id={elementId} className="dashboard-panel"><CardContent>
    <header className="dashboard-panel-heading"><h2>{title}</h2><button type="button" className="dashboard-fullscreen" aria-label={`${title}全屏`} onClick={toggle}>{fullscreen ? '退出全屏' : '全屏'}</button></header>
    {children}
  </CardContent></Card>
}
