import { lazy, Suspense } from 'react'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { RenderFailureBoundary } from '@/components/render-failure-boundary'

function BrokenView(): never { throw new Error('test-only-render-details-not-for-display') }

describe('页面显示异常恢复', () => {
  it('异步页面加载失败不移除外层内容，普通重渲染不自动重试，切页后可以显示健康内容', async () => {
    const BrokenPage = lazy(() => Promise.reject(new TypeError('test-only-chunk-failure')))
    const view = render(<><nav aria-label="保留的导航">工作区导航</nav><RenderFailureBoundary key="failed-page"><Suspense fallback={<p>加载中</p>}><BrokenPage/></Suspense></RenderFailureBoundary></>)
    await screen.findByRole('alert', { name: '页面显示异常' })
    expect(screen.getByRole('navigation', { name: '保留的导航' })).toBeVisible()
    expect(screen.queryByText('test-only-chunk-failure')).not.toBeInTheDocument()
    view.rerender(<><nav aria-label="保留的导航">工作区导航</nav><RenderFailureBoundary key="failed-page"><p>健康页面内容</p></RenderFailureBoundary></>)
    expect(screen.getByRole('alert', { name: '页面显示异常' })).toBeVisible()
    expect(screen.queryByText('健康页面内容')).not.toBeInTheDocument()
    view.rerender(<><nav aria-label="保留的导航">工作区导航</nav><RenderFailureBoundary key="other-page"><p>健康页面内容</p></RenderFailureBoundary></>)
    expect(screen.getByText('健康页面内容')).toBeVisible()
    expect(screen.queryByRole('alert', { name: '页面显示异常' })).not.toBeInTheDocument()
  })

  it('显示异常清除旧页面记录，不暴露异常详情，并保留明确的重新加载入口', async () => {
    const view = render(<RenderFailureBoundary><p>已读取的旧页面记录</p></RenderFailureBoundary>)
    expect(screen.getByText('已读取的旧页面记录')).toBeVisible()
    view.rerender(<RenderFailureBoundary><BrokenView/></RenderFailureBoundary>)
    await screen.findByRole('alert', { name: '页面显示异常' })
    expect(screen.queryByText('已读取的旧页面记录')).not.toBeInTheDocument()
    expect(screen.queryByText('test-only-render-details-not-for-display')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新加载平台' })).toBeEnabled()
  })
})
