import { CARD_W, CARD_GAP } from './drawCard'

// ─── animation timing (community cards) ──────────────────────────────────────
export const DECK_DELAY_MS    = 220
export const SLIDE_MS         = 400
export const FLIP_PAUSE_MS    = 80
export const FLIP_STAGGER_MS  = 180
export const FLIP_HALF_MS     = 140
export const DECK_LINGER_MS   = 300

// ─── deal animation ───────────────────────────────────────────────────────────
export const MINI_SCALE       = 0.3
export const MY_CARD_SCALE    = 0.95
export const MY_CARD_X        = 120   // logical px right of canvas center
export const DEAL_INTERVAL_MS = 420
export const DEAL_FLIGHT_MS   = 320
export const HALF_SPACING     = (CARD_W + CARD_GAP) / 2 * MINI_SCALE

// ─── community card row ───────────────────────────────────────────────────────
export const COMM_SCALE = 0.72   // scale down community cards so they don't crowd the pot
export const COMM_GAP   = 6
export const commRowW   = (n: number) => n * CARD_W + (n - 1) * COMM_GAP
