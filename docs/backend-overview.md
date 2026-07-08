# Backend Overview

A guided tour of the backend: the architectural decisions behind it, how the code
is structured, the patterns that recur, and the web-server machinery that ties it
together. The goal is to paint the whole picture of what happens server-side when
someone plays a hand of poker.

> Companion docs: `docs/architecture-gcp.md` (the GCP topology) and
> `docs/path-b-plan.md` (the stateless-design migration this code is the result of).

---

## 1. The big picture

The backend is a Gradle multi-module Kotlin (JVM 21) project split into two modules:

| Module | Responsibility | Knows about… |
|---|---|---|
| **`poker-engine`** | Pure game logic — cards, betting, hand evaluation, persistence *contracts* | Nothing server-related. No Ktor, no Firestore, no coroutines in the core. |
| **`server-gcp`** | Ktor HTTP/WebSocket server, GCP integrations, session/auth, timers, voting | Depends on `poker-engine`. |

The hard line between them is the central architectural decision. The engine is a
deterministic state machine you could drive from a unit test, a CLI, or a different
transport entirely. The server is everything needed to expose that engine to many
concurrent players over the web, durably and across horizontally-scaled instances.

The single most important property of the whole system: **it is stateless across
instances.** Any server instance can serve any player for any table, because all
shared state lives off-instance (Firestore for game state, Cloud Tasks for timers).
This is what lets it run on Cloud Run with scale-to-zero and `maxScale > 1`. Section
6 explains how that is achieved without the code being littered with coordination
logic.

---

## 2. `poker-engine` — the game logic

### Entry points and the state machine

- **`PokerTable`** is the top-level aggregate. It owns the list of players, blinds,
  ordering, game status, and the current round. Every game operation is a method on
  it (`newPokerRound`, `processPlayerCommand`, `playerJoin`, `pause`, `openVote`, …).
- **`PokerTableState`** is the immutable-ish data snapshot `PokerTable` wraps. The
  table mutates by `copy()`-ing this state.
- **`PokerRound`** runs a single hand: takes blinds, deals, advances through betting
  stages, resolves side pots, evaluates winners.
- **`PokerRoundStage`** is the per-hand state machine enum:
  `BET_BLINDS → BET_FLOP → BET_TURN → BET_RIVER → SHOWDOWN`. Its `isBettingRound()`,
  `isLastBettingRound()`, and `next()` drive transitions.
- **`BettingRoundCoordinator`** processes a single `PlayerCommand` (Fold / Call /
  Raise / AllIn), validates it's the right player's turn, mutates the pot, and decides
  whether the betting round is complete (everyone matched the last raiser, or only one
  player remains active). `moveToNextPlayerWhoCanBet()` always skips 0-chip players, so
  `bettingPlayer()` never points at someone who can't act.
- **`PlayerOrdering`** tracks dealer / small-blind / big-blind / current-better
  positions and rotates them between hands (`forNextHand`).
- **`TexasHoldEmHandEvaluator`** + `hand/rankings/*` evaluate the best 5-card hand.

### The Wireable pattern (serialization)

Domain objects hold *mutable references* to each other — e.g. several structures all
point at the same `Player` instance, and mutating chips in one place must be visible
everywhere. You cannot serialize that graph directly without either duplicating
players or losing identity.

The solution is the **`Wireable<T>`** interface:

```kotlin
interface Wireable<T> { fun toWire(): T }
```

Every domain object that participates in persistence has a sibling `@Serializable`
`Wireable…` data class that stores only **IDs** instead of object references
(`BettingRoundState.lastRaiser: Player?` becomes `WireableBettingRoundState.lastRaiser: Int?`).
`toWire()` flattens the live graph to IDs; a companion `restore(wire, playerMap)`
rebuilds it, re-linking every reference through a single `Map<Int, Player>` so object
identity is preserved on the way back in. `PokerTableState.restore()` is the root of
this: it restores the players once, builds the `playerMap`, then threads it through
ordering, round state, and pots.

`restore()` also doubles as a **schema-migration seam** — e.g. it infers `gameStatus`
for documents written before that field existed.

### Persistence contract

`poker-engine` defines the contract but not the storage:

```kotlin
interface PokerTablePersistence {
    fun loadState(id: Int): PokerTableState?
    fun saveStateIfVersionMatches(state: PokerTableState): Boolean   // CAS write
}
```

There is **deliberately no unconditional write.** Every write is a
compare-and-swap on a monotonic `version` field. This is the foundation of the
optimistic-concurrency model in section 5. Implementations:

- `MemoryBasedPokerTablePersistence` — tests / local.
- `FileBasedPokerTablePersistence` — local persistence to disk.
- `FirestorePokerTablePersistence` — production (lives in `server-gcp`).

Tests that need to seed arbitrary state use an impl-only `seed()`/`json()` seam, never
the interface — so the production contract stays CAS-only.

---

## 3. `server-gcp` — the web server

Built on **Ktor 3.1** running on the **Netty** engine. `Application.kt` is the
composition root: `main()` wires the production dependencies and starts the server;
`configureServer()` installs plugins and registers routes. `LocalServer.kt` is the
parallel dev entry point with in-memory wiring.

### Wiring (dependency graph)

`main()` constructs the production object graph by hand (no DI framework):

```
FirestoreOptions → Firestore (one shared client)
   ├── FirestorePokerTablePersistence   (reads/writes table docs)
   └── FirestoreTableUpdateBus          (snapshot listeners for fan-out)
CloudTasksScheduler                     (durable timers → /internal/* callbacks)

configureServer(persistence, config, bus, scheduler):
   JwtService              ← config.jwtSecret
   TableConnectionManager  ← bus
   TurnTimerManager        ← persistence, scheduler
   GameService             ← persistence, timerManager
   VotingService           ← persistence, timerManager, connectionManager, scheduler
```

The same `configureServer()` is shared by production (`Application.main`) and dev
(`LocalServer.main`); only the four injected dependencies differ. That symmetry is
why a single in-process test can exercise the exact production fan-out path.

### Ktor plugins (`configurePlugins`)

| Plugin | Why it's there |
|---|---|
| `Resources` | Type-safe route definitions (see `ApiResources.kt`). |
| `XForwardedHeaders` | On Cloud Run the real client IP is in `X-Forwarded-For`; trust it so rate-limit keys are per-client, not per-load-balancer. |
| `RateLimit` | Per-IP token bucket on the two document-*creating* endpoints (table create + join) — the cheapest abuse vector. |
| `ContentNegotiation` (kotlinx JSON) | `ignoreUnknownKeys`, `isLenient`, `encodeDefaults`. |
| `WebSockets` | 15s ping / 30s timeout keepalive for the push channel. |
| `CORS` | Credentialed cross-origin **only** when a concrete `ALLOWED_ORIGIN` is set; the `*` default is dev-only (browsers reject `*` + credentials, and the JWT rides in a cookie). When the SPA is served same-origin via `STATIC_DIR`, CORS is moot. |

### Route groups

| File | Mounts | Purpose |
|---|---|---|
| `TableRoutes` | `/api/tables/**` | Lifecycle + in-round actions (create, join, action, start-round, ready, etc.). |
| `VotingRoutes` | `/api/tables/{id}/voting-sessions/**` | Open a vote, cast a vote. |
| `WebSocketRoutes` | `/ws/tables/{id}` | The real-time server→client push channel. |
| `InternalRoutes` | `/internal/**` | Callbacks invoked by Cloud Tasks (not players); shared-secret guarded. |
| `Application.configureStaticAndHealth` | `/healthz`, `/` | Liveness probe + optional same-origin SPA hosting. |

**Typed routes (`ApiResources.kt`).** Every path is declared once as a
`@Resource`-annotated class (e.g. `TableActionResource(val tableId: Int)`). Route
registration and any typed client reference the class, never a duplicated path string.

### Request shape

A REST handler is intentionally thin. It does exactly three things:

1. **Authenticate** — `call.extractSession(jwtService, tableId)`; bail with 401 if absent.
2. **Delegate** — call the matching `GameService` / `VotingService` method.
3. **Respond** — `call.respond(result)` where `result` is a `ServiceResult`.

All game logic and validation lives in the service layer, never in the route. Example:

```kotlin
post<TableActionResource> { resource ->
    val session = call.extractSession(jwtService, resource.tableId)
        ?: return@post call.respondUnauthorized()
    val request = call.receive<ActionRequest>()
    call.respond(gameService.applyAction(resource.tableId, session.playerId, request))
}
```

---

## 4. Sessions & auth

- **`JwtService`** issues an HMAC-256 JWT carrying `(tableId, playerId)`. On create-table
  and join, the token is set as an **HttpOnly cookie** named `poker_table_{tableId}`.
- Cookies are scoped per table, so one browser can hold independent sessions at several
  tables simultaneously.
- `extractSession()` reads the cookie, verifies the signature, and checks the token's
  `tableId` matches the route — returning a `PlayerSession` or null.
- The WebSocket handshake authenticates the same way (the cookie travels with the WS
  upgrade request); an unauthenticated socket is closed with a policy violation.

There is no server-side session store — the JWT *is* the session. That's another piece
of the stateless design: any instance can validate any request with just the signing
secret.

---

## 5. The single write path (the core server pattern)

This is the most important pattern in `server-gcp`. **Every state change goes through
exactly one boundary, produces exactly one versioned write, and that write is what
drives the broadcast.**

### `withTable` — the commit boundary

```kotlin
suspend fun <T> withTable(
    tableId: Int,
    persistence: PokerTablePersistence,
    block: suspend (PokerTable) -> ServiceResult<T>,
): ServiceResult<T>
```

`withTable` (in `service/ServiceResult.kt`):

1. **Restores** the table from persistence (`PokerTable.restore`).
2. Runs `block`, which validates and **stages** mutations on the table.
3. If `block` returns `Ok` *and* something was staged, calls `table.commit()` **once**.
4. On a `ConcurrentModificationException`, **restores fresh and retries** the whole
   block (up to `maxAttempts`, default 3), then returns 409 if still conflicting.

`PokerTable` mutators never persist on their own — they only flip an in-memory `dirty`
flag and `copy()` the state. `commit()` is the sole writer: it bumps `version` and
calls `saveStateIfVersionMatches()`, the Firestore CAS transaction. A version mismatch
throws, which is exactly what the retry loop catches.

Consequences of this design:

- **One `withTable` call == one consistent state transition == one versioned write.**
- A read-and-bail or a no-op flip stages nothing, so it writes nothing (no spurious
  version bumps, no spurious conflicts).
- Concurrency is handled in *one place*. Handlers don't think about locking.

Reads that don't mutate use the read-only `loadTable(tableId)` — explicitly *not*
`withTable`, making "I am only reading" structurally obvious.

### Side effects run *after* the boundary, never inside

Broadcasts and timer transitions must observe the *committed* state, and a timer op
inside the block would nest a second commit and self-conflict. So the shape is always:

```kotlin
val result = withTable(tableId, persistence) { table -> /* validate + stage */ }
if (result is ServiceResult.Ok) advanceTimer(tableId)   // post-commit side effect
return result
```

Notice there is **no explicit broadcast call** in the success path. That's section 6.

### `ServiceResult`

A small sealed type that carries an HTTP status through the service layer without the
service knowing about Ktor request/response objects:

```kotlin
sealed class ServiceResult<out T> {
    data class Ok<T>(val value: T, val status: HttpStatusCode = OK)
    data class Failed(val status: HttpStatusCode, val error: String)
}
```

`ApplicationCall.respond(result)` maps it to the wire (`Ok` → value, `Failed` → `{error}`).

### `GameService` — orchestration

`GameService` is where lifecycle and in-round logic compose into the `withTable`
shape. Every mutating method follows the same recipe: *validate + stage inside
`withTable`, then run the post-commit timer transition.* A representative example,
`applyAction`:

1. Map the `ActionRequest` to a `PlayerCommand` (Fold/Call/Raise/AllIn).
2. Inside `withTable`: reject if no round, paused, not a betting round, or not your
   turn; un-idle the actor; run `processPlayerCommand` (catching invalid-command
   exceptions as 400s); if the round ended, `clearRoundState()`.
3. After commit: `advanceTimer(tableId)` — start the next player's clock if a betting
   round is live, else cancel it.

---

## 6. Real-time fan-out (how clients get updates)

Players at one table may be connected to **different server instances**. The instance
that commits a change can't reach the others' WebSockets directly. The
**`TableUpdateBus`** solves this, and it's why no handler calls "broadcast" explicitly
— the *commit itself* drives delivery.

```kotlin
interface TableUpdateBus {
    fun subscribe(tableId: Int, onChange: suspend (PokerTableState) -> Unit): Subscription
}
```

**Production — `FirestoreTableUpdateBus`.** Every instance holding a socket for a table
attaches a Firestore **snapshot listener** to `tables/{id}`. When *any* instance
commits a write to that document, Firestore pushes the new document to *every*
listener. The database itself is the publisher — there is no explicit publish step.
Two correctness details:

- **Ordering.** Each subscription drains snapshots through a single-consumer
  `Channel`, so `onChange` runs strictly in commit order and one table's slow
  broadcast can't head-of-line-block another's.
- **Self-heal.** On a listener error it re-subscribes after a short delay, so a
  terminal error doesn't strand a table's clients on that instance.

**Dev / tests — `InMemoryTableUpdateBus` + `NotifyingPersistence`.** A single process
has no database to fire listeners, so `NotifyingPersistence` decorates the in-memory
persistence: after a successful CAS write it publishes the new state to the in-memory
bus. Same subscription path as production, fired manually. This symmetry means
`MultiInstanceTest` can prove a commit on one "instance" reaches a socket on another.

**`TableConnectionManager`** is the per-instance glue:

- Holds `tableId → playerId → WebSocketSession`.
- Opens **one bus subscription per table** it has sockets for (on the first local
  socket; cancels on the last). The subscription handler calls `broadcastGameState`.
- `broadcastGameState(state)` builds a **per-player** `GameStateUpdate` and sends it.
  Per-player because each player sees only their own pocket cards — unless it's a real
  showdown, when active players' cards (and computed best hand) are revealed. Active
  votes ride along in the same frame because they live in the table state.

So the end-to-end push path is:

```
commit (any instance) → Firestore write → snapshot listener fires on every instance
   → TableConnectionManager.broadcastGameState → per-player GameStateUpdate → sockets
```

The WebSocket channel is **server→client only**. Client actions always go over REST;
the socket carries pushes and keepalive pings. `WebSocketRoutes` also handles the
connection lifecycle: a (re)connect brings the player ONLINE through a versioned
`withTable` write (so a concurrent round mutation can't be lost), displaces and closes
any stale prior socket, and on disconnect marks the player OFFLINE — but only if they
haven't already reconnected on a fresh session.

---

## 7. Durable timers (turn clocks & vote timeouts)

A player's turn has a clock; if it expires the server auto-plays for them. The naive
implementation — an in-process `delay()` coroutine — dies when the instance scales to
zero or restarts. So the countdown lives **off-instance** too.

**`TaskScheduler`** abstracts "fire this callback later":

- **`CloudTasksScheduler`** (prod) enqueues a **Cloud Task** that POSTs an
  `/internal/*` endpoint on this service at the scheduled time. Durable across
  restarts and scale-to-zero (delivery cold-starts an instance) and lands on whichever
  instance is up.
- **`InMemoryTaskScheduler`** (dev/tests) is a `delay()` coroutine that calls the
  handler in-process.

Both converge on the **same handler** (`TurnTimerManager.onTimerFired` /
`VotingService.onVoteExpired`): in-process schedulers call it directly via
`attachExpiry`; Cloud Tasks delivers over HTTP to `InternalRoutes`, which calls the
same handler. `/internal/*` is guarded by a shared-secret header
(`X-Internal-Token`, constant-time compared) so only the queue can drive expiry.

**Delivery is at-least-once, so handlers are idempotent:**

- The durable timer state is the persisted `turnTimerStartedAt`, which *is* the
  idempotency token. Each new turn re-arms with a fresh timestamp; a late or duplicate
  delivery of a prior turn's task no longer matches and is ignored. `cancel()` on Cloud
  Tasks is a deliberate no-op — the token check makes obsolete deliveries harmless, so
  there's no need to track task names for deletion.
- `onTimerFired` ignores the delivery unless the token still matches, the game is
  RUNNING, and a betting round is live. Otherwise it idles the timed-out player and
  either auto-pauses (if that tips the table to majority-idle) or auto-plays the turn —
  all in one commit — then cascades through any further absent players via
  `resolveExpiredTurns`.

Because every auto-play/auto-pause is a `withTable` commit, clients are updated by the
bus fan-out — `TurnTimerManager` never broadcasts directly.

---

## 8. Voting

Pause, unpause, kick, and restart-game are democratic: a player opens a vote and
others cast. The design choice here mirrors everything else — **there is no
`VoteManager` and no in-memory vote map.** Vote tallies live in
`PokerTableState.activeVotes`, so they're persisted, versioned, and fan out via the
bus exactly like game state.

`VotingService` flow:

- **Open a vote** (`createSession`): inside `withTable`, validate the resolution is
  legal, compute eligible voters (present/ONLINE players + the initiator; the kick
  target is excluded), build an `ActiveVote` with `requiredVotes = eligible/2 + 1`,
  and `openVote`. If the initiator alone already passes it (e.g. a 1-eligible vote),
  the consequence is applied and the vote closed **in the same commit**.
- **Cast a vote** (`castVote`): inside `withTable`, record the vote; if it now passes,
  stage the consequence and close the vote atomically; if it fails, close it.
- **The same-commit consequence** is the key invariant: a passed vote and its effect
  (pause/unpause/kick/restart) are one atomic state transition, so no client ever sees
  a passed-but-not-yet-applied vote.

Post-commit side effects (`applyCommit` / `runPostCommit`) run *after* the boundary:
arming or cancelling the vote's Cloud Tasks timeout, transitioning the turn timer, and
emitting ephemeral toast messages (`broadcastMessage`). The vote timeout itself is a
durable Cloud Task calling `onVoteExpired`, idempotent on whether the vote UUID is
still open.

---

## 9. Configuration

`ServerConfig.fromEnvironment()` reads all tunables from env vars (see the table in
`CLAUDE.md`). Two production guards matter:

- `assertSecretsAreSet()` (called only from the prod entry point) refuses to start if
  `JWT_SECRET` or `INTERNAL_TOKEN` are still the publicly-known dev defaults —
  otherwise anyone could forge sessions or trigger timer/vote expiry.
- `ALLOWED_ORIGIN` must be a concrete origin in production for credentialed CORS to
  work (see §3).

---

## 10. Persistence & deployment notes

- **Firestore document layout.** Each table is one document `tables/{id}` with fields:
  `state` (the JSON-serialized `WireablePokerTableState`), `version` (the CAS guard),
  and `expiresAt` (a native Firestore `Timestamp`, 12h out). A native **TTL policy** on
  the `expiresAt` field reaps abandoned tables.
- **CAS write.** `saveStateIfVersionMatches` runs a Firestore transaction: read the
  current `version`, write only if it equals `state.version - 1`. This is what makes
  the optimistic-concurrency retry in `withTable` correct across instances.
- **Blocking SDK calls on IO.** The Firestore SDK is blocking (`.get()`), so `loadTable`
  and `withTable`'s restore/commit run on `Dispatchers.IO` to keep the Netty
  event-loop threads free. Cloud Tasks enqueues are likewise launched off the calling
  coroutine.
- **Stateless ⇒ horizontal scale.** All cross-instance coordination is off-instance
  (Firestore listeners + Cloud Tasks + votes-in-state), so any instance serves any
  player. Cloud Run pins `maxScale` purely as a **cost ceiling** (not a correctness
  one) and `minScale=0` for scale-to-zero. The only per-instance state is each
  instance's own live sockets + their bus subscriptions (correct by design) and
  ephemeral toasts (cosmetic; the underlying state still fans out).

---

## 11. End-to-end: a player calls

Putting it together, here is what happens when a player clicks "Call":

1. Browser `POST /api/tables/42/action` with `{type:"CALL"}` and its cookie.
2. `TableRoutes` extracts the session from the JWT cookie → `PlayerSession(42, playerId)`.
3. `GameService.applyAction` opens `withTable(42)`:
   - restores table from Firestore,
   - validates round/turn/status,
   - `setPlayerOnline` + `processPlayerCommand(Call(...))` stage mutations,
   - `commit()` does one CAS write (`version` bumps).
4. The Firestore write triggers the **snapshot listener on every instance** holding a
   socket for table 42.
5. Each instance's `TableConnectionManager` builds a per-player `GameStateUpdate` and
   pushes it down each socket — every player sees the new pot, the next actor, etc.
6. Back in `applyAction`, post-commit `advanceTimer(42)` starts the next player's clock
   by enqueuing a Cloud Task; persisting `turnTimerStartedAt` is itself a commit that
   fans out the new `turnTimerEndsAt` to clients.
7. The HTTP response (`{status:"ok"}`) returns to the original caller — but the *game
   state* reached them (and everyone) over the WebSocket, not this response body.
