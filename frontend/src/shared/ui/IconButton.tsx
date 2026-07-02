import type { ButtonHTMLAttributes } from 'react'

import { cn } from '@/shared/lib/cn'

export interface IconButtonProps
  extends ButtonHTMLAttributes<HTMLButtonElement> {
  /** Accessible name is mandatory: icon-only buttons carry no text. */
  'aria-label': string
  size?: 'sm' | 'md'
}

const sizeClasses = {
  sm: 'h-6 w-6 text-xs',
  md: 'h-8 w-8 text-sm',
}

export function IconButton({
  size = 'md',
  className,
  type = 'button',
  ...rest
}: IconButtonProps) {
  return (
    <button
      type={type}
      className={cn(
        'inline-flex select-none items-center justify-center rounded-md text-muted transition-colors',
        'hover:bg-elevated hover:text-primary',
        'outline-none focus-visible:ring-2 focus-visible:ring-accent/60',
        'disabled:cursor-not-allowed disabled:opacity-45 disabled:hover:bg-transparent disabled:hover:text-muted',
        sizeClasses[size],
        className,
      )}
      {...rest}
    />
  )
}
