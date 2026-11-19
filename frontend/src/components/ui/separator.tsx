import type { ComponentProps } from 'react'
import { cn } from '@/lib/utils'

type SeparatorProps = ComponentProps<'div'> & { orientation?: 'horizontal' | 'vertical'; decorative?: boolean }
export function Separator({ className, orientation = 'horizontal', decorative = true, ...props }: SeparatorProps) {
  return <div {...props} role={decorative ? 'presentation' : 'separator'} aria-orientation={decorative ? undefined : orientation} data-slot="separator" data-orientation={orientation} className={cn('platform-separator', className)}/>
}
