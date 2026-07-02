import type { ReactNode } from 'react'

import { cn } from '@/shared/lib/cn'

/** File-status chip variants map 1:1 to the VCS color tokens (docs/06 §3). */
export type BadgeVariant =
  | 'default'
  | 'accent'
  | 'added'
  | 'modified'
  | 'deleted'
  | 'conflicted'
  | 'renamed'

const variantClasses: Record<BadgeVariant, string> = {
  default: 'bg-elevated text-muted',
  accent: 'bg-accent/15 text-accent',
  added: 'bg-vcs-added/15 text-vcs-added',
  modified: 'bg-vcs-modified/15 text-vcs-modified',
  deleted: 'bg-vcs-deleted/15 text-vcs-deleted',
  conflicted: 'bg-vcs-conflicted/15 text-vcs-conflicted',
  renamed: 'bg-vcs-renamed/15 text-vcs-renamed',
}

export interface BadgeProps {
  variant?: BadgeVariant
  children: ReactNode
  className?: string
}

export function Badge({ variant = 'default', children, className }: BadgeProps) {
  return (
    <span
      className={cn(
        'inline-flex select-none items-center rounded px-1.5 py-0.5 text-[11px] font-semibold leading-none',
        variantClasses[variant],
        className,
      )}
    >
      {children}
    </span>
  )
}
