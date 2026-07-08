# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Build all modules
./gradlew build

# Run all tests (Kotlin)
./gradlew test

# Run tests for a specific module
./gradlew :poker-engine:test
./gradlew :server-gcp:test

# Run a single test class
./gradlew :poker-engine:test --tests "com.gustmmer.poker.round.PokerRoundRaisesTest"

# Run the server locally
./gradlew :server-gcp:run

# Build Docker image for deployment
docker build -t poker-server .

# ── Frontend ──────────────────────────────────────────────────────────────────
cd frontend

# Start the Vite dev server (proxies /api and /ws to localhost:8080;
# set BACKEND_PORT=<port> when the backend runs elsewhere — the e2e helpers honor it too)
npm run dev

# Build for production
npm run build

# Run E2E tests (requires both backend and Vite dev server to be running)
npm run test:e2e

# Regenerate visual baseline snapshots after intentional UI changes
npm run test:e2e:update

# Open Playwright's interactive test UI
npm run test:e2e:ui
```

## Architecture

The project is a Gradle multi-module Kotlin (JVM 21) monorepo with two backend modules and a React frontend:

### `poker-engine` — Core game logic (no server dependencies)

- **`PokerTable`** — Top-level entry point. Manages players, blinds, ordering, and delegates to `PokerRound` for per-round logic. Calls `PokerTablePersistence.saveState()` on every mutation.
- **`PokerRound`** — Runs a single hand: takes blinds, deals cards, advances through betting stages (BET_BLINDS → BET_FLOP → BET_TURN → BET_RIVER → SHOWDOWN), resolves side pots, and evaluates winners via `TexasHoldEmHandEvaluator`.
- **`BettingRoundCoordinator`** — Processes `PlayerCommand` (Fold/Call/Raise/AllIn) and determines when a betting round is complete. `moveToNextPlayerWhoCanBet()` always skips players with 0 chips (`canBet() = isActive() && chips > 0`), so `bettingPlayer()` never returns a 0-chip player during an active betting round.
- **`PokerRoundStage`** — Enum encoding the stage machine; `isBettingRound()`, `isLastBettingRound()`, `next()` drive transitions.
- **`PlayerOrdering`** — Tracks dealer/small-blind/big-blind/betting positions across hands.
- **`PokerTablePersistence`** — Interface with `loadState(id)` / `saveState(state)`. Implementations: `MemoryBasedPokerTablePersistence` (tests), `FileBasedPokerTablePersistence` (local), `FirestorePokerTablePersistence` (GCP production).
- **Wireable pattern** — Domain objects that contain mutable references (e.g., `Player`) cannot be serialized directly. They implement `Wireable<T>` and expose a `toWire()` method that converts to a `@Serializable` data class storing only IDs. `restore(wire, playerMap)` companions reconstruct from wire form using a `Map<Int, Player>`.

### `server-gcp` — Ktor-based HTTP server (depends on `poker-engine`)

- **`Application.kt`** — Entry point. Wires `FirestorePokerTablePersistence`, a `TableUpdateBus`, a `TaskScheduler`, `JwtService`, `TableConnectionManager`, and `TurnTimerManager`, then registers routes.
- **`TableRoutes`** — REST API under `/api/tables/{tableId}`. Each route mutates a `PokerTable` via the `withTable` commit boundary; the resulting state change fans out over WebSocket through the bus (no explicit broadcast in the handler).
- **`WebSocketRoutes`** — WebSocket endpoint for real-time game state push. Players identify via JWT cookie; connections are tracked in `TableConnectionManager`.
- **`TableUpdateBus`** — Cross-instance fan-out. `FirestoreTableUpdateBus` (prod) uses Firestore snapshot listeners; `InMemoryTableUpdateBus` + `NotifyingPersistence` (dev/tests) publish on commit. `TableConnectionManager` subscribes per table and re-broadcasts committed changes to its local sockets.
- **`TableConnectionManager`** — Per-instance map of `tableId → playerId → WebSocketSession` + one bus subscription per table. Builds per-player `GameStateUpdate` (hides other players' pocket cards unless showdown; carries `state.activeVotes`).
- **`TurnTimerManager` / `TaskScheduler`** — Turn (and vote) timeouts run on a durable `TaskScheduler`: `CloudTasksScheduler` (prod) enqueues a Cloud Task that calls `/internal/*` back; `InMemoryTaskScheduler` (dev/tests) uses a coroutine. `onTimerFired`/`onVoteExpired` are idempotent (turn token / vote-id check) to absorb at-least-once delivery.
- **Voting** — No `VoteManager`; vote tallies live in `PokerTableState.activeVotes` and flow through `VotingService` + `withTable`, so they fan out via the bus. A passed cast stages its consequence (pause/kick/…) in the same commit.
- **`JwtService`** — Issues and validates JWT cookies scoped to `(tableId, playerId)`.

### `frontend` — React + Pixi.js SPA (`frontend/`)

Single-page app built with Vite, React 19, TypeScript, and Emotion (CSS-in-JS). Communicates with the backend via REST (`/api/...`) and a persistent WebSocket (`/ws/table/{tableId}`). Vite proxies both to `localhost:8080` in development.

**Key files:**
- **`src/api/types.ts`** — Shared TypeScript types for all REST and WebSocket payloads (`GameStateUpdate`, `PlayerView`, `ActionRequest`, etc.).
- **`src/api/client.ts`** — Typed `fetch` wrappers for every REST endpoint (no cookie management needed — `credentials: 'include'` passes the JWT cookie automatically).
- **`src/ws/useGameSocket.ts`** — React hook that manages the WebSocket connection, reconnects on drop, and normalizes incoming `game_state` frames.
- **`src/context/SessionContext.tsx`** — Holds `tableId`, `myPlayerId`, and `tableInfo` after the player joins.
- **`src/pages/TablePage.tsx`** — Route entry point; shows `JoinForm` if no session, otherwise `GameScreen`.
- **`src/pages/GameScreen.tsx`** — Composes `PixiPokerTable`, `ControlBar`, `VotePopupLayer`, and `Toasts`.

**`PixiTable/` — Pixi.js v8 canvas renderer:**
- **`PixiPokerTable.tsx`** — Main Pixi application. Maintains a `SceneState` and updates it on every `GameStateUpdate`. Handles dealing animations, chip/pot rendering (5 randomized `POT_VARIANTS` that grow consistently), card dealing/crossfade, and the per-frame tick loop.
- **`drawSeat.ts`** — Builds and updates the per-player seat: avatar sprite, gold turn halo (`turnHaloGfx`), green win halo (`winHaloGfx`), name/chips labels, badges (D/SB/BB), mini pocket cards. The two halo `Graphics` objects are drawn once at build time and only toggled via `visible` — never cleared and redrawn — to avoid the Pixi v8 `BlurFilter` stale-texture artefact.

**`ControlBar/` — In-round action bar:**
- Shows `Ready Up` / `Start Round` when no round is active.
- Shows `Fold` / `Call` (or `Check`) / `Raise` slider when a round is in progress **and** the local player has chips > 0. Players with 0 chips (all-in or eliminated mid-round) see a minimal bar with no action buttons.
- Action buttons carry `data-testid` attributes (`btn-fold`, `btn-call`, `btn-ready`, `btn-start-round`) for E2E test targeting.

### Key data flows

1. **Player action**: `POST /api/tables/{id}/action` → `withTable` commits `processPlayerCommand()` (one versioned write) → the committed change fans out over WebSocket via the `TableUpdateBus` subscription on every instance → `TurnTimerManager` schedules the next turn's timeout on the `TaskScheduler`.
2. **Round start**: `POST /api/tables/{id}/start-round` → `advancePlayerOrdering()` → `newPokerRound()` → broadcast → start timer.
3. **State persistence (single write path)**: `PokerTable` mutators only *stage* changes in memory (setting a dirty flag); they never persist on their own. The sole commit boundary is `withTable(tableId) { table -> … }` (in `service/ServiceResult.kt`): it restores the table, runs the block, and—if the block returns `Ok` and something was staged—calls `PokerTable.commit()` once, which bumps `version` and writes via `PokerTablePersistence.saveStateIfVersionMatches()` (a Firestore CAS transaction). A version mismatch throws `ConcurrentModificationException`, which `withTable` catches to restore-and-retry. So one `withTable` call == one consistent state transition == one versioned write. Reads use the read-only `loadTable(tableId)`. Side effects that must observe the committed state—the WebSocket broadcast and turn-timer transitions—run *after* `withTable` returns, never inside the block (a timer op inside would nest a second commit and self-conflict). `PokerTablePersistence` exposes no unconditional write; tests seed arbitrary state through the impl-only `seed()` seam on the in-memory/file persistence.
4. **Frontend rendering**: `useGameSocket` receives a `game_state` WebSocket frame → React re-renders `GameScreen` → `PixiPokerTable` diffing updates the Pixi scene (seats, pot, community cards, animations).

## E2E Tests (`frontend/e2e/`)

Built on `@playwright/test`. Requires the backend (`./gradlew :server-gcp:run`) and Vite dev server (`npm run dev`) to both be running.

```
e2e/
  global-setup.ts          # Verifies backend is reachable before any test runs
  helpers/
    api.ts                 # BotClient: typed HTTP client with its own cookie jar for bot players
  tests/
    game-flow.spec.ts      # Behavioral assertions (lobby bar, action button state, dealing, 0-chip guards)
    visual.spec.ts         # Screenshot regression tests at stable game moments
  snapshots/               # Baseline PNGs committed to git; updated with --update-snapshots
  tsconfig.json            # Separate tsconfig so @playwright/test types resolve correctly
```

**WS spy pattern**: Tests inject a `WebSocket` subclass via `page.addInitScript()` before the app loads. Every incoming `game_state` frame is written to `window.__lastGameState`. Tests read it back in Node.js context via `page.evaluate()` + `expect.poll()` — no DOM text scraping, no fragile CSS selectors for state.

**Bot players**: Each `BotClient` instance holds its own `APIRequestContext` (independent JWT cookie jar). Bots make direct HTTP calls to the backend at `localhost:8080`, bypassing Vite. Tests drive bots explicitly rather than relying on the turn timer, keeping total test runtime under 30 seconds.

**Visual baselines**: Stored under `e2e/snapshots/` with platform suffix (e.g. `my-turn-chromium-darwin.png`). `--use-gl=swiftshader` (software WebGL) ensures pixel-identical canvas output across runs on the same OS. `maxDiffPixelRatio: 0.02` tolerates minor anti-aliasing variation. Regenerate with `npm run test:e2e:update` after intentional UI changes, then commit the updated PNGs.

## Environment Variables (server-gcp)

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | HTTP listen port |
| `JWT_SECRET` | `dev-secret-change-in-production` | JWT signing key |
| `GCP_PROJECT_ID` | `poker-online-dev` | Firestore project |
| `ALLOWED_ORIGIN` | `*` | CORS origin. `*` is dev-only (credentialed cross-origin requests fail); set a concrete origin in production to enable the JWT cookie cross-origin. With the SPA served same-origin (`STATIC_DIR`), CORS is moot. |
| `STATIC_DIR` | _(unset)_ | Directory of built frontend assets to serve at `/` (same-origin). Unset in dev (Vite proxy). The Docker image sets it to the bundled SPA. |
| `INTERNAL_TOKEN` | `dev-internal-token` | Shared secret guarding the `/internal/*` endpoints Cloud Tasks calls back. Secret Manager in prod. |
| `SECURE_COOKIES` | `false` | Marks session cookies `Secure` (HTTPS-only). The Docker image sets it `true` (Cloud Run is HTTPS); keep it false for local `http://localhost` dev so the cookie is still sent. |
| `SERVICE_URL` | _(required in prod)_ | This service's own public URL — the target Cloud Tasks POSTs timer/vote callbacks to. |
| `CLOUD_TASKS_LOCATION` / `CLOUD_TASKS_QUEUE` | `us-central1` / `poker-timers` | Cloud Tasks queue for durable timers. |
| `VOTE_TIMEOUT_SECONDS` | `60` | How long a vote stays open before its Cloud Task auto-closes it. |
| `RATE_LIMIT_MUTATIONS` / `RATE_LIMIT_REFILL_SECONDS` | `30` / `60` | Per-IP cap on table create + join. |
| `RATE_LIMIT_READS` / `RATE_LIMIT_READ_REFILL_SECONDS` | `60` / `60` | Per-IP cap on read endpoints that fan out Firestore reads (`GET /api/my-tables`). Separate bucket so listing never drains the create/join budget. |

## Deployment

**Stateless — scales horizontally, but kept cost-bounded.** All cross-instance coordination lives off-instance (Path B, see `docs/path-b-plan.md` + `docs/architecture-gcp.md`): WebSocket fan-out via **Firestore snapshot listeners** (`TableUpdateBus`), turn + vote timers via **Cloud Tasks** (`TaskScheduler` → `/internal/*`), and vote state inside `PokerTableState.activeVotes`. So any instance can serve any player. The Cloud Run manifest pins `maxScale=3` purely as a **cost ceiling** (not a correctness one) and `minScale=0` for scale-to-zero. `MultiInstanceTest` proves a commit on one instance fans out to a socket on another. The only per-instance state left is each instance's own live WebSocket sockets + their bus subscriptions (correct by design), and ephemeral toasts (`broadcastMessage`) are instance-local (cosmetic; the underlying state still fans out via the bus).

**Firestore TTL.** Documents carry an `expiresAt` Firestore `Timestamp` field (12h out); the `tables` collection needs a matching native TTL policy configured for cleanup to actually happen.
