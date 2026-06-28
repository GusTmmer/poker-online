import { Graphics, Text, TextStyle } from 'pixi.js'
import { palette } from '../../theme'
import type { SceneState } from './sceneTypes'

// ─── pot chip pyramid ─────────────────────────────────────────────────────────
const STACK_COLORS = [0x7a1f2b, 0x1c1c1c, 0x1f5c3a, 0x1a3a6b, 0xc9a227]
const MAX_CHIPS_TOTAL = 16

const CW = 22  // chip ellipse width (CHIP_W)

function chipCountFor(pot: number) {
  return pot <= 0 ? 0 : Math.min(Math.round(Math.sqrt(pot / 3)) + 1, MAX_CHIPS_TOTAL)
}

// Each variant defines groups filled sequentially (group 0 fills completely before group 1 starts).
// Within a group, chips are distributed evenly across all positions — so new stacks appear in sync.
// 5 variants are defined; one is chosen randomly when the pot resets.
interface PotSlotDef { cx: number; colorOff: number; raise: number }
interface PotGroup { max: number; positions: PotSlotDef[] }
interface PotStackDef { cx: number; count: number; colorOff: number; raise: number }

function fillPotGroups(chips: number, groups: PotGroup[]): PotStackDef[] {
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

const S_POT = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 14, fontWeight: 'bold', fill: palette.gold })

export function updatePot(scene: SceneState, potTotal: number, stage: string | null) {
  if (potTotal === scene.lastPotTotal && stage === scene.lastRoundStage) return

  // Pick a new variant when the pot resets so each round gets a fresh layout style
  if (potTotal === 0 || (scene.lastPotTotal > 0 && potTotal < scene.lastPotTotal)) {
    scene.potVariant = randomPotVariant()
  }

  scene.lastPotTotal = potTotal
  scene.lastRoundStage = stage

  const { potContainer } = scene
  potContainer.removeChildren()
  potContainer.y = 35

  if (potTotal > 0) {
    const g = new Graphics()
    const CHIP_H = 8, OVERLAP = 5
    const rw = CW / 2, rh = CHIP_H / 2

    const stacks = fillPotGroups(chipCountFor(potTotal), POT_VARIANTS[scene.potVariant])

    for (const { cx, count, colorOff, raise } of stacks) {
      for (let i = 0; i < count; i++) {
        const col = STACK_COLORS[(colorOff + i) % STACK_COLORS.length]
        const y = raise - i * (CHIP_H - OVERLAP)

        // Drop shadow
        g.ellipse(cx, y + rh * 0.65, rw, rh * 0.55).fill({ color: 0x000000, alpha: 0.3 })
        // Main chip face
        g.ellipse(cx, y, rw, rh).fill({ color: col })
        // Top rim highlight — rounded edge catching light
        g.ellipse(cx, y - rh * 0.42, rw * 0.78, rh * 0.38).fill({ color: 0xffffff, alpha: 0.22 })
        // Edge stroke
        g.ellipse(cx, y, rw, rh).stroke({ color: 0x000000, alpha: 0.52, width: 1 })
      }
    }
    potContainer.addChild(g)
  }

  const label = new Text({ text: `Pot: ${potTotal}`, style: S_POT })
  label.anchor.set(0.5, 0)
  label.y = 14
  potContainer.addChild(label)
}
