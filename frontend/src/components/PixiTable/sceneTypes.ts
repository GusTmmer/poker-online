import type { Application, Container, Text, Ticker } from 'pixi.js'
import type { GameStateUpdate, PlayerView } from '../../api/types'
import type { SeatObjects } from './drawSeat'

export interface SeatEntry {
  slot: number
  playerId: number
  seatObj: SeatObjects
  miniCards: Container
  miniCardsVisible: boolean
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

export interface FlyingChip { sprite: Container; fromX: number; fromY: number; elapsed: number; duration: number; done: boolean }
export interface WinnerChip { sprite: Container; toX: number; toY: number; elapsed: number; delay: number; duration: number; done: boolean }
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

export interface SceneState {
  app: Application
  root: Container
  seatsLayer: Container
  emptySeatsLayer: Container
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
  lastRoundStage: string | null | undefined
  potVariant: number
}
