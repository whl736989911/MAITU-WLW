import type { ComponentProps } from 'react'
import { Tag } from 'antd'
import { cn } from '@/lib/utils'

type BadgeProps = ComponentProps<'span'> & { variant?: 'default' | 'secondary' | 'destructive' | 'outline' | 'ghost' | 'link' }

export function Badge({ className, variant = 'default', ...props }: BadgeProps) {
  return <Tag {...props} color={variant === 'destructive' ? 'error' : variant === 'default' ? 'processing' : undefined} className={cn('platform-tag', className)} data-slot="badge" data-variant={variant}/>
}
