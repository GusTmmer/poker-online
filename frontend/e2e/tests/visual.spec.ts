import { test, expect } from '@playwright/test'
import { BotClient } from '../helpers/api'

const CANVAS_SETTLE_MS = 700

type GameState = {
  roundStage: string | null
  nextPlayerIdToAct: number | null
  players: Array<{ id: number; name: string; chips: number }>
  communityCards: string[]
}

/** Fetch the latest game_state frame from the browser's WS spy (runs in Node.js context). */
async function gs(page: import('@playwright/test').Page): Promise<GameState | null> {
  return page.evaluate(() => (window as { __lastGameState?: GameState }).__lastGameState ?? null)
}

/** Inject WS spy and navigate. */
async function openTable(page: import('@playwright/test').Page, tableId: number) {
  await page.addInitScript(() => {
    const OrigWS = window.WebSocket
    window.WebSocket = class extends OrigWS {
      constructor(...args: ConstructorParameters<typeof OrigWS>) {
        super(...args)
        this.addEventListener('message', (e) => {
          try {
            const data = JSON.parse(e.data as string)
            if (data.type === 'game_state') {
              ;(window as { __lastGameState?: unknown }).__lastGameState = data
            }
          } catch { /* ignore */ }
        })
      }
    }
  })
  await page.goto(`/table/${tableId}`, { waitUntil: 'networkidle' })
}

/** Drive bot players to act (call) until `stopId`'s turn arrives or the round ends. */
async function driveBotsUntil(
  stopId: number,
  bots: BotClient[],
  tableId: number,
  maxPolls = 40,
) {
  for (let i = 0; i < maxPolls; i++) {
    const state = await bots[0].getState(tableId)
    if (state.nextPlayerIdToAct === stopId || state.nextPlayerIdToAct == null) return
    const acting = bots.find((b) => b.playerId === state.nextPlayerIdToAct)
    if (acting) await acting.act(tableId, 'CALL')
    await new Promise((r) => setTimeout(r, 300))
  }
}

// ── snapshots ────────────────────────────────────────────────────────────────
// First run:  npm run test:e2e:update   (generates baselines)
// Subsequent: npm run test:e2e          (compares against baselines)
// Baselines stored in e2e/snapshots/ and committed to git.
//
// Tolerances are deliberately tight so that real visual changes fail:
//  - threshold (per-pixel YIQ distance) 0.1 instead of the 0.2 default, so
//    broad low-contrast changes (e.g. a felt recolor) register as diff pixels.
//  - maxDiffPixelRatio 0.005 (~4.6k px of 1280x720), enough headroom for
//    SwiftShader anti-aliasing jitter but far below any redesign-level change.
// NOTE: Playwright only rewrites baselines it considers "changed", so after an
// intentional redesign run `playwright test --update-snapshots=all`.

const SNAP_OPTS = { threshold: 0.1, maxDiffPixelRatio: 0.005 } as const

test.describe('visual: lobby', () => {
  test('waiting room — 3 players, no round', async ({ page }) => {
    const host = await BotClient.create()
    const bot2 = await BotClient.create()

    try {
      const tableId = await host.createTable('Host')
      await bot2.join(tableId, 'Bot2')
      await openTable(page, tableId)

      await page.fill('input[placeholder="Your name"]', 'Human')
      await page.click('button[type="submit"]')

      await expect.poll(() => gs(page).then((s) => s?.players.length), { timeout: 10_000 }).toBe(3)
      await page.waitForTimeout(CANVAS_SETTLE_MS)

      await expect(page).toHaveScreenshot('lobby-3-players.png', SNAP_OPTS)
    } finally {
      await Promise.all([host.dispose(), bot2.dispose()])
    }
  })
})

test.describe('visual: in-round', () => {
  test('opponent turn — gold halo on opponent, my buttons disabled', async ({ page }) => {
    const bot1 = await BotClient.create()
    const bot2 = await BotClient.create()

    try {
      const tableId = await bot1.createTable('Bot1', { turnTimerSeconds: 120 })
      await bot2.join(tableId, 'Bot2')
      await openTable(page, tableId)

      await page.fill('input[placeholder="Your name"]', 'Human')
      await page.click('button[type="submit"]')
      await expect.poll(() => gs(page).then((s) => s?.players.length), { timeout: 10_000 }).toBe(3)
      const humanId = await gs(page).then((s) => s?.players.find((p) => p.name === 'Human')?.id)

      await bot1.startRound(tableId)

      // Stop as soon as the round is in a betting stage and it's NOT the human's turn
      await expect
        .poll(
          () => gs(page).then((s) => s?.roundStage != null && s?.nextPlayerIdToAct !== humanId),
          { timeout: 10_000 },
        )
        .toBe(true)
      await page.waitForTimeout(CANVAS_SETTLE_MS)

      await expect(page).toHaveScreenshot('opponent-turn.png', SNAP_OPTS)
    } finally {
      await Promise.all([bot1.dispose(), bot2.dispose()])
    }
  })

  test('my turn — gold halo on me, buttons enabled', async ({ page }) => {
    const bot1 = await BotClient.create()
    const bot2 = await BotClient.create()

    try {
      const tableId = await bot1.createTable('Bot1', { turnTimerSeconds: 120 })
      await bot2.join(tableId, 'Bot2')
      await openTable(page, tableId)

      await page.fill('input[placeholder="Your name"]', 'Human')
      await page.click('button[type="submit"]')
      await expect.poll(() => gs(page).then((s) => s?.players.length), { timeout: 10_000 }).toBe(3)
      const humanId = await gs(page).then((s) => s?.players.find((p) => p.name === 'Human')?.id)

      await bot1.startRound(tableId)
      await driveBotsUntil(humanId!, [bot1, bot2], tableId)

      await expect
        .poll(() => gs(page).then((s) => s?.nextPlayerIdToAct), { timeout: 10_000 })
        .toBe(humanId)
      await page.waitForTimeout(CANVAS_SETTLE_MS)

      await expect(page).toHaveScreenshot('my-turn.png', SNAP_OPTS)
    } finally {
      await Promise.all([bot1.dispose(), bot2.dispose()])
    }
  })

  test('flop — 3 community cards visible', async ({ page }) => {
    const bot1 = await BotClient.create()
    const bot2 = await BotClient.create()

    try {
      const tableId = await bot1.createTable('Bot1', { turnTimerSeconds: 120 })
      await bot2.join(tableId, 'Bot2')
      await openTable(page, tableId)

      await page.fill('input[placeholder="Your name"]', 'Human')
      await page.click('button[type="submit"]')
      await expect.poll(() => gs(page).then((s) => s?.players.length), { timeout: 10_000 }).toBe(3)
      const humanId = await gs(page).then((s) => s?.players.find((p) => p.name === 'Human')?.id)

      await bot1.startRound(tableId)

      // Play every player through blinds round so flop is dealt
      for (let i = 0; i < 20; i++) {
        const state = await bot1.getState(tableId)
        if (state.nextPlayerIdToAct == null) break
        if (state.nextPlayerIdToAct === bot1.playerId) await bot1.act(tableId, 'CALL')
        else if (state.nextPlayerIdToAct === bot2.playerId) await bot2.act(tableId, 'CALL')
        // Human: check/call via browser if it's their turn
        else if (state.nextPlayerIdToAct === humanId) {
          const current = await gs(page)
          if (current?.roundStage === 'BET_FLOP') break // reached flop, stop
          await page.locator('[data-testid="btn-call"]').click().catch(() => { /* ignore if disabled */ })
        }
        const roundStage = await gs(page).then((s) => s?.roundStage)
        if (roundStage === 'BET_FLOP') break
        await new Promise((r) => setTimeout(r, 350))
      }

      await expect
        .poll(() => gs(page).then((s) => s?.roundStage), { timeout: 10_000 })
        .toBe('BET_FLOP')
      await page.waitForTimeout(CANVAS_SETTLE_MS)

      await expect(page).toHaveScreenshot('flop.png', SNAP_OPTS)
    } finally {
      await Promise.all([bot1.dispose(), bot2.dispose()])
    }
  })

  test('control bar: 0-chip player sees only the corner menu', async ({ page }) => {
    const bot1 = await BotClient.create()

    try {
      // 2-player table — the human shoves all-in on their first turn. The bot never
      // responds (120s timer), so the human sits at 0 chips mid-round: a stable state.
      const tableId = await bot1.createTable('Bot1', { startingChips: 300, turnTimerSeconds: 120 })
      await openTable(page, tableId)

      await page.fill('input[placeholder="Your name"]', 'Human')
      await page.click('button[type="submit"]')
      await expect.poll(() => gs(page).then((s) => s?.players.length), { timeout: 10_000 }).toBe(2)
      const humanId = await gs(page).then((s) => s?.players.find((p) => p.name === 'Human')?.id)

      await bot1.startRound(tableId)

      // Drive the bot until it's the human's turn, then shove. The slider UI can't
      // reach All In when maxRaiseOnTop isn't step-aligned (see ControlBar), so the
      // shove goes through the browser's own session (JWT cookie) directly.
      await driveBotsUntil(humanId!, [bot1], tableId)
      await expect
        .poll(() => gs(page).then((s) => s?.nextPlayerIdToAct), { timeout: 10_000 })
        .toBe(humanId)
      await page.evaluate(async (id) => {
        const res = await fetch(`/api/tables/${id}/action`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          credentials: 'include',
          body: JSON.stringify({ type: 'ALL_IN' }),
        })
        if (!res.ok) throw new Error(`ALL_IN rejected: ${res.status}`)
      }, tableId)

      // Human must now be at 0 chips with the round still in progress.
      await expect
        .poll(
          async () => {
            const s = await gs(page)
            return {
              chips: s?.players.find((p) => p.id === humanId)?.chips,
              inRound: s?.roundStage != null,
            }
          },
          { timeout: 10_000 },
        )
        .toEqual({ chips: 0, inRound: true })

      // Spectating mode: the action buttons are gone, only the bar shell remains.
      await expect(page.locator('[data-testid="btn-fold"]')).toHaveCount(0)
      await expect(page.locator('[data-testid="btn-call"]')).toHaveCount(0)

      await page.waitForTimeout(CANVAS_SETTLE_MS)
      const bar = page.locator('[data-testid="control-bar"]')
      // Mask the countdown — its digits change between runs.
      await expect(bar).toHaveScreenshot('control-bar-zero-chips.png', {
        ...SNAP_OPTS,
        mask: [page.locator('[data-testid="turn-timer"]')],
      })
    } finally {
      await bot1.dispose()
    }
  })
})
