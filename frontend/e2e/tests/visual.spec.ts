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
// maxDiffPixelRatio=0.02 tolerates minor WebGL anti-aliasing variation.

const SNAP_OPTS = { maxDiffPixelRatio: 0.02 } as const

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
      // 2-player table — easier to engineer an all-in scenario
      const tableId = await bot1.createTable('Bot1', { startingChips: 300, turnTimerSeconds: 120 })
      await openTable(page, tableId)

      await page.fill('input[placeholder="Your name"]', 'Human')
      await page.click('button[type="submit"]')
      await expect.poll(() => gs(page).then((s) => s?.players.length), { timeout: 10_000 }).toBe(2)
      const humanId = await gs(page).then((s) => s?.players.find((p) => p.name === 'Human')?.id)

      await bot1.startRound(tableId)

      // Drive bots until human's turn, then human goes all-in
      await driveBotsUntil(humanId!, [bot1], tableId)
      await expect
        .poll(() => gs(page).then((s) => s?.nextPlayerIdToAct), { timeout: 10_000 })
        .toBe(humanId)
      await page.locator('[data-testid="btn-call"]').click()

      // Now drive bot until human has 0 chips (mid-round)
      for (let i = 0; i < 20; i++) {
        const state = await bot1.getState(tableId)
        const humanChips = state.players.find((p) => p.id === humanId)?.chips ?? -1
        if (humanChips === 0) {
          const current = await gs(page)
          if (current?.roundStage != null) {
            await page.waitForTimeout(CANVAS_SETTLE_MS)
            const bar = page.locator('[data-testid="control-bar"]')
            await expect(bar).toHaveScreenshot('control-bar-zero-chips.png', SNAP_OPTS)
            return
          }
        }
        if (state.nextPlayerIdToAct === bot1.playerId) await bot1.act(tableId, 'CALL')
        await new Promise((r) => setTimeout(r, 400))
      }
    } finally {
      await bot1.dispose()
    }
  })
})
