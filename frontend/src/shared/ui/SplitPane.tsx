import type { ComponentProps } from 'react'
import { Group, Panel, Separator } from 'react-resizable-panels'

import { cn } from '@/shared/lib/cn'

/**
 * Thin design-system wrapper over `react-resizable-panels` v4.
 *
 * Why the library instead of a hand-rolled pointer-event pane: it ships
 * keyboard-accessible ARIA separators, px/percent min-max constraints and
 * persistable layouts (`ui_state` in later milestones) in ~4 kB, which we
 * would otherwise have to rebuild and test ourselves.
 */

export type SplitPaneProps = ComponentProps<typeof Group>

/** Container: `orientation="horizontal"` lays panes side by side. */
export function SplitPane({ className, ...rest }: SplitPaneProps) {
  return <Group className={cn('h-full w-full', className)} {...rest} />
}

export type PaneProps = ComponentProps<typeof Panel>

export function Pane({ className, ...rest }: PaneProps) {
  return <Panel className={cn('min-h-0 min-w-0', className)} {...rest} />
}

export interface SplitHandleProps
  extends Omit<ComponentProps<typeof Separator>, 'children'> {
  /**
   * Visual direction of the bar itself:
   * `vertical` bar sits between side-by-side panes (horizontal group),
   * `horizontal` bar sits between stacked panes (vertical group).
   */
  direction?: 'vertical' | 'horizontal'
}

export function SplitHandle({
  direction = 'vertical',
  className,
  ...rest
}: SplitHandleProps) {
  return (
    <Separator
      className={cn(
        'shrink-0 bg-border transition-colors duration-150',
        'hover:bg-accent focus-visible:bg-accent outline-none',
        'data-[resizing]:bg-accent',
        direction === 'vertical'
          ? 'w-px cursor-col-resize'
          : 'h-px cursor-row-resize',
        className,
      )}
      {...rest}
    />
  )
}
