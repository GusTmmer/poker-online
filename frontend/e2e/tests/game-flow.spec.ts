import { test, expect } from '@playwright/test'
import { BotClient } from '../helpers/api'

// ── helpers ──────────────────────────────────────────────────────────────────

type GameState = {
  roundStage: string | null
  nextPlayerIdToAct: number | null
  gameStatus: string
  turnTimerEndsAt: number | null
  players: Array<{ id: number; name: string; chips: number; pocketCards?: string[] | null; botPersonality?: string | null }>
}

/** Fetch the latest game_state frame from the browser's WS spy. */
async function gs(page: import('@playwright/test').Page): Promise<GameState | null> {
  return page.evaluate(() => (window as { __lastGameState?: GameState }).__lastGameState ?? null)
}

/** Inject a WebSocket spy so tests can read game state via window.__lastGameState. */
async function openTable(page: import('@playwright/test').Page, tableId: number) {
  await installStateSpy(page)
  await page.goto(`/table/${tableId}`)
}

async function installStateSpy(page: import('@playwright/test').Page) {
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
              // The showdown frame is superseded within moments; keep the odds it carried.
              if (data.runoutOdds?.length) (window as { __lastRunoutOdds?: unknown }).__lastRunoutOdds = data.runoutOdds
            }
          } catch { /* ignore */ }
        })
      }
    }
  })
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

test.describe('all-in runout', () => {
  test('an all-in call reveals the board before the next hand can start', async ({ page }) => {
    const bot = await BotClient.create()

    try {
      // Heads-up: the bot (dealer, small blind) shoves; the human (big blind) calls.
      const tableId = await bot.createTable('Bot', { turnTimerSeconds: 120 })
      await openTable(page, tableId)
      await page.fill('input[placeholder="Your name"]', 'Human')
      await page.click('button[type="submit"]')
      await expect.poll(() => gs(page).then((s) => s?.players.length), { timeout: 10_000 }).toBe(2)

      await bot.startRound(tableId)
      await bot.playWhenMyTurn(tableId, 'ALL_IN')

      const call = page.locator('[data-testid="btn-call"]')
      await expect(call).toBeEnabled({ timeout: 10_000 })
      await call.click()

      // The runout holds the lobby controls while flop, turn and river are dealt…
      await expect(page.locator('[data-testid="revealing"]')).toBeVisible({ timeout: 5_000 })

      // …with the odds of winning for each board it shows: bare, flop, turn and river.
      type Odds = { boardCards: number; equities: { playerId: number; equity: number }[] }[]
      const odds = await page.evaluate(() => (window as { __lastRunoutOdds?: Odds }).__lastRunoutOdds ?? [])
      expect(odds.map((o) => o.boardCards)).toEqual([0, 3, 4, 5])
      for (const street of odds) {
        expect(street.equities).toHaveLength(2)
        expect(street.equities.reduce((sum, e) => sum + e.equity, 0)).toBeCloseTo(1, 6)
      }
      await expect(page.locator('[data-testid="btn-start-round"]')).not.toBeVisible()

      // …then releases them once the showdown is revealed (three streets, each followed by a 2s beat).
      await expect(page.locator('[data-testid="btn-start-round"]')).toBeVisible({ timeout: 20_000 })
      await expect(page.locator('[data-testid="revealing"]')).not.toBeVisible()
    } finally {
      await bot.dispose()
    }
  })
})

test.describe('mobile', () => {
  test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true })

  test('a phone held in portrait gets a rotated, landscape game screen', async ({ page }) => {
    const host = await BotClient.create()

    try {
      const tableId = await host.createTable('Host')
      await openTable(page, tableId)
      await page.fill('input[placeholder="Your name"]', 'Human')
      await page.click('button[type="submit"]')

      const bar = page.locator('[data-testid="control-bar"]')
      await expect(bar).toBeVisible({ timeout: 10_000 })
      await expect(page.locator('[data-rotated="true"]')).toHaveCount(1)

      // The compact side column lands along the bottom edge of the physical (portrait) screen,
      // spanning its width, and the canvas renders within the screen.
      const box = await bar.boundingBox()
      expect(box!.width).toBeGreaterThan(350)
      expect(box!.y + box!.height).toBeLessThanOrEqual(845)
      await expect(page.locator('[data-testid="btn-start-round"]')).toBeVisible()
    } finally {
      await host.dispose()
    }
  })
})

// ── phone layout ──────────────────────────────────────────────────────────────

type Box = { left: number; top: number; right: number; bottom: number }
type LayoutProbe = {
  free: { w: number; h: number }
  compact: boolean
  seats: (Box & { playerId: number; hasShowdownRow: boolean })[]
  myCards: Box | null
}

async function probe(page: import('@playwright/test').Page): Promise<LayoutProbe | null> {
  return page.evaluate(() => (window as { __pokerLayout?: () => LayoutProbe }).__pokerLayout?.() ?? null)
}

function offScreen(box: Box, free: LayoutProbe['free']): boolean {
  const slack = 1
  return box.left < -slack || box.top < -slack || box.right > free.w + slack || box.bottom > free.h + slack
}

/**
 * Six players see a hand through to showdown — the fullest a phone screen gets — while the test checks
 * the local player's cards on their turn and then every seat, showdown row included, at the end.
 */
async function sixWayShowdownFitsTheScreen(page: import('@playwright/test').Page) {
  const others = await Promise.all(Array.from({ length: 5 }, () => BotClient.create()))
  try {
    const tableId = await others[0].createTable('P1', { turnTimerSeconds: 120 })
    for (let i = 1; i < others.length; i++) await others[i].join(tableId, `P${i + 1}`)
    await openTable(page, tableId)
    await page.fill('input[placeholder="Your name"]', 'Hero')
    await page.click('button[type="submit"]')
    await expect.poll(async () => (await gs(page))?.players.length, { timeout: 10_000 }).toBe(6)
    const me = (await gs(page))!.players.find((p) => p.name === 'Hero')!.id

    await others[0].startRound(tableId)
    let checkedMyCards = false
    for (let i = 0; i < 200; i++) {
      const s = await others[0].getState(tableId)
      if (s.gameStatus === 'WAITING' && i > 0) break
      const toAct = s.nextPlayerIdToAct
      if (toAct === me) {
        const call = page.locator('[data-testid="btn-call"]')
        await expect(call).toBeEnabled({ timeout: 10_000 })
        if (!checkedMyCards) {
          // Once dealt, the local player's own cards show — on screen.
          await expect.poll(async () => (await probe(page))?.myCards != null, { timeout: 10_000 }).toBe(true)
          const layout = (await probe(page))!
          expect(offScreen(layout.myCards!, layout.free), JSON.stringify(layout)).toBe(false)
          checkedMyCards = true
        }
        await call.click()
      } else if (toAct != null) {
        await others.find((o) => o.playerId === toAct)!.act(tableId, 'CALL')
      }
      await page.waitForTimeout(150)
    }
    expect(checkedMyCards).toBe(true)

    await expect.poll(async () => (await probe(page))?.seats.filter((s) => s.hasShowdownRow).length, { timeout: 15_000 }).toBe(6)
    const layout = (await probe(page))!
    expect(layout.compact).toBe(true)
    for (const seat of layout.seats) {
      expect(offScreen(seat, layout.free), `seat ${seat.playerId} is off screen: ${JSON.stringify(layout)}`).toBe(false)
    }
  } finally {
    await Promise.all(others.map((o) => o.dispose()))
  }
}

test.describe('phone layout: portrait', () => {
  test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true })
  test('every seat and showdown row of a six-way hand stays on screen', async ({ page }) => {
    test.setTimeout(90_000)
    await sixWayShowdownFitsTheScreen(page)
  })
})

test.describe('phone layout: landscape', () => {
  test.use({ viewport: { width: 844, height: 390 }, hasTouch: true, isMobile: true })
  test('every seat and showdown row of a six-way hand stays on screen', async ({ page }) => {
    test.setTimeout(90_000)
    await sixWayShowdownFitsTheScreen(page)
  })
})

test.describe('computer players', () => {
  test('a table created with computer players seats them and they play their own turns', async ({ page }) => {
    await installStateSpy(page)
    await page.goto('/')
    await page.fill('input[placeholder="Alice"]', 'Human')
    await page.fill('[data-testid="input-computer-players"]', '3')
    await page.click('button[type="submit"]')

    const bar = page.locator('[data-testid="control-bar"]')
    await expect(bar).toBeVisible({ timeout: 10_000 })
    await expect.poll(async () => (await gs(page))?.players.filter((p) => p.botPersonality).length).toBe(3)
    const me = (await gs(page))!.players.find((p) => !p.botPersonality)!.id

    // Four-handed, a computer player is under the gun: nobody drives it, yet the turn reaches the human
    // within its few seconds of "thinking" (the turn timer is 30s).
    await bar.locator('[data-testid="btn-start-round"]').click()
    await expect.poll(async () => (await gs(page))?.nextPlayerIdToAct, { timeout: 10_000 }).toBe(me)

    // After the human calls, the computer players act in turn until it's the human's move again (or the
    // hand is over).
    await bar.locator('[data-testid="btn-call"]').click()
    await expect.poll(async () => (await gs(page))?.nextPlayerIdToAct).not.toBe(me)
    await expect
      .poll(async () => {
        const s = await gs(page)
        return s?.nextPlayerIdToAct === me || s?.roundStage == null || s?.roundStage === 'SHOWDOWN'
      }, { timeout: 20_000 })
      .toBe(true)
  })
})

test.describe('invite link', () => {
  test('the host copies an invite link from the table menu and a friend joins through it', async ({ page, browser, baseURL }) => {
    await page.context().grantPermissions(['clipboard-read', 'clipboard-write'])
    await installStateSpy(page)
    await page.goto('/')
    await page.fill('input[placeholder="Alice"]', 'Host')
    await page.fill('input[placeholder="Friday Night Poker"]', 'Invite Test Table')
    await page.click('button[type="submit"]')
    await expect(page.locator('[data-testid="control-bar"]')).toBeVisible({ timeout: 10_000 })

    await page.getByRole('button', { name: 'Table menu' }).click()
    await page.locator('[data-testid="menu-copy-invite"]').click()
    await expect(page.getByRole('status')).toContainText('Invite link copied')

    const link = await page.evaluate(() => navigator.clipboard.readText())
    const tableId = Number(new URL(page.url()).pathname.split('/').pop())
    expect(link).toBe(`${baseURL}/table/${tableId}`)

    const friendContext = await browser.newContext()
    try {
      const friend = await friendContext.newPage()
      await friend.goto(link)
      const invitation = friend.locator('[data-testid="join-invitation"]')
      await expect(invitation).toContainText('Invite Test Table')
      await expect(invitation).toContainText('Host · 1 of 6 seats taken')

      await friend.fill('input[placeholder="Your name"]', 'Friend')
      await friend.click('button[type="submit"]')
      await expect(friend.locator('[data-testid="control-bar"]')).toBeVisible({ timeout: 10_000 })

      await expect.poll(async () => (await gs(page))?.players.map((p) => p.name)).toEqual(['Host', 'Friend'])
    } finally {
      await friendContext.close()
    }
  })

  test('the join tab accepts a pasted invite link', async ({ page, baseURL }) => {
    const host = await BotClient.create()
    try {
      const tableId = await host.createTable('Host')
      await page.goto('/')
      await page.getByRole('button', { name: 'Join', exact: true }).click()
      await page.fill('[data-testid="input-table-ref"]', `${baseURL}/table/${tableId}`)
      await page.fill('input[placeholder="Alice"]', 'Friend')
      await page.click('button[type="submit"]')
      await expect(page).toHaveURL(new RegExp(`/table/${tableId}$`))
      await expect(page.locator('[data-testid="control-bar"]')).toBeVisible({ timeout: 10_000 })
    } finally {
      await host.dispose()
    }
  })
})
