import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react'
import { Minus, Plus } from 'lucide-react'

const tileSize = 256
const minZoom = 3
const maxZoom = 17
const initialZoom = 13
const defaultCenter = { latitude: 31.3, longitude: 120.6 }
const modulo = (value: number, total: number) => ((value % total) + total) % total

function origin(latitude: number, longitude: number, zoom: number) {
  const scale = tileSize * 2 ** zoom
  const sin = Math.sin(latitude * Math.PI / 180)
  return { x: scale * (longitude + 180) / 360, y: scale * (.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) }
}

function tileSource() {
  const url = import.meta.env.VITE_CUSTOMER_MAP_TILE_URL as string | undefined
  const attribution = import.meta.env.VITE_CUSTOMER_MAP_ATTRIBUTION as string | undefined
  if (!url || !attribution || !['{z}', '{x}', '{y}'].every(key => url.includes(key))) return null
  try {
    const address = new URL(url, location.origin)
    if (address.username || address.password || (address.origin !== location.origin && address.protocol !== 'https:')) return null
    return { url, attribution }
  } catch { return null }
}

const source = tileSource()

export function LegacyCustomerMap() {
  // The map never receives unit/device coordinates. Its tile requests contain only the visible viewport.
  const ref = useRef<HTMLElement>(null)
  const [size, setSize] = useState({ width: 0, height: 0 })
  const [view, setView] = useState(() => ({ ...origin(defaultCenter.latitude, defaultCenter.longitude, initialZoom), zoom: initialZoom }))
  const [loaded, setLoaded] = useState(false)
  const [loadFailed, setLoadFailed] = useState(false)
  const drag = useRef<{ id: number; x: number; y: number } | null>(null)

  useEffect(() => {
    const root = ref.current
    if (!root || !source) return
    function sizeChanged() { if (root) setSize({ width: root.clientWidth, height: root.clientHeight }) }
    const observer = new ResizeObserver(sizeChanged)
    observer.observe(root)
    sizeChanged()
    return () => observer.disconnect()
  }, [])

  function pan(x: number, y: number) {
    setView(previous => {
      const width = tileSize * 2 ** previous.zoom
      return { ...previous, x: modulo(previous.x + x, width), y: Math.min(width, Math.max(0, previous.y + y)) }
    })
  }

  function zoom(delta: number) {
    setView(previous => {
      const next = Math.min(maxZoom, Math.max(minZoom, previous.zoom + delta))
      const ratio = 2 ** (next - previous.zoom)
      return { x: previous.x * ratio, y: previous.y * ratio, zoom: next }
    })
  }

  function control(event: KeyboardEvent<HTMLElement>) {
    if (event.target !== event.currentTarget) return
    const commands: Record<string, () => void> = { ArrowLeft: () => pan(-100, 0), ArrowRight: () => pan(100, 0), ArrowUp: () => pan(0, -100), ArrowDown: () => pan(0, 100), '+': () => zoom(1), '-': () => zoom(-1) }
    const command = commands[event.key]
    if (command) { event.preventDefault(); command() }
  }

  function begin(event: PointerEvent<HTMLElement>) {
    if (event.button !== 0 || (event.target as HTMLElement).closest('button')) return
    event.currentTarget.setPointerCapture(event.pointerId)
    drag.current = { id: event.pointerId, x: event.clientX, y: event.clientY }
  }

  function move(event: PointerEvent<HTMLElement>) {
    const previous = drag.current
    if (!previous || event.pointerId !== previous.id) return
    drag.current = { id: event.pointerId, x: event.clientX, y: event.clientY }
    pan(previous.x - event.clientX, previous.y - event.clientY)
  }

  const left = view.x - size.width / 2, top = view.y - size.height / 2
  const across = 2 ** view.zoom
  const tiles = []
  if (source && size.width && size.height) {
    for (let y = Math.max(0, Math.floor(top / tileSize)); y < Math.min(across, Math.ceil((top + size.height) / tileSize)); y++) {
      for (let x = Math.floor(left / tileSize); x < Math.ceil((left + size.width) / tileSize); x++) {
        const src = source.url.replace('{z}', String(view.zoom)).replace('{x}', String(modulo(x, across))).replace('{y}', String(y))
        tiles.push(<img key={`${view.zoom}:${x}:${y}`} src={src} alt="" width={tileSize} height={tileSize} draggable={false} referrerPolicy="no-referrer" style={{ left: x * tileSize - left, top: y * tileSize - top }} onLoad={() => setLoaded(true)} onError={() => setLoadFailed(true)}/>)
      }
    }
  }

  return <section ref={ref} className="legacy-map" aria-label="设备地图" tabIndex={source ? 0 : undefined} onKeyDown={control}
    onPointerDown={source ? begin : undefined} onPointerMove={source ? move : undefined} onLostPointerCapture={() => { drag.current = null }}>
    {tiles}
    {!source ? <p className="legacy-map-notice">地图底图未配置</p> : !loaded && loadFailed ? <p className="legacy-map-notice" role="status">地图底图暂不可用</p> : null}
    {source && <><div className="legacy-map-zoom"><button type="button" aria-label="放大地图" disabled={view.zoom === maxZoom} onClick={() => zoom(1)}><Plus size={18}/></button><button type="button" aria-label="缩小地图" disabled={view.zoom === minZoom} onClick={() => zoom(-1)}><Minus size={18}/></button></div><span className="legacy-map-attribution">{source.attribution}</span></>}
  </section>
}
