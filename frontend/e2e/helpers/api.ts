import { type APIRequestContext, request } from '@playwright/test'

const BACKEND = `http://localhost:${process.env.BACKEND_PORT ?? '8080'}`

export interface TableHandle {
  tableId: number
  playerId: number
}

/**
 * HTTP client for a single player session. Each instance keeps its own cookie jar
 * so multiple bots can hold independent JWT sessions against the same table.
 */
export class BotClient {
  private ctx!: APIRequestContext
  private _playerId = -1

  get playerId() {
    return this._playerId
  }

  static async create(): Promise<BotClient> {
    const bot = new BotClient()
    bot.ctx = await request.newContext({ baseURL: BACKEND })
    return bot
  }

  async createTable(
    name: string,
    opts: { startingChips?: number; turnTimerSeconds?: number; maxPlayers?: number } = {},
  ): Promise<number> {
    const res = await this.ctx.post('/api/tables', {
      data: {
        playerName: name,
        startingChips: opts.startingChips ?? 1000,
        turnTimerSeconds: opts.turnTimerSeconds ?? 120,
        maxPlayers: opts.maxPlayers ?? 6,
      },
    })
    const body = await res.json()
    this._playerId = body.playerId
    return body.tableId as number
  }

  async join(tableId: number, name: string): Promise<number> {
    const res = await this.ctx.post(`/api/tables/${tableId}/players`, {
      data: { playerName: name },
    })
    const body = await res.json()
    this._playerId = body.playerId
    return body.playerId as number
  }

  async startRound(tableId: number) {
    await this.ctx.post(`/api/tables/${tableId}/start-round`)
  }

  async readyUp(tableId: number) {
    await this.ctx.post(`/api/tables/${tableId}/ready`)
  }

  async act(tableId: number, type: 'FOLD' | 'CALL' | 'RAISE' | 'ALL_IN', value?: number) {
    await this.ctx.post(`/api/tables/${tableId}/action`, {
      data: value !== undefined ? { type, value } : { type },
    })
  }

  async getState(tableId: number) {
    const res = await this.ctx.get(`/api/tables/${tableId}`)
    return res.json() as Promise<{
      gameStatus: string
      nextPlayerIdToAct: number | null
      players: Array<{ id: number; chips: number; status: string }>
    }>
  }

  /** Repeatedly calls until it is this bot's turn, then plays `action`. */
  async playWhenMyTurn(
    tableId: number,
    action: 'FOLD' | 'CALL' | 'ALL_IN' = 'CALL',
    maxAttempts = 60,
  ) {
    for (let i = 0; i < maxAttempts; i++) {
      const state = await this.getState(tableId)
      if (state.nextPlayerIdToAct === this._playerId) {
        await this.act(tableId, action)
        return
      }
      await new Promise((r) => setTimeout(r, 500))
    }
    throw new Error(`Bot ${this._playerId} never got a turn within ${maxAttempts} polls`)
  }

  async dispose() {
    await this.ctx.dispose()
  }
}

/**
 * Polls until the game reaches the given round stage (or WAITING after a round).
 */
export async function pollUntilStage(
  bot: BotClient,
  tableId: number,
  stage: string,
  timeoutMs = 15_000,
) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const state = await bot.getState(tableId)
    if ((state as { roundStage?: string }).roundStage === stage || state.gameStatus === stage) return
    await new Promise((r) => setTimeout(r, 400))
  }
  throw new Error(`Timed out waiting for stage "${stage}"`)
}
