import { Container, Graphics, Text, TextStyle } from 'pixi.js'
import { hex } from '../../theme'
import type { GameStateUpdate } from '../../api/types'
import { potResultLines } from '../../game/potResults'
import type { SceneState } from './sceneTypes'

// ─── pot chip pyramid ─────────────────────────────────────────────────────────
// Clay chip colors: oxblood, ink, emerald, navy, gold — the classic denominations.
const STACK_COLORS = [0x6b2230, 0x26221f, 0x1f5c3a, 0x24466b, 0xc9a227]
const MAX_CHIPS_TOTAL = 16

export const CW = 22  // chip ellipse width (CHIP_W)

/** Grows with the pot measured in big blinds, so it reads the same at any stack depth. */
function chipCountFor(pot: number, bigBlind: number) {
  return pot <= 0 ? 0 : Math.min(Math.round(Math.sqrt(pot / Math.max(1, bigBlind))) + 1, MAX_CHIPS_TOTAL)
}

// Each variant defines groups filled sequentially (group 0 fills completely before group 1 starts).
// Within a group, chips are distributed evenly across all positions — so new stacks appear in sync.
// 5 variants are defined; one is chosen randomly when the pot resets.
export interface PotSlotDef { cx: number; colorOff: number; raise: number }
export interface PotGroup { max: number; positions: PotSlotDef[] }
export interface PotStackDef { cx: number; count: number; colorOff: number; raise: number }

export function fillPotGroups(chips: number, groups: PotGroup[]): PotStackDef[] {
  let rem = chips
  const out: PotStackDef[] = []
  for (const grp of groups) {
    if (rem <= 0) break
    const k = grp.positions.length
    const take = Math.min(rem, grp.max * k)
    const base = Math.floor(take / k)
    const extra = take - base * k
    grp.positions.forEach((pos, i) => {
      const count = base + (i < extra ? 1 : 0)
      if (count > 0) out.push({ ...pos, count })
    })
    rem -= take
  }
  return out
}

// 5 distinct visual themes. Each grows consistently: higher chip counts expand on lower ones.
export const POT_VARIANTS: PotGroup[][] = [
  // 0 — Classic Pyramid: center fills first, then symmetric wings, then outer wings
  [
    { max: 5, positions: [{ cx: 0, colorOff: 2, raise: -3 }] },
    { max: 4, positions: [{ cx: -CW * 0.75, colorOff: 0, raise: 0 }, { cx: CW * 0.75, colorOff: 4, raise: 0 }] },
    { max: 3, positions: [{ cx: -CW * 1.5,  colorOff: 1, raise: 0 }, { cx: CW * 1.5,  colorOff: 3, raise: 0 }] },
  ],
  // 1 — Twin Peaks: two equal side columns rise first, center fills between them, outer flanks last
  [
    { max: 5, positions: [{ cx: -CW * 0.58, colorOff: 0, raise: 0 }, { cx: CW * 0.58, colorOff: 3, raise: 0 }] },
    { max: 3, positions: [{ cx: 0, colorOff: 2, raise: 3 }] },
    { max: 2, positions: [{ cx: -CW * 1.25, colorOff: 1, raise: 0 }, { cx: CW * 1.25, colorOff: 4, raise: 0 }] },
  ],
  // 2 — Wide Spread: outer positions establish first, then inner, then center bridges the gap
  [
    { max: 4, positions: [{ cx: -CW * 1.45, colorOff: 0, raise: 0 }, { cx: CW * 1.45, colorOff: 3, raise: 0 }] },
    { max: 4, positions: [{ cx: -CW * 0.72, colorOff: 1, raise: 0 }, { cx: CW * 0.72, colorOff: 4, raise: 0 }] },
    { max: 4, positions: [{ cx: 0, colorOff: 2, raise: -2 }] },
  ],
  // 3 — Staircase: fills left-to-right with descending max, so left is always the tallest column
  [
    { max: 7, positions: [{ cx: -CW * 1.0,  colorOff: 0, raise: 0 }] },
    { max: 4, positions: [{ cx: -CW * 0.33, colorOff: 2, raise: 0 }] },
    { max: 3, positions: [{ cx:  CW * 0.33, colorOff: 4, raise: 0 }] },
    { max: 2, positions: [{ cx:  CW * 1.0,  colorOff: 1, raise: 0 }] },
  ],
  // 4 — Cluster: three tight stacks grow together from the start, outer stacks expand last
  [
    { max: 4, positions: [
      { cx: -CW * 0.47, colorOff: 0, raise: 0 },
      { cx: 0,          colorOff: 2, raise: -2 },
      { cx:  CW * 0.47, colorOff: 4, raise: 0 },
    ]},
    { max: 3, positions: [{ cx: -CW * 0.94, colorOff: 1, raise: 1 }, { cx: CW * 0.94, colorOff: 3, raise: 1 }] },
  ],
]

export function randomPotVariant() {
  return Math.floor(Math.random() * POT_VARIANTS.length)
}

/** Where the pot's chips sit, relative to the pot container — flying chips land here. */
export const POT_Y = 35

/** Draws clay chip stacks (bottom chip at y = raise, growing upward) onto `g`. Shared by the pot and seat piles. */
export function drawChipStacks(g: Graphics, stacks: PotStackDef[]) {
  const CHIP_H = 8, OVERLAP = 5
  const rw = CW / 2, rh = CHIP_H / 2
  for (const { cx, count, colorOff, raise } of stacks) {
    for (let i = 0; i < count; i++) {
      const col = STACK_COLORS[(colorOff + i) % STACK_COLORS.length]
      const y = raise - i * (CHIP_H - OVERLAP)

      // Drop shadow
      g.ellipse(cx, y + rh * 0.65, rw, rh * 0.55).fill({ color: 0x000000, alpha: 0.3 })
      // Main chip face
      g.ellipse(cx, y, rw, rh).fill({ color: col })
      // Ivory edge spots on the visible band — reads as a real clay chip
      for (const tx of [-rw * 0.55, 0, rw * 0.55]) {
        g.rect(cx + tx - 1, y + rh * 0.25, 2, rh * 0.55).fill({ color: 0xf5efdc, alpha: 0.5 })
      }
      // Top rim highlight — rounded edge catching light
      g.ellipse(cx, y - rh * 0.42, rw * 0.78, rh * 0.38).fill({ color: 0xffffff, alpha: 0.22 })
      // Edge stroke
      g.ellipse(cx, y, rw, rh).stroke({ color: 0x000000, alpha: 0.52, width: 1 })
    }
  }
}

const S_POT_WORD   = new TextStyle({ fontFamily: 'Cinzel, Georgia, serif', fontSize: 11, fontWeight: '600', fill: hex.gold, letterSpacing: 2 })
const S_POT_AMOUNT = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 14, fontWeight: 'bold', fill: hex.cream })
const S_POT_RESULT = new TextStyle({ fontFamily: 'Cinzel, Georgia, serif', fontSize: 11, fontWeight: '700', fill: hex.goldBright, letterSpacing: 0.5 })
const S_POT_RESULT_COMPACT = new TextStyle({ ...S_POT_RESULT, fontSize: 10 })

/** Centre-to-centre spacing of the pots when side pots split the pot into a row. */
const POT_SLOT_W = 116
const POT_SLOT_W_COMPACT = 96
const LABEL_Y = 20
const RESULT_LINE_H = 15
const RESULT_LINE_H_COMPACT = 12

/** "POT 1,250" — engraved word in gold, amount in cream, centered as one unit. */
function makePotLabel(word: string, potTotal: number): Container {
  const c = new Container()
  const wordT  = new Text({ text: word, style: S_POT_WORD })
  const amount = new Text({ text: potTotal.toLocaleString('en-US'), style: S_POT_AMOUNT })
  const GAP = 7
  const total = wordT.width + GAP + amount.width
  wordT.anchor.set(0, 0.5)
  amount.anchor.set(0, 0.5)
  wordT.position.set(-total / 2, 1)
  amount.position.set(-total / 2 + wordT.width + GAP, 0)
  wordT.alpha = 0.85
  c.addChild(wordT, amount)
  return c
}

/** Horizontal centre of pot [index] of [count], relative to the pot container. */
export function potSlotX(index: number, count: number, compact: boolean): number {
  return (index - (count - 1) / 2) * (compact ? POT_SLOT_W_COMPACT : POT_SLOT_W)
}

function potWord(index: number, count: number): string {
  if (count === 1) return 'POT'
  if (index === 0) return 'MAIN'
  return index === 1 ? 'SIDE' : `SIDE ${index}`
}

/** The showdown result lines on a dark backing so they read over the felt and the chips. */
function makeResultLines(lines: string[], compact: boolean): Container {
  const c = new Container()
  const lineH = compact ? RESULT_LINE_H_COMPACT : RESULT_LINE_H
  const texts = lines.map((line, i) => {
    const t = new Text({ text: line, style: compact ? S_POT_RESULT_COMPACT : S_POT_RESULT })
    t.anchor.set(0.5, 0)
    t.y = i * lineH
    return t
  })
  const w = Math.max(...texts.map((t) => t.width)) + 16
  const h = lines.length * lineH + 5
  const backing = new Graphics().roundRect(-w / 2, -3, w, h, 6).fill({ color: 0x000000, alpha: 0.45 })
  c.addChild(backing, ...texts)
  return c
}

/**
 * The pot as a row of stacks — one "POT" normally, "MAIN" and "SIDE" once an all-in splits it — with the
 * showdown's result lines underneath, kept on the felt until the next deal.
 */
export function updatePot(scene: SceneState, state: GameStateUpdate) {
  const { potTotal, roundStage: stage } = state
  const amounts = state.pots.length > 1 ? state.pots.map((pot) => pot.amount) : [potTotal]
  const live = potResultLines(state.pots, state.players)
  const lines = live.length > 0 ? live : stage == null ? scene.retainedPotLines : []

  const key = [potTotal, stage, scene.compact, amounts.join(','), lines.join('\n')].join('|')
  if (key === scene.lastPotKey) return

  // Pick a new variant when the pot resets so each round gets a fresh layout style
  if (potTotal === 0 || (scene.lastPotTotal > 0 && potTotal < scene.lastPotTotal)) {
    scene.potVariant = randomPotVariant()
  }

  scene.lastPotTotal = potTotal
  scene.lastPotKey = key

  const { potContainer } = scene
  potContainer.removeChildren()
  potContainer.y = POT_Y

  const bigBlind = state.blinds.big
  amounts.forEach((amount, i) => {
    const x = potSlotX(i, amounts.length, scene.compact)
    if (amount > 0) {
      const g = new Graphics()
      drawChipStacks(g, fillPotGroups(chipCountFor(amount, bigBlind), POT_VARIANTS[scene.potVariant]))
      g.x = x
      potContainer.addChild(g)
    }
    // A phone has no room below the label — the bottom seat's showdown row is right there — so the result
    // takes the label's place (the pot has been paid out by the time it shows).
    if (scene.compact && lines.length > 0) return
    const label = makePotLabel(potWord(i, amounts.length), amount)
    label.position.set(x, LABEL_Y)
    potContainer.addChild(label)
  })

  if (lines.length > 0) {
    const results = makeResultLines(lines, scene.compact)
    results.y = scene.compact ? LABEL_Y - 6 : LABEL_Y + 16
    potContainer.addChild(results)
  }
}
