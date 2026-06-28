# GCP Architecture — how the services fit together

> Audience: someone comfortable with the *idea* of cloud services (light AWS background) but new to
> GCP. Every service below has an "AWS analogy" column — treat it as a rough mental hook, not an exact
> equivalence. This documents **why** each service is here and **how they interact**, so the design
> transfers to other projects. Companion to `path-b-plan.md` (the rollout plan).

## 1. The one constraint everything follows from

**Cloud Run instances are ephemeral and not addressable.** You can't point at "instance #3", instances
appear and disappear with autoscaling, and the platform can scale to **zero** when idle. Anything kept
in one instance's memory (a countdown timer, a live WebSocket session, a vote tally) is invisible to
other instances and is lost when that instance goes away.

Every decision below is a consequence of that: any state that must be shared between players — who
might land on different instances — has to live **outside** the instances, in a managed service. So
the architecture is really "move the three in-memory things (broadcasts, timers, votes) onto managed
GCP services."

## 2. Service catalog (with AWS analogies)

| GCP service | What it is here | Rough AWS analogy |
|---|---|---|
| **Cloud Run** | Runs our container; autoscales, scale-to-zero, pay-per-use | App Runner / Fargate behind an ALB; Lambda-ish billing but long-running + WebSocket-capable |
| **Firestore (Native)** | Document database **+ real-time change push** to subscribers | DynamoDB **+** DynamoDB Streams **+** AppSync subscriptions, in one service |
| **Cloud Tasks** | A queue that makes an **HTTP call at a scheduled time** | SQS (delivery + retries) **+** EventBridge Scheduler (the "fire at time T" part) |
| **Pub/Sub** | Topic-based messaging / fan-out | SNS topic (+ SQS-style subscriptions) |
| **Cloud Functions (gen2)** | Single-purpose event-triggered function | Lambda |
| **Artifact Registry** | Stores our built Docker image | ECR |
| **Cloud Build** | Builds the image from source | CodeBuild |
| **Secret Manager** | Stores the JWT signing key | Secrets Manager / SSM Parameter Store |
| **Cloud Billing Budgets** | Tracks spend, emits alerts | AWS Budgets |
| **IAM + service accounts** | Identity/permissions; the identity a workload runs as | IAM policies + IAM roles |
| **OIDC tokens** | How one service proves its identity to another over HTTP | SigV4 / IAM auth between services |

## 3. The big picture

```
                         ┌─────────────────────────────────────────────┐
   browser (player)      │                 Cloud Run                    │
   ┌──────────┐  WS+REST │   ┌───────────┐        ┌───────────┐         │
   │  SPA     │◄────────►│   │ instance1 │        │ instance2 │  ...    │
   └──────────┘          │   └─────┬─────┘        └─────┬─────┘         │
        ▲                │         │  commit (CAS write)│               │
        │ WS frames      └─────────┼────────────────────┼───────────────┘
        │                          ▼                    ▼
        │                 ┌───────────────────────────────────┐
        │  snapshot push  │             Firestore             │  ← source of truth +
        └─────────────────┤        collection: tables/{id}    │    real-time change bus
                          └───────────────────────────────────┘
                                    ▲                 ▲
              schedule "call me      │                 │  budget notification
              back in 30s"          │                 ▼
                          ┌─────────────────┐   ┌──────────┐   ┌──────────────┐
                          │   Cloud Tasks   │   │ Pub/Sub  │──►│ Cloud Function│──► disables
                          └─────────────────┘   └──────────┘   │  kill-switch  │    billing
                              (HTTP callback                   └──────────────┘
                               to Cloud Run)
```

- **Browser ↔ Cloud Run**: WebSocket (live game frames) + REST (actions). The browser **never** talks
  to Firestore/Tasks directly — only to our service.
- **Cloud Run ↔ Firestore**: instances *write* committed state, and *subscribe* to changes (§4).
- **Cloud Tasks → Cloud Run**: scheduled HTTP callbacks that drive turn/vote timers (§5).
- **Budgets → Pub/Sub → Function**: the cost kill-switch (§7).

## 4. Firestore as the state store *and* the message bus  (Phase 1)

Firestore is a document DB (like DynamoDB), but its defining extra feature is **real-time listeners**:
a client can call `addSnapshotListener(docRef)` and Firestore will **push** the new document to it
every time that document changes — no polling.

We use this for the **WebSocket fan-out problem**. When a player acts:

1. The instance handling the request commits the new table state to `tables/{id}` (a transactional
   compare-and-set write — our optimistic concurrency).
2. Firestore pushes the updated document to **every instance that has a listener** on `tables/{id}`.
3. Each of those instances rebuilds the per-player JSON frame and sends it over the **WebSocket** to
   the players *it* holds connections for.

So Firestore is acting as the **broadcast bus between instances**. The instance that made the change
doesn't need to know which other instances hold the table's other players — it just writes, and
Firestore notifies everyone watching.

```
player A (inst1) acts
  └─ inst1: commit tables/T  ──►  Firestore
                                    ├─ push snapshot ─► inst1 listener ─► WS ─► player A
                                    └─ push snapshot ─► inst2 listener ─► WS ─► player B
```

**Who consumes the events?** The same `server-gcp` service — each instance is both writer and listener.
**Is WS still the client transport?** Yes, unchanged. Firestore is purely server-to-server plumbing;
the frontend doesn't change.

**Why Firestore and not Pub/Sub or Redis for this?** The write to Firestore *already happens* (it's the
source of truth), so the listener is free fan-out — no extra messages to publish, no Redis to run
(Redis/Memorystore has no free tier). Pub/Sub would mean publishing a second copy of every change.

**Lifecycle detail:** an instance opens a listener when its first socket for a table connects and closes
it when its last one disconnects (ref-counting) — so we don't leak listeners or pay for reads on
tables nobody here is watching.

## 5. Cloud Tasks for durable timers  (Phase 2)  — the unfamiliar one, explained

### What Cloud Tasks *is*
A fully-managed **queue of future HTTP calls**. You hand it a task that says, in effect: *"At time T,
make this HTTP request: `POST https://my-service/internal/timer-expire/42` with this JSON body."*
Cloud Tasks stores it, waits until T, then **makes that HTTP call to your service for you**. If your
service returns a non-2xx, it **retries with backoff** (configurable). Delivery is **at-least-once**.

Think of it as **SQS** (a durable queue with retries) combined with the **"deliver at a specific
future time"** part of **EventBridge Scheduler**, where the delivery mechanism is a plain HTTP POST to
an endpoint you own.

### Why we need it (the problem it solves)
The turn timer ("auto-fold this player in 30 seconds if they don't act") today is an in-memory
coroutine doing `delay(30_000)`. That has two fatal flaws on Cloud Run:
- If the instance is reclaimed (scale-to-zero, deploy, crash), the coroutine — and the timer — is gone.
- With multiple instances, the timer lives on whichever instance armed it; another instance handling
  the player's reconnect knows nothing about it.

Cloud Tasks lives **outside** the instances. When the timer should fire, Cloud Tasks makes an HTTP call
that **wakes up (cold-starts) an instance if needed** and lands on whichever instance is available.
The timer survives restarts, deploys, and scale-to-zero.

### How the turn timer works with Cloud Tasks
```
player's turn begins
  └─ instance enqueues a Task: "POST /internal/timer-expire/{tableId} at now+30s,
                                body = { turnToken: <table version> }"
        … 30s later, or whenever an instance is reachable …
  Cloud Tasks ─► POST /internal/timer-expire/{tableId}  ─► some Cloud Run instance
        └─ handler: is turnToken still current? (did the player already act?)
              • stale  → do nothing (player acted; this task is obsolete)
              • current→ auto-play the turn (fold/check), commit, enqueue the next turn's task
```

Two things make this safe:
- **Idempotency via a turn token.** Cloud Tasks is at-least-once, and a player may act right as the
  timer fires. The task carries the table `version` (or a turn counter) it was scheduled for; if the
  current state has moved past it, the handler no-ops. So a late or duplicate delivery is harmless — we
  never cancel tasks, we just let obsolete ones fall through.
- **The endpoint is internal.** `/internal/*` isn't for players. Cloud Tasks authenticates its call
  with an **OIDC token** (Google-signed proof of the calling service account's identity); the handler
  verifies it and rejects anything else. (AWS analogy: like requiring SigV4/IAM auth on an internal
  route.)

### Cloud Tasks vs. Firestore listeners — different tools, different jobs
- **Firestore listener** = a *continuous push stream of state changes* → used for **fan-out** (tell
  everyone the state changed, now).
- **Cloud Tasks** = a *scheduled one-shot callback* → used for **deadlines/timers** (do something at a
  future time even if nobody is connected).

Votes (Phase 3) reuse **both**: vote state lives in Firestore (so it fans out via the listener like any
state), and the vote's timeout is a Cloud Task (like the turn timer).

## 6. Build & deploy pipeline

```
source ──► Cloud Build ──► Docker image ──► Artifact Registry ──► Cloud Run (new revision)
            (CodeBuild)      (multi-stage:        (ECR)
                              frontend + server)
```
- The multi-stage `Dockerfile` builds the React app, builds the Kotlin server, and bundles the SPA into
  the image so the server serves it **same-origin** (no CORS, no cross-site cookie).
- Cloud Run reads config from the manifest (`infra/cloudrun-service.yaml`) and the JWT key from Secret
  Manager.

## 7. Cost safety as architecture

```
spend ──► Cloud Billing Budget ($5) ──► Pub/Sub topic ──► Cloud Function ──► disables project billing
                 (AWS Budgets)            (SNS-ish)          (Lambda-ish)        (hard stop)
```
- A **budget alert only notifies** — it can't stop spend. The Cloud Function subscribed to the budget's
  Pub/Sub topic is what actually **disables billing** when cost hits the cap, hard-stopping everything
  (the service goes offline) rather than letting a bill run. For a portfolio that trade-off is correct.
- The other cost levers are deployment config, not code: `maxScale` is the cost ceiling (and, until the
  coordination is stateless, also the correctness ceiling), `minScale=0` gives $0-at-idle, and high
  per-instance concurrency packs many WebSockets onto few instances.

## 8. Identity & auth between services (IAM)

- The Cloud Run service runs **as a service account** (an identity, like an AWS IAM role a workload
  assumes). That SA is granted exactly: Firestore read/write, enqueue Cloud Tasks, read the JWT secret.
- **Service-to-service calls** (Cloud Tasks → our `/internal` endpoint) are authenticated with **OIDC
  tokens** — Google mints a signed token proving the caller's SA identity; our handler verifies it.
  This is how you do "only Cloud Tasks may call this endpoint" without a shared password.
- **Players** authenticate separately and unrelated to IAM: a JWT cookie scoped to `(tableId, playerId)`,
  issued by our service. GCP IAM is for *service* identity; the JWT is for *player* identity.

## 9. Decisions log (quick reference)

| Decision | Why | Rejected alternative |
|---|---|---|
| Firestore listeners for fan-out | Write already happens → free fan-out; no extra infra | Redis pub/sub (no free tier); Pub/Sub (double-publish every change) |
| Cloud Tasks for timers | Durable, survives scale-to-zero & restarts, instance-agnostic | In-memory `delay()` (dies with the instance) |
| Vote state in Firestore | Becomes regular state → fans out for free; CAS handles concurrency | In-memory maps (invisible across instances) |
| Serve SPA from Cloud Run | Same-origin → no CORS, no `SameSite=None` cookie dance | Firebase Hosting (its CDN can't proxy WebSockets) |
| `maxScale` ceiling + kill-switch | Bounds blast radius of abuse; makes <$5 a hard cap | Cloud Armor/WAF (needs a paid Load Balancer) |
| `minScale=0` | $0 when idle | `minScale≥1` (bills 24/7) |
| Single managed DB does state **and** messaging | Fewer moving parts, all free-tier | Separate DB + broker |

## 10. Glossary (GCP → AWS one-liners)

- **Snapshot listener** — server-push on document change. *AWS:* DynamoDB Streams, but delivered to any
  subscriber instead of only Lambda.
- **Task / queue (Cloud Tasks)** — a scheduled HTTP callback with retries. *AWS:* SQS + EventBridge
  Scheduler.
- **Service account** — the identity a workload runs as. *AWS:* IAM role.
- **OIDC token** — signed proof of a service's identity for HTTP calls. *AWS:* SigV4-signed request.
- **Revision (Cloud Run)** — an immutable deployed version; traffic is split across revisions. *AWS:*
  Lambda version / App Runner deployment.
