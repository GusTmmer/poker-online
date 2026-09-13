/**
 * Chip granularity of the raise slider: the small blind, since no-limit bets needn't be multiples of
 * the big blind. The range input's `step` and the snapping agree on it.
 */
export function raiseStep(smallBlind: number): number {
  return Math.max(1, smallBlind)
}

/**
 * Normalize a raw slider value so every slider position maps to a legal raise:
 * 0 stays a call/check, values below the minimum raise snap up to it, and
 * anything within one step of the top snaps to the exact all-in amount. When the
 * stack can't cover a full minimum raise, any raise is the all-in.
 *
 * The top snap exists because browsers quantize a range input to multiples of
 * `step`: when maxRaiseOnTop isn't one (e.g. 294), the largest value a drag or
 * End keypress can produce is the step below it (290), so without snapping the
 * all-in position would be unreachable.
 */
export function snapRaiseValue(raw: number, minRaise: number, maxRaiseOnTop: number, step: number): number {
  if (raw <= 0) return 0
  if (minRaise >= maxRaiseOnTop || raw > maxRaiseOnTop - step) return maxRaiseOnTop
  if (raw < minRaise) return minRaise
  return raw
}
