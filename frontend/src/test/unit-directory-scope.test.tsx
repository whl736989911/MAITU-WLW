import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { UnitsPage } from '@/pages/units'
import { useSession } from '@/lib/store'
import { management } from './fixtures'

beforeEach(() => { useSession.getState().clear(); localStorage.clear(); sessionStorage.clear() })

describe('unit directory onboarding scope', () => {
  it('does not request a list for initialized empty scope and keeps zero-unit registration available', async () => {
    useSession.getState().login({ ...management, permissions: ['units:read', 'units:write'] }, 'management')
    useSession.getState().setScope([], [], true)
    const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher)
    render(<UnitsPage />)
    expect(screen.getByText('当前未选择范围')).toBeInTheDocument()
    expect(fetcher).not.toHaveBeenCalled()

    await userEvent.setup().click(screen.getByRole('button', { name: '新增单位' }))
    expect(await screen.findByText('登记单位')).toBeInTheDocument()
    expect(fetcher).not.toHaveBeenCalled()
  })
})
