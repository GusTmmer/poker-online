# Path B — Stateless, Cost-Bounded, Horizontally-Scalable Poker on Cloud Run

> Status: **Phases 0–4 complete — Path B implemented end-to-end.** All shared coordination is off-instance
> (fan-out via Firestore listeners; turn + vote timers via Cloud Tasks; votes in table state), `maxScale`
> raised to 3 as a pure cost ceiling, and `MultiInstanceTest` proves cross-instance fan-out. The
> Firestore-listener path runs in CI via **Testcontainers**; Cloud Tasks is GCP-only. **Phase 5** is the
> real-GCP deployment (apply `infra/README.md`, set `SERVICE_URL`, verify on prod) — the one step that
> needs a live project. For how the GCP services interact (with AWS analogies), see
> [`architecture-gcp.md`](architecture-gcp.md).
> Supersedes the abandoned "Cloud LB + `HEADER_FIELD` session affinity on `X-Table-Id`" design
> (Cloud Run instances are not addressable; affinity pins a *client* best-effort, never a *table*,
> and a fronting LB costs ~$18/mo).

## 1. Goal & guiding constraints

Make the three in-memory coordination subsystems stateless so any Cloud Run instance can serve any
player, **without** giving up cost safety.

- **Expected cost $0/mo** — live inside GCP Always Free (perpetual; *not* a 12-month clock — that's AWS).
- **Hard-capped < $5/mo** via a budget → billing kill-switch.
- **Scale-to-zero** (`min-instances=0`) is non-negotiable.
- Horizontal scale is *demonstrative*: deployed with a low `max-instances` ceiling, because one
  instance at high concurrency already holds every realistic player. The architecture is genuinely
  scalable; the deployment is deliberately bounded ("cost-bounded autoscaling").

## 2. Target architecture

Single idea: **the Firestore table document is the source of truth, and all coordination rides on it.**

| Subsystem | Today (in-memory) | Path B (stateless) |
|---|---|---|
| **WS fan-out** (`TableConnectionManager`) | local socket map; broadcast reaches only local sockets | Firestore **snapshot listener** per table; each instance re-broadcasts to *its* local sockets on every committed change |
| **Turn timers** (`TurnTimerManager`) | in-process coroutine `delay()` | **Cloud Tasks** scheduled HTTP callback to an internal endpoint; idempotent via turn token |
| **Vote state** (`VoteManager`) | in-process session maps + coroutine timeout | vote state on the **Firestore table doc**; timeout via **Cloud Tasks**; fans out via the same listener |

Why Firestore listeners (not Pub/Sub or Redis): they reuse the CAS writes already made by the
`withTable` commit, so fan-out is write-neutral and needs **zero extra infrastructure**. Redis has no
free tier; Pub/Sub adds plumbing for no benefit here.

Recurring pattern: **commit state via the existing `withTable` CAS path → the snapshot listener
delivers the new state to every instance → each instance pushes to its local sockets.** "Who wrote" is
fully decoupled from "who broadcasts."

## 3. Code changes (`server-gcp`)

`poker-engine` stays untouched.

### 3.1 Fan-out abstraction (`TableUpdateBus`)
- Interface: `subscribe(tableId, onSnapshot): Subscription`.
- **Firestore impl** wraps `addSnapshotListener` on `tables/{id}`; ref-counts per table (open on first
  local socket, close on last).
- **In-memory impl** for tests/local (drives callbacks on commit). Keeps the engine's
  `PokerTablePersistence` interface clean.
- Post-commit explicit broadcasts are replaced by listener-driven broadcasts (one code path). The
  `TableConnectionManager` shrinks to "local socket registry + per-player frame builder."

### 3.2 Durable timers (`TurnTimerManager` → Cloud Tasks)
- Internal route `POST /internal/timer-expire/{tableId}`.
- On turn start: enqueue a Cloud Task at `now + turnTimerSeconds` with a **turn token** (table
  `version` or a monotonic turn counter).
- On delivery: if token is stale (player already acted), no-op; else run existing auto-play/auto-pause
  via `withTable`, then enqueue the next task. **Idempotency is mandatory** (Cloud Tasks is at-least-once).
- Keep persisting `turnTimerStartedAt` for the client clock.
- Secure `/internal/*` with the Cloud Tasks **OIDC token** (verify issuer/audience/SA email);
  shared-secret header is the simpler fallback.

### 3.3 Votes to Firestore (`VoteManager`)
- Vote sessions become state on the table doc; create/cast = `withTable` CAS (existing retry covers
  concurrent casts). They already appear in the broadcast snapshot → fan-out is automatic.
- Timeout via Cloud Tasks (`/internal/vote-expire`), same idempotency pattern.
- Ephemeral toasts ride as a deduped `lastNotification {id,type,message}` field the listener forwards.

### 3.4 Supporting changes
- Offload blocking Firestore calls to `Dispatchers.IO`.
- Idle WebSocket reaping (server closes idle sockets; client closes on tab-hidden via Page Visibility).
- Rate limiting (Ktor `RateLimit`): per-IP caps on table creation, joins, WS connects.
- Serve the built frontend **from the Cloud Run service** (same origin → no CORS / no `SameSite=None`).
- Health/readiness endpoint.

## 4. Infrastructure

| Item | Setting / note |
|---|---|
| **Cloud Run** | `us-central1`; `min-instances=0`; `max-instances=2–3`; concurrency 250–1000; 1 vCPU / 512Mi–1Gi; request timeout `3600s`; request-based billing |
| **Firestore** | Native, same region; TTL policy on `tables.expiresAt`; rules deny all client access |
| **Cloud Tasks** | one queue (`poker-timers`); conservative retry (idempotency covers double-delivery) |
| **IAM** | runtime SA: `datastore.user` + `cloudtasks.enqueuer`; tasks carry OIDC from an SA with `run.invoker` |
| **Artifact Registry** | repo + cleanup policy (keep last ~3 images) |
| **Frontend** | built and served by the Cloud Run service (same origin); cache headers on assets |
| **Budget kill-switch** | Budget $5 → Pub/Sub → Cloud Function (gen2) disabling project billing at 100% |

**Avoid** (each breaks the budget): `min-instances>0`, external HTTPS LB, Cloud Armor, Memorystore
Redis, Serverless VPC connector.

## 5. Cost & abuse posture

- Idle = **$0** (scale-to-zero). Portfolio traffic stays inside Always Free → **$0/mo**.
- `max-instances` bounds worst case; the kill-switch makes **< $5 a hard guarantee** (budget alerts
  only notify).
- Abuse closed: table-create flood (rate-limit + kill-switch), socket flood (concurrency +
  `max-instances`), forgotten tabs (idle reaping), internal endpoint (OIDC-gated).

## 6. Phased rollout

- **Phase 0 — Guardrails** ✅ *(`max-instances=1`; "Path A done right")*:
  - ✅ Cloud Run manifest pinning `minScale=0` / `maxScale=1` / `timeout=3600` / `concurrency=250` (`infra/cloudrun-service.yaml`)
  - ✅ Budget → Pub/Sub → billing kill-switch Cloud Function (`infra/billing-killswitch/`)
  - ✅ Artifact Registry cleanup policy + Firestore TTL (runbook: `infra/README.md`)
  - ✅ Per-IP rate limiting on table-create + join (Ktor `RateLimit`, verified 30/min → 429)
  - ✅ Idle-socket reaping: client closes WS after 60s hidden, reconnects on foreground (`useGameSocket.ts`)
  - ✅ Blocking Firestore calls moved to `Dispatchers.IO` (`loadTable` / `withTable`)
  - ✅ Same-origin SPA hosting from the Cloud Run service (Ktor `singlePageApplication`, gated on `STATIC_DIR`; multi-stage Dockerfile builds + bundles the frontend) — verified locally
  - ✅ `/healthz` readiness endpoint
  - Remaining (needs a live project): apply `infra/README.md` steps; verify the Docker frontend bundling in a real `gcloud builds submit`.
- **Phase 1 — Listener-driven fan-out** *(single instance, behavior-preserving)*: De-risks the hardest piece.
  - **1a ✅** Foundation: `TableUpdateBus` interface + `InMemoryTableUpdateBus` + `NotifyingPersistence`
    decorator + `FirestoreTableUpdateBus` (snapshot listeners). Unit-tested on the in-memory path
    (`TableUpdateBusTest`). Not yet wired into the app.
  - **1b ✅** Wired in. The bus subscription (per table, opened on the first local socket / cancelled on
    the last) is now the **sole** broadcast path for state changes: explicit post-commit broadcasts were
    removed from `GameService` and `TurnTimerManager` (both shed their now-unused `connectionManager`/
    `VoteManager` deps). `InMemoryTableUpdateBus` delivers in commit order (`limitedParallelism(1)`).
    Wired: `FirestoreTableUpdateBus`+shared `Firestore` into `Application`; `InMemoryTableUpdateBus`+
    `NotifyingPersistence` into `LocalServer` and both test harnesses. Fan-out integration test passes
    (a REST commit reaches a *different* player's socket via the bus); `FirestoreTableUpdateBusTest` is
    emulator-guarded (skips without `FIRESTORE_EMULATOR_HOST`).
  - **Deferred to Phase 3 (intentional):** `VotingService` still broadcasts vote tallies explicitly,
    because a *pending* vote cast commits no table state (so the bus can't carry it). That's correct at
    single instance; cross-instance vote fan-out arrives when votes move into Firestore state (Phase 3),
    which precedes the multi-instance flip (Phase 4). A *passed* vote double-broadcasts (explicit + bus)
    — harmless (same state, ordered).
- **Phase 2 — Durable timers via Cloud Tasks** ✅: `TaskScheduler` abstraction (`InMemoryTaskScheduler`
  for dev/tests, `CloudTasksScheduler` for prod) replaces the in-process `delay()` timer; `TurnTimerManager`
  dropped its in-memory timer map entirely. `onTimerFired` is idempotent via a **turn token** (the
  persisted `turnTimerStartedAt`): a delivery is ignored unless the token still matches and the game is
  RUNNING in a betting round — so a late/duplicate Cloud Task (at-least-once) or a fire during pause is a
  no-op. Internal endpoint `POST /internal/timer-expire/{tableId}` guarded by a shared-secret header
  (OIDC noted as the stricter alternative). WS timer-recovery on reconnect removed (durability now comes
  from Cloud Tasks). Tests: an unattended turn times out and auto-plays via the in-memory scheduler; the
  internal endpoint rejects calls without the secret. Cloud Tasks env/secret/queue added to
  `infra/` (`SERVICE_URL`, `INTERNAL_TOKEN`, `CLOUD_TASKS_*`).
- **Phase 3 — Votes to Firestore + Cloud Tasks timeouts**:
  - **3a ✅** Votes moved into `PokerTableState.activeVotes` (a plain ID-based `@Serializable ActiveVote`,
    no Wireable needed). Opening/casting a vote is now a `withTable` commit, so it fans out via the bus
    like any state — **closing the Phase 1b cross-instance vote gap**. A cast that passes stages its
    consequence (pause/unpause/kick/restart) and closes the vote in the *same* commit; timer transitions
    and ephemeral toasts run post-commit. `VoteManager` deleted; `TableConnectionManager.votesProvider`
    gone (votes read straight from state). Tests: lifecycle/acceptance vote flows still pass; a pending
    vote fans out to another player's socket; `FirestoreTableUpdateBusTest` now **runs green against the
    emulator**.
  - **3b ✅** Vote timeout moved onto Cloud Tasks. `TaskScheduler` generalized with
    `scheduleVoteTimeout`/`cancelVote`/`attachVoteExpiry`; `CloudTasksScheduler` enqueues to
    `/internal/vote-expire/{tableId}/{sessionId}` (same queue + shared-secret guard as the turn timer);
    `VotingService` dropped its in-process timeout coroutine and exposes an idempotent `onVoteExpired`
    (closes the vote only if still open — the UUID never collides with a re-opened vote). Tests: the
    vote-expire endpoint closes a still-open vote; a pending vote auto-closes when its timeout fires via
    the in-memory scheduler. **Result: no shared in-memory coordination state remains** — `VotingService`
    and `TurnTimerManager` are stateless.
  - *Known minor caveat for Phase 4:* ephemeral toasts (`paused`, `vote_failed`, …) are still delivered
    instance-locally via `broadcastMessage`, so a player on another instance sees the resulting *state*
    (it fans out via the bus) but may miss the transient toast. Cosmetic; optionally move to a deduped
    `lastNotification` state field.
- **Phase 4 — Flip to multi-instance** ✅: `maxScale` 1→3 in `infra/cloudrun-service.yaml` (now a pure
  cost ceiling, no longer a correctness one). `MultiInstanceTest` runs two `TableConnectionManager`s
  (stand-in instances) over one shared bus + persistence with fake sockets and asserts a commit by anyone
  reaches sockets on *both* — the multi-instance design verified without real GCP. `CLAUDE.md` updated
  (deployment note, env table, architecture bullets, data flow).
- **Phase 5 — Finalize** ☐ *(needs a live project)*: apply `infra/README.md`, two-step deploy to set
  `SERVICE_URL`, configure Firestore TTL + budget kill-switch, confirm cross-instance behavior and $0 at
  idle on prod.

Every phase is a deployed, working, cost-safe system; the multi-instance flip is a config change over
code already proven on one instance.

## 7. Risks & open questions

- Cloud Tasks at-least-once → idempotency token is load-bearing; design it first.
- Firestore listener reads scale with (instances × change rate); bounded by low instance count.
- Fan-out latency ~tens–low-hundreds ms; per-document ordering preserved (fine for poker).
- Cold-start adds a few JVM seconds to Cloud Tasks-delivered timers at scale-to-zero — acceptable.
- JVM at 512Mi with Firestore SDK + listeners — validate; bump to 1Gi only if needed.
- Confirmed decision: frontend served from Cloud Run (same origin).

## 8. Definition of done

Deployed at `max-instances≥2`; two players on different instances see consistent state; timers fire
after restart and at scale-to-zero; votes resolve cross-instance; idle disconnect works; floods are
rate-limited; kill-switch hard-stops at $5; **$0 at idle**.
