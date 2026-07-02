/** Chip granularity of the raise slider; the range input's `step` and the snapping agree on it. */
export const RAISE_STEP = 10

/**
 * Normalize a raw slider value so every slider position maps to a legal raise:
 * 0 stays a call/check, values below the minimum raise snap up to it, and
 * anything within one step of the top snaps to the exact all-in amount.
 *
 * The top snap exists because browsers quantize a range input to multiples of
 * `step`: when maxRaiseOnTop isn't one (e.g. 294), the largest value a drag or
 * End keypress can produce is the step below it (290), so without snapping the
 * all-in position would be unreachable.
 */
export function snapRaiseValue(raw: number, minRaise: number, maxRaiseOnTop: number): number {
  if (raw <= 0) return 0
  if (raw > maxRaiseOnTop - RAISE_STEP) return maxRaiseOnTop
  if (raw < minRaise) return minRaise
  return raw
}
