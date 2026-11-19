import { Component, type ReactNode } from 'react'
import './render-failure-boundary.css'

type Props = { children: ReactNode; onExit?: () => void; exitLabel?: string }

export class RenderFailureBoundary extends Component<Props, { failed: boolean }> {
  state = { failed: false }

  static getDerivedStateFromError() { return { failed: true } }

  render() {
    if (!this.state.failed) return this.props.children
    return <section className="render-failure" role="alert" aria-label="页面显示异常">
      <h1>页面暂时无法显示</h1>
      <p>页面资源加载失败或显示出现异常，当前页面内容未能确认。</p>
      <p>重新加载会清空未提交内容，并需要重新登录。若刚提交过操作，请先核对处理结果，不要重复提交。</p>
      <div className="render-failure-actions">
        {this.props.onExit && <button type="button" onClick={this.props.onExit}>{this.props.exitLabel ?? '返回运行总览'}</button>}
        <button type="button" onClick={() => window.location.reload()}>重新加载平台</button>
      </div>
    </section>
  }
}
