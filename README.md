# ♠ Poker Online

A full-stack, real-time multiplayer **Texas Hold'em** — a modular Kotlin game engine behind a
Ktor WebSocket server, with a React + Pixi.js canvas client. Built as a portfolio piece to
demonstrate clean domain modelling and a **stateless, horizontally-scalable** cloud architecture
that still fits inside a free tier.

**Stack:** Kotlin (JVM 21) · Ktor · Firestore · Cloud Tasks · Cloud Run · React 19 · TypeScript · Pixi.js v8

---

## What it does

**Gameplay**
- Complete Texas Hold'em hand: blinds, four betting rounds (pre-flop → river), **side pots**,
  showdown hand evaluation, and automatic **blind escalation** over time.
- Real-time multiplayer: every table change is pushed to all seated players over a WebSocket, and
  the table is drawn on a Pixi.js canvas with dealing, chip, and card animations.
- Per-player turn timers with **auto-fold**; players can disconnect and reconnect mid-hand.

**Table lifecycle & social controls**
- Create a table (with a human-friendly **name**) or join one by id; ready-up and start rounds.
- **Democratic controls via voting** — pause/unpause, kick an absent player, restart the game, or
  increase the blinds all require a table vote rather than an admin.
- **"My tables"** — the home screen surfaces every table this browser holds a session for and lets
  you rejoin with one click, or **permanently leave** (which frees your seat for a new player).

## Tech stack

| Layer | Technology |
|---|---|
| **Game engine** | Kotlin (JVM 21), Kotlinx Serialization — zero server dependencies |
| **Server** | Ktor (REST + WebSocket), JWT session cookies |
| **State & real-time** | Firestore — the source of truth *and* the cross-instance message bus |
| **Durable timers** | Cloud Tasks (scheduled HTTP callbacks) |
| **Frontend** | React 19, TypeScript, Vite, Emotion, Pixi.js v8 |
| **Infra / delivery** | Cloud Run, Artifact Registry, Cloud Build, Secret Manager |
| **Tests** | JUnit (engine + server), Playwright (E2E + visual regression) |

## Repository layout

```
poker-engine/   Pure Kotlin game logic — no server, no cloud. Fully unit-tested.
server-gcp/     Ktor HTTP + WebSocket server. Persistence, voting, timers, auth.
frontend/       React + Pixi.js single-page app (Vite). Playwright E2E suite.
docs/           Architecture write-ups (see "Further reading").
```

---

## Architecture

The whole design falls out of **one constraint**: on Cloud Run, instances are ephemeral and not
addressable — they autoscale, they scale to **zero** when idle, and anything held in one instance's
memory (a countdown, a live WebSocket, a vote tally) is invisible to the others and lost when that
instance goes away. So any state shared between players — who may land on different instances — must
live **outside** the instances, in a managed service.

```
   browser (SPA) ──WS + REST──►  Cloud Run (N stateless instances)
        ▲                              │  commit (compare-and-set write)
        │  live game frames            ▼
        │                        ┌──────────────┐   snapshot push   ┌────────────┐
        └────────────────────────│  Firestore   │──────────────────►│ every       │
                                 │ tables/{id}  │   (change bus)    │ instance    │
                                 └──────────────┘                   └────────────┘
                                        ▲
                    "call me back at T" │  HTTP callback (turn / vote timers)
                                 ┌──────────────┐
                                 │ Cloud Tasks  │
                                 └──────────────┘
```

- **One write == one broadcast.** A player's action commits new state to `tables/{id}` as a single
  versioned compare-and-set write. Firestore then *pushes* that document to every instance holding a
  snapshot listener on the table, and each rebuilds the per-player frame and sends it over its own
  WebSockets. The writing instance never needs to know where the other players are connected.
- **Timers live off-instance.** "Auto-fold in 30s" and "close this vote in 60s" are Cloud Tasks that
  call an internal endpoint back at the deadline — surviving restarts, deploys, and scale-to-zero.
  They're idempotent (a turn token / vote id guards at-least-once delivery).
- **Votes are just state.** Vote tallies live in the table document, so they fan out over the same
  Firestore bus as everything else — no separate mechanism.

Because all coordination is off-instance, **any instance can serve any player**, and the service
scales horizontally with no sticky sessions.

### Server-side invariants worth calling out

- **Single write path.** All state transitions go through one `withTable(id) { … }` boundary that
  restores the table, runs the mutation, and commits **exactly once** with optimistic concurrency; a
  conflicting write is caught and the whole block retried. There is no unconditional write path.
- **Serialization via a "wire" pattern.** Domain objects that hold mutable references (e.g. `Player`)
  can't be serialized directly, so each exposes a `toWire()` / `restore()` pair that converts to and
  from an id-only `@Serializable` form.
- **Same-origin delivery.** A multi-stage Docker build bundles the built SPA into the server image so
  it's served same-origin — no CORS and no `SameSite=None` cookie dance in production.

### Frontend

The client keeps a scene model and updates it from each incoming `game_state` frame; a small
event/observer layer drives one-shot effects (dealing, chip motion) so rendering stays declarative.
State changes made in this browser (create / join / rename / leave) are optimistically folded into
the UI through the same observer bus, avoiding redundant re-fetches.

---

## Key architectural decisions

The trade-offs behind the design — the short version. The full rationale, alternatives, and an
AWS-analogy glossary are in [`docs/architecture-gcp.md`](docs/architecture-gcp.md).

| Decision | Why | Rejected alternative |
|---|---|---|
| **Engine has zero server/cloud deps** | Rules are testable in isolation and portable to other poker variants or transports | Coupling game logic to the server/DB |
| **Firestore as state store *and* message bus** | The source-of-truth write already happens, so snapshot listeners give fan-out for free — one managed service, all free-tier | Redis pub/sub (no free tier); Pub/Sub (double-publishes every change) |
| **Cloud Tasks for turn/vote timers** | Durable HTTP callbacks survive scale-to-zero, deploys, and crashes; instance-agnostic | In-memory `delay()` (dies with the instance) |
| **Votes stored as table state** | Become ordinary state → fan out for free; CAS handles concurrency | In-memory maps (invisible across instances) |
| **Single `withTable` commit boundary (CAS)** | One logical operation = one consistent, versioned write; predictable concurrency | Ad-hoc writes scattered across handlers |
| **Per-table JWT session cookies** | A player can hold independent sessions across many tables; each is httpOnly and scoped | One global session / server-side session store |
| **Serve the SPA from Cloud Run** | Same-origin → no CORS, no cross-site cookie config | Separate static host that can't proxy WebSockets |
| **`minScale=0` + budget kill-switch** | $0 at idle, and a hard spend cap makes an accidental bill impossible for a portfolio | Always-on instances; WAF/Load Balancer (both incur cost) |

---

## Running locally

Two processes — the Ktor backend and the Vite dev server. The dev backend uses **in-memory**
persistence and an in-memory scheduler, so **no GCP setup is needed** to play locally.

```bash
# 1. Backend  (HTTP + WebSocket on :8080)
./gradlew :server-gcp:run

# 2. Frontend (Vite on :5173, proxies /api and /ws to :8080)
cd frontend && npm install && npm run dev
```

Open the printed URL, and a second browser profile to join as another player. Full instructions
(including custom ports) are in [`how-to-run.MD`](how-to-run.MD).

## Testing

```bash
./gradlew test                 # Kotlin engine + server (unit + integration)
cd frontend && npm run test:e2e # Playwright E2E + visual regression (needs both servers running)
```

## Further reading

- [`docs/architecture-gcp.md`](docs/architecture-gcp.md) — how each managed service fits, why it's
  there, and the decisions log in full.
- [`docs/backend-overview.md`](docs/backend-overview.md) — a tour of the server modules.
- [`CLAUDE.md`](CLAUDE.md) — build/test commands and a detailed module-by-module reference.
