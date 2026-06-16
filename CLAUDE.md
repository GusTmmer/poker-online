# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Build all modules
./gradlew build

# Run all tests
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
```

## Architecture

The project is a Gradle multi-module Kotlin (JVM 21) monorepo with two modules:

### `poker-engine` — Core game logic (no server dependencies)

- **`PokerTable`** — Top-level entry point. Manages players, blinds, ordering, and delegates to `PokerRound` for per-round logic. Calls `PokerTablePersistence.saveState()` on every mutation.
- **`PokerRound`** — Runs a single hand: takes blinds, deals cards, advances through betting stages (BET_BLINDS → BET_FLOP → BET_TURN → BET_RIVER → SHOWDOWN), resolves side pots, and evaluates winners via `TexasHoldEmHandEvaluator`.
- **`BettingRoundCoordinator`** — Processes `PlayerCommand` (Fold/Call/Raise/AllIn) and determines when a betting round is complete.
- **`PokerRoundStage`** — Enum encoding the stage machine; `isBettingRound()`, `isLastBettingRound()`, `next()` drive transitions.
- **`PlayerOrdering`** — Tracks dealer/small-blind/big-blind/betting positions across hands.
- **`PokerTablePersistence`** — Interface with `loadState(id)` / `saveState(state)`. Implementations: `MemoryBasedPokerTablePersistence` (tests), `FileBasedPokerTablePersistence` (local), `FirestorePokerTablePersistence` (GCP production).
- **Wireable pattern** — Domain objects that contain mutable references (e.g., `Player`) cannot be serialized directly. They implement `Wireable<T>` and expose a `toWire()` method that converts to a `@Serializable` data class storing only IDs. `restore(wire, playerMap)` companions reconstruct from wire form using a `Map<Int, Player>`.

### `server-gcp` — Ktor-based HTTP server (depends on `poker-engine`)

- **`Application.kt`** — Entry point. Wires `FirestorePokerTablePersistence`, `JwtService`, `TableConnectionManager`, `VoteManager`, and `TurnTimerManager`, then registers routes.
- **`TableRoutes`** — REST API under `/api/tables/{tableId}`. Each route restores a `PokerTable` from Firestore, mutates it (which auto-saves), then broadcasts via WebSocket.
- **`WebSocketRoutes`** — WebSocket endpoint for real-time game state push. Players identify via JWT cookie; connections are tracked in `TableConnectionManager`.
- **`TableConnectionManager`** — In-memory map of `tableId → playerId → WebSocketSession`. Builds per-player `GameStateUpdate` (hides other players' pocket cards unless showdown).
- **`TurnTimerManager`** — Coroutine-based countdown per table; auto-plays idle/offline players on expiry.
- **`VoteManager`** — Vote accumulation for pause/unpause/kick actions; requires an absolute majority of online players.
- **`JwtService`** — Issues and validates JWT cookies scoped to `(tableId, playerId)`.

### Key data flows

1. **Player action**: `POST /api/tables/{id}/action` → restore `PokerTable` from Firestore → `processPlayerCommand()` (saves state) → `broadcastGameState()` → restart `TurnTimerManager`.
2. **Round start**: `POST /api/tables/{id}/start-round` → `advancePlayerOrdering()` → `newPokerRound()` → broadcast → start timer.
3. **State persistence**: Every `PokerTable` method calls `saveState()` which increments `version` and calls `PokerTablePersistence.saveState()`. There is no explicit transaction boundary.

## Environment Variables (server-gcp)

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | HTTP listen port |
| `JWT_SECRET` | `dev-secret-change-in-production` | JWT signing key |
| `GCP_PROJECT_ID` | `poker-online-dev` | Firestore project |