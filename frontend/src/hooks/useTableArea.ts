import { useEffect, useState } from 'react'

/** How much of the game stage the control bar takes, from the right (phone side column) or the bottom. */
export interface TableInsets {
  right: number
  bottom: number
}

/**
 * The control bar's footprint on a stage of [stageH] CSS px: a bar (nearly) as tall as the stage is the
 * phone side column, any other sits along the bottom. Layout sizes are pre-transform, so this holds inside
 * a rotated stage too. The table is fitted to — and centred in — what's left.
 */
export function tableInsets(stageH: number, bar: HTMLElement | null | undefined, defaultBarHeight = 0): TableInsets {
  if (!bar) return { right: 0, bottom: defaultBarHeight }
  return bar.offsetHeight >= stageH * 0.9 ? { right: bar.offsetWidth, bottom: 0 } : { right: 0, bottom: bar.offsetHeight }
}

/**
 * Tracks the control bar's footprint on [stage], so overlays (announcements, toasts) can centre on the
 * table rather than on the whole screen.
 */
export function useTableArea(stage: HTMLElement | null): TableInsets {
  const [insets, setInsets] = useState<TableInsets>({ right: 0, bottom: 0 })

  useEffect(() => {
    if (!stage) return
    const bar = stage.querySelector<HTMLElement>('[data-testid="control-bar"]')
    const update = () =>
      setInsets((prev) => {
        const next = tableInsets(stage.clientHeight, bar)
        return prev.right === next.right && prev.bottom === next.bottom ? prev : next
      })
    update()
    const observer = new ResizeObserver(update)
    observer.observe(stage)
    if (bar) observer.observe(bar)
    return () => observer.disconnect()
  }, [stage])

  return insets
}
