import type { Application, Container, Text, Ticker } from 'pixi.js'
import type { GameStateUpdate, PlayerView } from '../../api/types'
import type { SeatObjects } from './drawSeat'
import type { ActionBurst } from './burst'
import type { Frame } from '../../game/frameBus'

export interface SeatEntry {
  slot: number
  playerId: number
  seatObj: SeatObjects
  miniCards: Container
  miniCardsVisible: boolean
  /** The player's stack drawn as chips on the felt; redrawn only when [pileCount] changes. */
  pile: Container
  pileCount: number
}

export interface CommCardEntry {
  outerContainer: Container
  innerContainer: Container
  cardCode: string
  batchIndex: number
  elapsed: number
  phase: 'pre' | 'slide' | 'flip-out' | 'flip-in' | 'done'
  startX: number
  startY: number
  targetX: number
}

export interface CommDeck { sprite: Container; elapsed: number; hideAt: number }

export interface FlyingChip { sprite: Container; fromX: number; fromY: number; toX: number; toY: number; delay: number; elapsed: number; duration: number; done: boolean }
export interface WinnerChip { sprite: Container; fromX: number; fromY: number; toX: number; toY: number; elapsed: number; delay: number; duration: number; done: boolean }
export interface FloatingDelta { label: Text; y0: number; elapsed: number }

export interface DealCard {
  sprite: Container
  toX: number; toY: number
  delay: number; elapsed: number; done: boolean
}

export interface Tween {
  // A tween target is any object with numeric props mutated by string key
  // (Pixi Containers, Text, etc.); the engine only ever reads/writes numbers.
  target: Record<string, number>
  props: Record<string, number>; startProps: Record<string, number>
  elapsed: number; duration: number; delay: number; ease: string; onDone?: () => void; done: boolean
}

/**
 * An all-in runout being played street by street. The frame that reached SHOWDOWN is
 * held in [final] (its winners and chip payouts would spoil the board), and any
 * frames arriving meanwhile queue behind it.
 */
export interface Runout {
  final: Frame
  queued: Frame[]
  timers: ReturnType<typeof setTimeout>[]
  /** Community cards on the felt so far — selects which of the frame's runout odds the seats show. */
  boardShown: number
}

export interface SceneState {
  app: Application
  root: Container
  seatsLayer: Container
  emptySeatsLayer: Container
  chipPilesLayer: Container
  miniCardsLayer: Container
  communityRow: Container
  potContainer: Container
  myCardsContainer: Container
  animLayer: Container
  seats: Map<number, SeatEntry>
  commCards: CommCardEntry[]
  commDeck: CommDeck | null
  flyingChips: FlyingChip[]
  winnerChips: WinnerChip[]
  floatingDeltas: FloatingDelta[]
  dealCards: DealCard[]
  tweens: Tween[]
  bursts: ActionBurst[]
  runout: Runout | null
  /**
   * Pacing hold: warm frames arriving before [holdUntil] (a performance.now() time) wait in [held]
   * and play one by one, so an action never lands on top of a deal, a street, or the previous action.
   */
  held: Frame[]
  holdUntil: number
  holdTimer: ReturnType<typeof setTimeout> | null
  /**
   * Phone layout: simplified card faces, pocket-only showdown rows placed on the table side of
   * each seat, and badges beside the avatar — so seats fit a short screen.
   */
  compact: boolean
  /** performance.now() at which the current deal animation settles (0 when not dealing). */
  dealEndsAt: number
  ticker: Ticker
  isDealing: boolean
  isCommunityDealing: boolean
  /** Latest snapshot applied via applySnapshot — event handlers read geometry from it. */
  lastApplied: GameStateUpdate | null
  myPlayerId: number
  maxPlayers: number
  /** Best hands kept on the felt from showdown until the next deal. */
  retainedShowdownHands: Map<number, PlayerView['bestHand']>
  /** Pocket cards per player captured at showdown — outlives the live snapshot's
   *  pocketCards (which the server hides once the round clears) so the gold pocket
   *  outline stays consistent while the retained hand is shown. */
  retainedPocketCards: Map<number, string[]>
  /** Community cards currently rendered face-up — drives reveal vs. instant render. */
  shownCommunity: string[]
  winnerPlayerIds: Set<number>
  myCardsPocketKey: string
  lastPotTotal: number
  /** Everything the pot drawing depends on; redrawn only when it changes. */
  lastPotKey: string
  /** Showdown result lines under the pot ("Ada wins — Queen kicker"), kept until the next deal. */
  retainedPotLines: string[]
  /** Each pot's winners from the showdown, main pot first — where the payout chips fly from. */
  retainedPotWinners: number[][]
  potVariant: number
}
