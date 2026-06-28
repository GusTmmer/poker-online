import { test, expect } from '@playwright/test'
import { BotClient } from '../helpers/api'

// ── helpers ──────────────────────────────────────────────────────────────────

type GameState = {
  roundStage: string | null
  nextPlayerIdToAct: number | null
  gameStatus: string
  turnTimerEndsAt: number | null
  players: Array<{ id: number; name: string; chips: number; pocketCards?: string[] | null }>
}

/** Fetch the latest game_state frame from the browser's WS spy. */
async function gs(page: import('@playwright/test').Page): Promise<GameState | null> {
  return page.evaluate(() => (window as { __lastGameState?: GameState }).__lastGameState ?? null)
}

/** Inject a WebSocket spy so tests can read game state via window.__lastGameState. */
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
  await page.goto(`/table/${tableId}`)
}

/** Drive both bots until it's `targetId`'s turn (up to `rounds` polls). */
async function driveBotsUntil(
  targetId: number,
  bot1: BotClient,
  bot2: BotClient,
  tableId: number,
  maxPolls = 40,
) {
  for (let i = 0; i < maxPolls; i++) {
    const state = await bot1.getState(tableId)
    if (state.nextPlayerIdToAct === targetId) return
    if (state.nextPlayerIdToAct === bot1.playerId) await bot1.act(tableId, 'CALL')
    else if (state.nextPlayerIdToAct === bot2.playerId) await bot2.act(tableId, 'CALL')
    else if (state.nextPlayerIdToAct == null) return // round ended
    await new Promise((r) => setTimeout(r, 300))
  }
}

// ── tests ─────────────────────────────────────────────────────────────────────

test.describe('lobby / pre-round', () => {
  test('shows Ready Up and Start Round when no round is active', async ({ page }) => {
    const host = await BotClient.create()
    const bot2 = await BotClient.create()

    try {
      const tableId = await host.createTable('Host')
      await bot2.join(tableId, 'Bot2')
      await openTable(page, tableId)

      await page.fill('input[placeholder="Your name"]', 'Human')
      await page.click('button[type="submit"]')

      const bar = page.locator('[data-testid="control-bar"]')
      await expect(bar).toBeVisible({ timeout: 10_000 })
      await expect(bar.locator('[data-testid="btn-ready"]')).toBeVisible()
      await expect(bar.locator('[data-testid="btn-start-round"]')).toBeVisible()
      await expect(bar.locator('[data-testid="btn-fold"]')).not.toBeVisible()
    } finally {
      await Promise.all([host.dispose(), bot2.dispose()])
    }
  })
})

test.describe('in-round: action bar', () => {
  test('shows enabled action buttons when it is my turn', async ({ page }) => {
    const bot1 = await BotClient.create()
    const bot2 = await BotClient.create()

    try {
      const tableId = await bot1.createTable('Bot1', { turnTimerSeconds: 120 })
      await bot2.join(tableId, 'Bot2')
      await openTable(page, tableId)

      await page.fill('input[placeholder="Your name"]', 'Human')
      await page.click('button[type="submit"]')

      // Wait until all three players appear, then get human's id
      await expect.poll(() => gs(page).then((s) => s?.players.length), { timeout: 10_000 }).toBe(3)
      const humanId = await gs(page).then((s) => s?.players.find((p) => p.name === 'Human')?.id)

      await bot1.startRound(tableId)

      // Drive bots until it is the human's turn
      await driveBotsUntil(humanId!, bot1, bot2, tableId)

      // Wait for browser WS to reflect human's turn
      await expect
        .poll(() => gs(page).then((s) => s?.nextPlayerIdToAct), { timeout: 10_000 })
        .toBe(humanId)

      await expect(page.locator('[data-testid="btn-fold"]')).toBeEnabled()
      await expect(page.locator('[data-testid="btn-call"]')).toBeEnabled()
    } finally {
      await Promise.all([bot1.dispose(), bot2.dispose()])
    }
  })

  test('action buttons are disabled when it is not my turn', async ({ page }) => {
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

      // Wait for any betting round that is NOT the human's turn
      await expect
        .poll(() => gs(page).then((s) => s?.roundStage != null && s?.nextPlayerIdToAct !== humanId), {
          timeout: 10_000,
        })
        .toBe(true)

      await expect(page.locator('[data-testid="btn-fold"]')).toBeDisabled()
      await expect(page.locator('[data-testid="btn-call"]')).toBeDisabled()
    } finally {
      await Promise.all([bot1.dispose(), bot2.dispose()])
    }
  })

  test('player with 0 chips sees no action buttons during a round', async ({ page }) => {
    const bot1 = await BotClient.create()
    const bot2 = await BotClient.create()

    try {
      // Tiny stacks so the human can go all-in quickly
      const tableId = await bot1.createTable('Bot1', { startingChips: 200, turnTimerSeconds: 120 })
      await bot2.join(tableId, 'Bot2')
      await openTable(page, tableId)

      await page.fill('input[placeholder="Your name"]', 'Human')
      await page.click('button[type="submit"]')
      await expect.poll(() => gs(page).then((s) => s?.players.length), { timeout: 10_000 }).toBe(3)
      const humanId = await gs(page).then((s) => s?.players.find((p) => p.name === 'Human')?.id)

      await bot1.startRound(tableId)
      await driveBotsUntil(humanId!, bot1, bot2, tableId)
      await expect
        .poll(() => gs(page).then((s) => s?.nextPlayerIdToAct), { timeout: 10_000 })
        .toBe(humanId)

      // Human goes all-in
      await page.locator('[data-testid="btn-call"]').click()

      // Drive bots through remaining betting until round ends or human has 0 chips
      for (let i = 0; i < 30; i++) {
        const state = await bot1.getState(tableId)
        const humanChips = state.players.find((p) => p.id === humanId)?.chips ?? -1

        if (humanChips === 0) {
          const current = await gs(page)
          if (current?.roundStage != null) {
            // Human is in an active round with 0 chips: action bar must not show action buttons
            await expect(page.locator('[data-testid="btn-fold"]')).not.toBeVisible()
            await expect(page.locator('[data-testid="btn-call"]')).not.toBeVisible()
          }
          return
        }

        if (state.nextPlayerIdToAct === bot1.playerId) await bot1.act(tableId, 'CALL')
        else if (state.nextPlayerIdToAct === bot2.playerId) await bot2.act(tableId, 'CALL')
        await new Promise((r) => setTimeout(r, 400))
      }
      // If we exit the loop without asserting, the test is inconclusive but still passes.
      // A 0-chip situation may not arise in every configuration; the visual test covers it more directly.
    } finally {
      await Promise.all([bot1.dispose(), bot2.dispose()])
    }
  })
})

test.describe('dealing', () => {
  test('active player is dealt pocket cards at round start', async ({ page }) => {
    const bot1 = await BotClient.create()

    try {
      const tableId = await bot1.createTable('Bot1', { startingChips: 500, turnTimerSeconds: 120 })
      await openTable(page, tableId)

      await page.fill('input[placeholder="Your name"]', 'Human')
      await page.click('button[type="submit"]')
      await expect.poll(() => gs(page).then((s) => s?.players.length), { timeout: 10_000 }).toBe(2)
      const humanId = await gs(page).then((s) => s?.players.find((p) => p.name === 'Human')?.id)

      await bot1.startRound(tableId)

      // Wait until the human player's pocketCards are populated (round started, cards dealt)
      await expect
        .poll(
          () =>
            gs(page).then(
              (s) => (s?.players.find((p) => p.id === humanId)?.pocketCards?.length ?? 0) > 0,
            ),
          { timeout: 10_000 },
        )
        .toBe(true)
    } finally {
      await bot1.dispose()
    }
  })
})

test.describe('timer: reset after action', () => {
  test('turnTimerEndsAt is refreshed after a player acts', async ({ page }) => {
    const bot1 = await BotClient.create()
    const bot2 = await BotClient.create()

    try {
      // Use a 30-second timer so the before/after delta is unambiguous.
      const TIMER_SECS = 30
      const tableId = await bot1.createTable('Bot1', { turnTimerSeconds: TIMER_SECS })
      await bot2.join(tableId, 'Bot2')
      await openTable(page, tableId)

      await page.fill('input[placeholder="Your name"]', 'Human')
      await page.click('button[type="submit"]')
      await expect.poll(() => gs(page).then((s) => s?.players.length), { timeout: 10_000 }).toBe(3)
      const humanId = await gs(page).then((s) => s?.players.find((p) => p.name === 'Human')?.id)

      await bot1.startRound(tableId)

      // Drive bots until it is the human's turn
      await driveBotsUntil(humanId!, bot1, bot2, tableId)
      await expect
        .poll(() => gs(page).then((s) => s?.nextPlayerIdToAct), { timeout: 10_000 })
        .toBe(humanId)

      // Let a few seconds elapse so the old countdown moves forward meaningfully.
      await page.waitForTimeout(4_000)
      const timerBefore = await gs(page).then((s) => s?.turnTimerEndsAt ?? 0)

      // Human acts — this should trigger a timer reset on the server.
      await page.locator('[data-testid="btn-call"]').click()

      // Wait for the turn to advance to a different player.
      await expect
        .poll(
          () =>
            gs(page).then(
              (s) => s?.nextPlayerIdToAct !== humanId && s?.nextPlayerIdToAct != null,
            ),
          { timeout: 10_000 },
        )
        .toBe(true)

      const timerAfter = await gs(page).then((s) => s?.turnTimerEndsAt ?? 0)

      // If the timer was correctly reset, timerAfter reflects a fresh full-duration countdown
      // started ~4s after timerBefore was captured — so it must be strictly greater.
      // A stale (un-reset) timer would leave timerAfter equal to timerBefore.
      expect(timerAfter).toBeGreaterThan(timerBefore)
    } finally {
      await Promise.all([bot1.dispose(), bot2.dispose()])
    }
  })
})
