import type { ComponentPropsWithoutRef } from 'react'
import { Button as AntButton } from 'antd'
import { cn } from '@/lib/utils'

type ButtonVariant = 'default' | 'outline' | 'secondary' | 'ghost' | 'destructive' | 'link'
type ButtonSize = 'default' | 'xs' | 'sm' | 'lg' | 'icon' | 'icon-xs' | 'icon-sm' | 'icon-lg'
type ButtonProps = Omit<ComponentPropsWithoutRef<'button'>, 'color'> & { variant?: ButtonVariant; size?: ButtonSize }

/** Application action semantics; form submission is independent from visual emphasis. */
export function Button({ className, variant = 'default', size = 'default', type = 'button', ...props }: ButtonProps) {
  const appearance = variant === 'default' || variant === 'destructive' ? 'primary' : variant === 'ghost' ? 'text' : variant === 'link' ? 'link' : 'default'
  const controlSize = size === 'lg' || size === 'icon-lg' ? 'large' : size === 'sm' || size === 'xs' || size === 'icon-sm' || size === 'icon-xs' ? 'small' : 'middle'
  return <AntButton {...props} autoInsertSpace={false} htmlType={type} type={appearance} danger={variant === 'destructive'} size={controlSize} className={cn('platform-action', className)} data-slot="button" data-variant={variant} data-size={size}/>
}
