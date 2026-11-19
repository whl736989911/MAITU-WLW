import { Children, cloneElement, createContext, isValidElement, useContext, useId, useLayoutEffect, useMemo, useState, type ComponentProps, type Dispatch, type MouseEvent, type ReactNode, type SetStateAction } from 'react'
import { Drawer } from 'antd'
import { cn } from '@/lib/utils'

type SheetContextValue = { open: boolean; change: (open: boolean) => void; title: ReactNode; setTitle: Dispatch<SetStateAction<ReactNode>>; descriptionId: string }
const SheetContext = createContext<SheetContextValue | null>(null)
function useSheet() {
  const value = useContext(SheetContext)
  if (!value) throw new Error('操作面板必须位于其受控容器内')
  return value
}

export function Sheet({ open, onOpenChange, children }: { open: boolean; onOpenChange: (open: boolean) => void; children: ReactNode }) {
  const [title, setTitle] = useState<ReactNode>(null)
  const descriptionId = useId()
  const context = useMemo(() => ({ open, change: onOpenChange, title, setTitle, descriptionId }), [open, onOpenChange, title, descriptionId])
  return <SheetContext.Provider value={context}>{children}</SheetContext.Provider>
}

export function SheetTrigger({ asChild = false, children, onClick, ...props }: ComponentProps<'button'> & { asChild?: boolean }) {
  const sheet = useSheet()
  const open = (event: MouseEvent<HTMLButtonElement>) => { onClick?.(event); if (!event.defaultPrevented) sheet.change(true) }
  if (asChild) {
    const child = Children.only(children)
    if (!isValidElement<ComponentProps<'button'>>(child)) throw new Error('面板触发器需要一个可操作元素')
    return cloneElement(child, { ...props, 'aria-haspopup': 'dialog', 'aria-expanded': sheet.open, onClick: event => { child.props.onClick?.(event); if (!event.defaultPrevented) open(event) } })
  }
  return <button {...props} type={props.type ?? 'button'} aria-haspopup="dialog" aria-expanded={sheet.open} onClick={open}>{children}</button>
}

type ContentProps = ComponentProps<'div'> & { side?: 'top' | 'right' | 'bottom' | 'left'; showCloseButton?: boolean }
export function SheetContent({ className, children, side = 'right', showCloseButton = true, ...props }: ContentProps) {
  const sheet = useSheet()
  const horizontal = side === 'left' || side === 'right'
  const width = className?.includes('mobile-sidebar') ? 'min(320px, 100vw)' : className?.includes('device-detail-sheet') ? 'min(960px, 100vw)' : 'min(760px, 100vw)'
  const titleId = `${sheet.descriptionId}-title`
  return <Drawer open={sheet.open} onClose={() => sheet.change(false)} placement={side} size={horizontal ? width : 'min(85vh, 760px)'} title={<span id={titleId}>{sheet.title ?? '详情与编辑'}</span>} aria-labelledby={titleId} closable={showCloseButton ? { placement: 'end' } : false} closeIcon={<span aria-label="关闭">×</span>} destroyOnHidden keyboard focusable={{ trap: true, focusTriggerAfterClose: true }} classNames={{ root: 'platform-drawer', body: 'platform-drawer-body' }} styles={{ body: { padding: 0 } }}>
    <div {...props} data-slot="sheet-content" data-side={side} className={cn('platform-sheet-content', className)}>{children}</div>
  </Drawer>
}
export function SheetHeader({ className, ...props }: ComponentProps<'div'>) { return <div {...props} data-slot="sheet-header" className={cn('platform-sheet-header', className)}/> }
export function SheetFooter({ className, ...props }: ComponentProps<'div'>) { return <div {...props} data-slot="sheet-footer" className={cn('platform-sheet-footer', className)}/> }
export function SheetTitle({ children }: ComponentProps<'h2'>) {
  const { setTitle } = useSheet()
  useLayoutEffect(() => { setTitle(children); return () => setTitle(null) }, [children, setTitle])
  return null
}
export function SheetDescription({ className, ...props }: ComponentProps<'p'>) {
  const { descriptionId } = useSheet()
  return <p {...props} id={props.id ?? descriptionId} data-slot="sheet-description" className={cn('platform-sheet-description', className)}/>
}
