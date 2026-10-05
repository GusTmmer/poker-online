interface Point { x: number; y: number }
interface Rect { left: number; top: number; width: number; height: number }

/**
 * Where a pointer at [point] falls along a horizontal slider whose on-screen box is [rect], from 0 (start)
 * to 1 (end), measured between the thumb's centre at either end — as a native range input does. On a game
 * stage rotated 90° clockwise the slider's own axis runs down the screen, so the screen's y is used.
 */
export function sliderFraction(point: Point, rect: Rect, rotated: boolean, thumbPx: number): number {
  const [pos, start, length] = rotated ? [point.y, rect.top, rect.height] : [point.x, rect.left, rect.width]
  const travel = length - thumbPx
  if (travel <= 0) return 0
  return Math.min(1, Math.max(0, (pos - start - thumbPx / 2) / travel))
}

/** The slider value at [fraction] of 0..[max], quantized to [step] like a range input's own value. */
export function sliderValueAt(fraction: number, max: number, step: number): number {
  return Math.min(max, Math.round((fraction * max) / step) * step)
}
