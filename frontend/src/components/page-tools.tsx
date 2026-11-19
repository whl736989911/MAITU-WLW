import { useState, type ReactNode } from 'react'
import { Popover } from 'antd'
import { CircleHelp } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetTrigger } from '@/components/ui/sheet'

/** Secondary information is opened deliberately; business data stays in the workspace. */
export function PageHelp({ title = '使用说明', label = '使用说明', children }: { title?: string; label?: string; children: ReactNode }) {
  const [open, setOpen] = useState(false)
  return <Sheet open={open} onOpenChange={setOpen}>
    <SheetTrigger asChild><Button type="button" variant="outline" size="sm" className="page-help-trigger"><CircleHelp size={15}/>{label}</Button></SheetTrigger>
    <SheetContent className="page-help-sheet">
      <SheetHeader><SheetTitle>{title}</SheetTitle></SheetHeader>
      {open && <div className="page-help-body">{children}</div>}
    </SheetContent>
  </Sheet>
}

/** Keep row dialogs mounted after closing the action popover, including uncertain writes. */
export function RowActions({ label = '更多操作', text = '更多操作', closeOnClick = true, children }: { label?: string; text?: ReactNode; closeOnClick?: boolean; children: ReactNode }) {
  const [open, setOpen] = useState(false)
  return <Popover open={open} onOpenChange={setOpen} trigger="click" placement="bottomRight" destroyOnHidden={false} content={
    <div className="row-actions-menu" role="group" aria-label={label} onClickCapture={closeOnClick ? () => setOpen(false) : undefined}>{children}</div>
  }><Button type="button" variant="ghost" size="sm" aria-label={label} aria-expanded={open}>{text}</Button></Popover>
}
