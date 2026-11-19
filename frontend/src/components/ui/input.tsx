import { forwardRef, useImperativeHandle, useRef, type ComponentPropsWithoutRef } from 'react'
import { Input as AntInput, type InputRef } from 'antd'
import { cn } from '@/lib/utils'

type InputProps = Omit<ComponentPropsWithoutRef<'input'>, 'size'>

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input({ className, type, ...props }, ref) {
  const control = useRef<InputRef>(null)
  const fileControl = useRef<HTMLInputElement>(null)
  useImperativeHandle(ref, () => (type === 'file' ? fileControl.current : control.current?.input)!, [type])
  // The browser owns the FileList and file-value reset contract, not the design system.
  if (type === 'file') return <input {...props} ref={fileControl} type="file" data-slot="input" className={cn('platform-file-input', className)}/>
  return <AntInput {...props} ref={control} type={type} data-slot="input" className={cn('platform-input', className)}/>
})
