import type { ButtonHTMLAttributes } from 'react'

import { cn } from '@/shared/lib/cn'

export type ButtonVariant = 'primary' | 'secondary' | 'ghost'
export type ButtonSize = 'sm' | 'md'

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: ButtonSize
}

const variantClasses: Record<ButtonVariant, string> = {
  primary:
    'bg-accent text-white hover:brightness-110 active:brightness-95 disabled:hover:brightness-100',
  secondary:
    'border border-border bg-elevated text-primary hover:border-muted/60 active:brightness-95',
  ghost: 'text-muted hover:bg-elevated hover:text-primary',
}

const sizeClasses: Record<ButtonSize, string> = {
  sm: 'h-6 px-2 text-xs',
  md: 'h-8 px-3 text-sm',
}

export function Button({
  variant = 'secondary',
  size = 'md',
  className,
  type = 'button',
  ...rest
}: ButtonProps) {
  return (
    <button
      type={type}
      className={cn(
        'inline-flex select-none items-center justify-center gap-1.5 rounded-md font-medium transition-colors',
        'outline-none focus-visible:ring-2 focus-visible:ring-accent/60',
        'disabled:cursor-not-allowed disabled:opacity-45',
        variantClasses[variant],
        sizeClasses[size],
        className,
      )}
      {...rest}
    />
  )
}
