import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import { AccountPage } from '@/pages/account'
import { useSession } from '@/lib/store'
import { customer, response } from '@/test/fixtures'

it('可以读下一页本人消息；标记已读保留该页并发送实际记录版本', async () => {
  useSession.getState().login({ ...customer, permissions: ['inbox:read'] }, 'customer')
  const row = { id: 'second', kind: 'SYSTEM', title: '下一页的通知', body: '仅本人可见', status: 'UNREAD', created_at: '2026-11-20T00:00:00Z', revision: 7 }
  let read = false
  const writes: unknown[] = []
  vi.stubGlobal('fetch', vi.fn(async (path: string, init?: RequestInit) => {
    if (path === '/api/me') return response(customer.user)
    if (path === '/api/me/sessions') return response([])
    if (path === '/api/me/messages?page=1') return response({ items: [{ ...row, id: 'first', title: '首页的通知' }], total: 2, page: 1, size: 1 })
    if (path === '/api/me/messages?page=2') return response({ items: [{ ...row, status: read ? 'READ' : 'UNREAD' }], total: 2, page: 2, size: 1 })
    if (path === '/api/me/messages/second/read' && init?.method === 'POST') {
      writes.push(JSON.parse(String(init.body)))
      read = true
      return response({ ...row, status: 'READ' })
    }
    throw new Error(`Unexpected request: ${path}`)
  }))
  const user = userEvent.setup()
  render(<AccountPage/>)
  await screen.findByRole('heading', { name: '首页的通知' })
  await user.click(screen.getByRole('button', { name: '下一页' }))
  const message = within((await screen.findByRole('heading', { name: row.title })).closest('article')!)
  expect(message.queryByRole('button', { name: '标记已处理' })).not.toBeInTheDocument()
  await user.click(message.getByRole('button', { name: '标记已读' }))
  expect(await screen.findByText('已读', { exact: true })).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: row.title })).toBeInTheDocument()
  expect(writes).toEqual([{ expected_revision: 7 }])
})
