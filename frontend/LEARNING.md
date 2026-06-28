# Learning React (and Pixi.js) through this Poker frontend

A guided tour of `frontend/`, written to re-teach you React from a real codebase and
introduce Pixi.js from zero. It assumes you once knew React, have forgotten most of
the hooks, and have never touched Pixi. Every concept is anchored to a real file you
can open side-by-side.

Read it top to bottom the first time. After that it works as a reference — each
section is self-contained.

---

## Table of contents

1. [The mental model: two worlds, one socket](#1-the-mental-model-two-worlds-one-socket)
2. [How the app boots (the render tree)](#2-how-the-app-boots-the-render-tree)
3. [React hooks refresher, taught from this code](#3-react-hooks-refresher-taught-from-this-code)
4. [State, where it lives, and *why* (hoisting decisions)](#4-state-where-it-lives-and-why-hoisting-decisions)
5. [The data layer: REST + WebSocket + types](#5-the-data-layer-rest--websocket--types)
6. [The event pipeline: snapshot vs. events (the key idea)](#6-the-event-pipeline-snapshot-vs-events-the-key-idea)
7. [Selectors: deriving UI from state](#7-selectors-deriving-ui-from-state)
8. [Pixi.js from zero](#8-pixijs-from-zero)
9. [The React⇄Pixi bridge](#9-the-reactpixi-bridge)
10. [Inside the renderer: apply / handle / tick](#10-inside-the-renderer-apply--handle--tick)
11. [Styling with Emotion](#11-styling-with-emotion)
12. [Component-by-component reference](#12-component-by-component-reference)
13. [Patterns worth stealing](#13-patterns-worth-stealing)
14. [Exercises](#14-exercises)

---

## 1. The mental model: two worlds, one socket

Hold this picture in your head before anything else. The app is split into two
rendering worlds fed by a single source of truth:

```
                    server (Ktor + Firestore)
                              │
              WebSocket frames │  REST responses
                              ▼
                  ┌───────────────────────┐
                  │   useGameSocket hook   │   ← one connection, the data spigot
                  └───────────┬───────────┘
                              │
              ┌───────────────┴────────────────┐
              │                                 │
   "what IS" (steady state)        "what just HAPPENED" (events)
              │                                 │
              ▼                                 ▼
      React components                    Pixi canvas
   (ControlBar, banners,            (seats, cards, chips,
    vote popups, timer)              halos, animations)
      via `gameState`                via the `FrameBus`
```

Two different rendering technologies, deliberately:

- **React/DOM** renders the *chrome* — buttons, sliders, banners, toasts, popups.
  These are form-like, accessible, easy to lay out with CSS. React's
  re-render-on-state-change model fits them perfectly.
- **Pixi/WebGL** renders the *game table* — the felt, animated cards flying from a
  deck, chips sliding to the pot, glowing halos. This is 60fps imperative
  animation that the DOM is bad at. Pixi draws to a `<canvas>` using the GPU.

The whole architecture is organised around keeping these two worlds fed correctly
from one stream of server messages. Most of the cleverness in this codebase is in
that plumbing. Get section 6 and you've got the spine of the project.

---

## 2. How the app boots (the render tree)

Follow the imports from the entry point. Open these files in order:

**`src/main.tsx`** — the literal entry. Standard React 19 boot:

```tsx
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
```

Two things to notice:

- `createRoot(...).render(...)` is the React 18+ API (you may remember the old
  `ReactDOM.render`). `createRoot` opts you into concurrent rendering.
- `<StrictMode>` is a *development-only* wrapper that intentionally **double-invokes**
  your components, effects, and reducers to surface bugs (impure renders, missing
  effect cleanup). This matters enormously for the Pixi code — see section 9 — because
  it means every `useEffect` that creates a Pixi app runs *twice* in dev, so the
  cleanup must be bulletproof.

**`src/App.tsx`** — routing and the top-level error net:

```tsx
<ErrorBoundary>
  <BrowserRouter>
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/table/:tableId" element={<TablePage />} />
    </Routes>
  </BrowserRouter>
</ErrorBoundary>
```

- `react-router-dom` maps URLs to components. `:tableId` is a URL param read later
  with `useParams()`.
- `<ErrorBoundary>` wraps *everything*. If any component throws during render
  (including the Pixi canvas blowing up), the user gets a "Reload" card instead of a
  white screen. See `src/components/ErrorBoundary.tsx` — it's the one place this
  codebase still uses a **class component**, because error boundaries *require*
  `getDerivedStateFromError` / `componentDidCatch`, which have no hook equivalent yet.

**The render tree once you're at a table:**

```
App
└─ TablePage                 (reads :tableId from URL, validates it)
   └─ SessionProvider        (Context: who am I, what table — fetched via REST)
      └─ TableSessionGate     (gating: loading? error? need to join? → JoinForm | GameScreen)
         └─ GameScreen        (opens the WebSocket via useGameSocket; composes everything)
            ├─ PixiPokerTable (the canvas world — fed by the FrameBus)
            ├─ ControlBar     (action buttons / raise slider — fed by gameState)
            ├─ VotePopupLayer (pause/kick vote popups)
            ├─ Toasts         (transient server messages)
            └─ Banner(s)      (paused / disconnected)
```

This shape *is* the architecture. Notice how responsibilities narrow as you go down:
`TablePage` knows about URLs, `SessionProvider` knows about identity, `GameScreen`
knows about the live game, and the leaf components each do one job.

---

## 3. React hooks refresher, taught from this code

You said you've forgotten most hooks. Here's each one **as actually used here**, so
the refresher sticks to concrete examples rather than abstract docs.

### `useState` — local, render-triggering state

State that, when it changes, should cause the component to re-render.

```tsx
// ControlBar.tsx
const [raiseValue, setRaiseValue] = useState(0)   // raise slider position
const [busy, setBusy] = useState(false)           // disable buttons mid-request
```

`useState` returns `[value, setter]`. Calling the setter schedules a re-render. Key
detail you may have forgotten: **the setter can take a function** when the new value
depends on the old one — this avoids stale-closure bugs:

```tsx
// useToasts.ts
setToasts((prev) => [...prev, { id, type, message }])   // append, based on latest
```

Always prefer `setToasts(prev => …)` over `setToasts([...toasts, …])` inside async
callbacks or timers, because `toasts` captured in the closure may be stale.

### `useEffect` — synchronising with the outside world

An effect runs *after* render and lets you touch things React doesn't manage:
timers, subscriptions, the network, the DOM, Pixi. Its shape:

```tsx
useEffect(() => {
  // setup
  return () => { /* cleanup */ }   // runs before next effect + on unmount
}, [deps])                         // re-run only when a dep changes
```

The **dependency array** is the part everyone forgets. Three forms:

- `[a, b]` — re-run when `a` or `b` change.
- `[]` — run once on mount, clean up on unmount.
- *omitted* — run after **every** render (rare; used deliberately in
  `useStateLogger.ts` so it can diff against the previous state every time).

Look at the timer in **`TurnTimer.tsx`** — a textbook effect:

```tsx
useEffect(() => {
  const endsAt = gameState.turnTimerEndsAt
  if (!endsAt) { setSecondsLeft(null); return }

  function tick() {
    setSecondsLeft(Math.max(0, Math.ceil((endsAt! - Date.now()) / 1000)))
  }
  tick()
  const id = setInterval(tick, 500)
  return () => clearInterval(id)        // ← cleanup prevents leaked intervals
}, [gameState.turnTimerEndsAt])         // ← restart only when the deadline moves
```

Why this is correct: every time the server sends a new turn deadline,
`turnTimerEndsAt` changes, the old interval is cleared, and a fresh one starts. When
the component unmounts, the interval is cleared. **No leaked timers.** That cleanup
return is the single most important habit to rebuild.

The crown jewel of effects in this codebase is `useGameSocket` (section 5) — a single
effect that owns a WebSocket, a heartbeat, reconnect backoff, and tab-visibility
handling, all torn down cleanly.

### `useRef` — a mutable box that does *not* trigger re-renders

`useRef(initial)` gives you `{ current: initial }`. Writing `ref.current = x` never
re-renders. Two distinct uses, both present here:

1. **A handle to a DOM node:**
   ```tsx
   // PixiPokerTable.tsx
   const containerRef = useRef<HTMLDivElement>(null)
   ...
   return <div ref={containerRef} ... />
   ```
   React fills `containerRef.current` with the actual `<div>` after mount. The Pixi
   canvas gets appended into it.

2. **Mutable state that must survive renders but shouldn't cause them:**
   ```tsx
   // useGameSocket.ts
   const reconnectDelayRef = useRef(RECONNECT_INITIAL_DELAY_MS)
   const prevSnapshotRef   = useRef<GameStateUpdate | null>(null)
   ```
   The previous snapshot is needed to diff against the next one, but storing it in
   `useState` would trigger a render every frame for no visual reason. A ref is exactly
   right: data that flows through the component without being *rendered*.

   There's a subtle but important pattern in `PixiPokerTable.tsx`:
   ```tsx
   const myPlayerIdRef = useRef(myPlayerId)
   useEffect(() => { myPlayerIdRef.current = myPlayerId })   // keep ref fresh
   ```
   The Pixi setup effect runs *once* (deps `[bus]`), so the `myPlayerId` value it
   captured would go stale if the prop changed. Mirroring the prop into a ref lets the
   long-lived Pixi callbacks always read the current value. This "latest-value ref"
   trick is worth memorising.

### `useCallback` and `useMemo` — stable identities

In JS, every render creates **new** function and object literals. That's usually
fine, but it breaks two things: (a) effects/children that depend on a value will see
it "change" every render, and (b) memoised children re-render needlessly.

- `useCallback(fn, deps)` returns the *same* function instance until `deps` change.
- `useMemo(() => value, deps)` returns the *same* computed value until `deps` change.

See **`SessionContext.tsx`**:

```tsx
const refresh = useCallback(async () => { ... }, [tableId])
const join    = useCallback(async (name) => { ... }, [tableId, refresh])

const value = useMemo(
  () => ({ tableId, myPlayerId, tableInfo, loading, error, join, refresh }),
  [tableId, myPlayerId, tableInfo, loading, error, join, refresh],
)
```

Why it matters here specifically: `value` is handed to a **Context Provider**. If it
were a fresh object every render, *every consumer of the context would re-render every
time*, even when nothing they care about changed. `useMemo` keeps the object identity
stable so consumers only re-render when a real field changes. And `refresh` is wrapped
in `useCallback` because `useEffect(() => { refresh() }, [refresh])` would loop forever
if `refresh` were a new function each render.

> **Rule of thumb:** reach for `useCallback`/`useMemo` when the value crosses a
> boundary where identity is observed — a context value, an effect dependency, a
> `React.memo` child, or a subscription. Don't sprinkle them everywhere "for
> performance"; they have their own cost and add noise.

### Custom hooks — extracting reusable stateful logic

A custom hook is just a function named `useX` that calls other hooks. This codebase
has several, and they're a great study in *what belongs in a hook*:

- **`useToasts`** (`hooks/useToasts.ts`) — owns a list of toasts + their auto-dismiss
  timers. Returns `{ toasts, addToast, dismissToast }`. All the `setTimeout`
  bookkeeping is hidden behind that clean interface.
- **`useErrorFlash`** (`hooks/useErrorFlash.ts`) — a boolean that flips true then
  auto-resets after N ms. Used by `ControlBar` to flash "Action failed". Tiny, but
  it captures a reusable pattern (transient flag with cleanup).
- **`useStateLogger`** (`hooks/useStateLogger.ts`) — dev-only diffing logger.
- **`useGameSocket`** (`ws/useGameSocket.ts`) — the big one; the entire connection.

The lesson: when a component accumulates a cluster of `useState` + `useEffect` that
together model one concept (toasts, a flashing flag, a socket), lift it into a
custom hook. The component gets shorter and the logic gets testable and reusable.

### The Rules of Hooks (the part that bites you)

1. **Only call hooks at the top level** — never inside loops, conditions, or nested
   functions. React tracks hooks by call order; conditional calls desync that order.
2. **Only call hooks from React functions** — components or other hooks.

Notice how this shapes `ControlBar.tsx`: it computes `selectControls(...)` and calls
all its `useState`/`useErrorFlash` hooks **before** any of the `if (mode === ...)`
early returns. The hooks can't live inside the branches.

---

## 4. State, where it lives, and *why* (hoisting decisions)

You said the hoisting concept is clear — state lives at the lowest common ancestor of
the components that need it. Good. This section is the *why* behind each placement in
this app, since that's what you asked for. The recurring question is: **how far up
does this piece of state need to go, and should it even be React state at all?**

There are essentially four "altitudes" of state here:

### Altitude 1 — Local component state (lowest)

State only one component cares about stays inside it.

- `raiseValue`, `busy` in **`ControlBar`** — the slider position and in-flight flag
  matter to nobody else. No reason to hoist.
- `playerName`, `submitting` in **`JoinForm`** — pure form-local state.
- `secondsLeft` in **`TurnTimer`** — derived ticking display, local.
- `expandedId`, `votedSessions` in **`VotePopupLayer`** — *which* popup is expanded
  and *which* you've voted on is pure view state. The server doesn't care, siblings
  don't care, so it lives here and nowhere higher.

**Decision rule applied:** "Does any sibling or parent need to read or change this?"
If no → keep it local. Hoisting it would just add prop-drilling and coupling.

### Altitude 2 — Lifted to the nearest common parent

When two siblings need the same data, it moves up to their shared parent, which owns
it and passes it down.

- **`GameScreen`** owns the live game by calling `useGameSocket(tableId)`. The result
  (`gameState`, `bus`, `connectionState`, `toasts`) is needed by *several* children —
  `PixiPokerTable` needs the `bus`, `ControlBar` and `VotePopupLayer` need
  `gameState`, the banners need `connectionState`, `Toasts` needs `toasts`. Their
  lowest common ancestor is `GameScreen`, so the hook lives there and the values flow
  down as props. Putting the socket any lower would mean either duplicating the
  connection or lifting it later anyway.

- **`Toasts`** is a "dumb"/presentational component: it receives `toasts` and
  `onDismiss` as props and renders them. The *state* lives in the `useToasts` hook
  up in `useGameSocket`/`GameScreen`; the component just displays it. This separation
  (stateful owner up top, dumb renderer at the leaf) is a deliberate, repeated choice.

### Altitude 3 — React Context (cross-cutting, avoid prop-drilling)

When a value is needed by many components at different depths and threading it through
every intermediate as props would be painful, it goes in **Context**.

- **`SessionContext`** (`context/SessionContext.tsx`) holds identity: `tableId`,
  `myPlayerId`, `tableInfo`, plus `join`/`refresh` actions. This is consumed by
  `TableSessionGate`, `JoinForm`, and `GameScreen` — components at different levels
  that all need "who am I and what table". Rather than drilling `myPlayerId` through
  three layers, the provider wraps the subtree and any descendant calls `useSession()`.

  Study the shape — it's the canonical Context recipe:
  ```tsx
  const SessionContext = createContext<SessionContextValue | null>(null)

  export function SessionProvider({ tableId, children }) {
    const [tableInfo, setTableInfo] = useState(null)
    // ...fetch via REST, expose actions...
    const value = useMemo(() => ({ ... }), [deps])
    return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
  }

  export function useSession() {
    const ctx = useContext(SessionContext)
    if (!ctx) throw new Error('useSession must be used within a SessionProvider')
    return ctx
  }
  ```
  Two craftsmanship details: (1) the custom `useSession()` hook throws a helpful error
  if used outside the provider — far better than a cryptic null-deref later. (2) the
  `value` is `useMemo`'d (section 3) to avoid re-rendering every consumer needlessly.

**Why isn't the *game state* in Context too?** It could be — but it isn't, and that's
a real design decision. The live `gameState` updates many times per second during a
hand. Context propagation re-renders *all* consumers on every change. The game state
only has a handful of React consumers and they all happen to sit under `GameScreen`,
so plain props from `GameScreen` are simpler and cheaper than a second context. And
the high-frequency consumer (the Pixi canvas) deliberately bypasses React entirely
(next point). Context earns its place for *low-frequency, widely-needed* identity
data; it would be a poor fit for *high-frequency* game data.

### Altitude 4 — Deliberately *outside* React (the FrameBus)

This is the most interesting decision in the whole frontend, so it gets its own
section (6). The short version: the per-frame game stream that drives the canvas does
**not** live in React state at all. It flows through a hand-rolled pub/sub channel
(`game/frameBus.ts`) straight into the imperative Pixi renderer. React never
re-renders for it.

Why pull it *out* of React? Because re-rendering a React tree 10×/second to drive
GPU animation is both wasteful and racy (React batches updates; bursts of WebSocket
frames could collapse into one render and you'd lose intermediate animation steps).
By making the canvas a subscriber to a plain event bus, each frame is delivered
synchronously and in order. The comment in `frameBus.ts` even notes this killed an
old `flushSync` hack.

**The meta-lesson on hoisting:** "where does state live" has *four* answers here, not
one — local, lifted-to-parent, context, or out-of-React-entirely — and the right
answer depends on **who needs it** and **how often it changes**. Frequency is as
important an axis as scope.

---

## 5. The data layer: REST + WebSocket + types

Three files define the contract with the server. Read them as a unit.

### `src/api/types.ts` — the shared vocabulary

Every shape that crosses the wire is a TypeScript `interface`/`type` here:
`GameStateUpdate`, `PlayerView`, `ActionRequest`, vote types, etc. This file is the
*single source of truth for the data model* on the frontend. When the backend changes
a payload, you change it here and TypeScript shows you every call site that breaks.

Two functions at the bottom are doing real work, not just typing:

- **`isGameStateUpdate(frame)`** — a *type guard*. A WebSocket frame is either a
  `GameStateUpdate` or a `SimpleMessage` (`WsFrame = GameStateUpdate | SimpleMessage`).
  This function narrows the union:
  ```ts
  export function isGameStateUpdate(frame: WsFrame): frame is GameStateUpdate {
    return frame.type === 'game_state'
  }
  ```
  The `frame is GameStateUpdate` return type tells the compiler "inside an
  `if (isGameStateUpdate(f))` block, treat `f` as a `GameStateUpdate`". This is how
  `useGameSocket` cleanly splits game-state frames from toast messages.

- **`normalizeGameState(raw)`** — defends against a real backend quirk. The Kotlin
  server uses `kotlinx.serialization` with `encodeDefaults = false`, so fields equal
  to their default (empty arrays, `false`, `null`) are **omitted from the JSON
  entirely**. This function fills them back in:
  ```ts
  readyPlayerIds: raw.readyPlayerIds ?? [],
  activeVotes:    raw.activeVotes ?? [],
  isDealer:       p.isDealer ?? false,
  ```
  After normalization the rest of the app can trust the types — no `?.` paranoia
  everywhere. **Lesson:** sanitise external data once, at the boundary, so the
  interior stays clean.

### `src/api/client.ts` — typed REST wrappers

One private `request<T>()` helper does fetch + JSON + error handling; every endpoint
is a one-liner on top of it:

```ts
export function sendAction(tableId: number, req: ActionRequest) {
  return request<StatusMessage>('POST', `/api/tables/${tableId}/action`, req)
}
```

Things to absorb:

- `credentials: 'include'` on the fetch — this sends the JWT auth **cookie**
  automatically. There's zero token-juggling in the frontend; the browser handles it.
  (The CLAUDE.md note about `ALLOWED_ORIGIN` and CORS exists precisely because
  credentialed requests have strict cross-origin rules.)
- Errors are normalised into a custom `ApiError` carrying the HTTP status, so callers
  can `catch` a consistent shape.
- The generic `<T>` ties each endpoint to its response type, so `await sendAction(...)`
  is fully typed at the call site.

This is the "typed fetch wrapper" pattern: components never call `fetch` directly,
they call named, typed functions. Easy to mock in tests, easy to grep, impossible to
typo a URL silently.

### `src/ws/useGameSocket.ts` — the live connection

This single hook is the most production-hardened file in the app. Open it and read the
comments; they explain *why* for every guard. The headline responsibilities:

1. **Connect** a WebSocket to `/ws/tables/{tableId}` (auto-picking `ws:`/`wss:`).
2. **Heartbeat** — send a `ping` every 20s so proxies (Vite dev, Cloud Run) don't
   kill an "idle" socket.
3. **Reconnect with exponential backoff** — start at 1s, double up to 30s, give up
   after 10 tries and surface `'abandoned'` so the UI can prompt a reload.
4. **Tab-visibility handling** — if the tab is hidden for 60s, close the socket
   (so a forgotten tab stops keeping a Cloud Run instance billing-alive), and
   reconnect when foregrounded.
5. **Route incoming frames** — game states get diffed and pushed to the bus; other
   messages become toasts.

The frame-routing core is the part most relevant to the architecture:

```ts
ws.onmessage = (event) => {
  if (socket !== ws) return                       // ignore a superseded socket
  const frame = JSON.parse(event.data) as WsFrame
  if (isGameStateUpdate(frame)) {
    const next = normalizeGameState(frame)
    const prev = prevSnapshotRef.current
    const cold = prev === null                     // first frame after (re)connect
    const events = deriveEvents(prev, next, deriveCtxRef.current)
    prevSnapshotRef.current = next
    bus.emit({ state: next, events, cold })        // → Pixi world
    setGameState(next)                             // → React world
  } else {
    addToast(frame.type, frame.message)            // → Toasts
  }
}
```

This is the fork in the road from section 1: **every game frame is sent to *both*
worlds** — `bus.emit(...)` to the canvas, `setGameState(next)` to React. And both
get the *same normalized snapshot*, so they never disagree about reality.

A few subtle guards worth understanding because they teach real-world async hygiene:

- **`if (socket !== ws) return`** appears in every handler. When a reconnect creates a
  new socket, the *old* socket's late-firing `onclose`/`onmessage` must not touch
  shared state. Capturing `ws` locally and comparing against the current `socket` is
  how you neutralise a stale connection's callbacks.
- **`cold` / `prevSnapshotRef.current = null`** — after a (re)connect, the first
  snapshot describes "what is", not "what just changed". Diffing it against a
  pre-disconnect snapshot would produce phantom animations (a card "dealing" that was
  already on the table). Marking it cold tells the renderer to paint it statically.
- **The cleanup function** returns and tears down *everything* — socket, heartbeat,
  reconnect timer, visibility listener. This is `useEffect` cleanup discipline at
  full scale.

---

## 6. The event pipeline: snapshot vs. events (the key idea)

If you internalise one section, make it this one. It's the project's defining idea and
it's genuinely reusable beyond this app.

### The problem

The server sends **full snapshots** — "here is the complete state of the table right
now". Snapshots are great for rendering *what things are*: this player has 1200 chips,
the pot is 300, it's Alice's turn. You just read the latest snapshot and draw it.

But snapshots are terrible for *what just happened*. A chip-flying-to-the-pot
animation isn't a property of any single snapshot — it's the *transition* between two
of them (player's bet went from 0 to 50). If you only ever look at the current
snapshot, you can't tell "the pot is 300" from "the pot just became 300, animate it".

### The solution: derive events by diffing consecutive snapshots

The app computes, on each frame, the list of **semantic events** that occurred between
the previous snapshot and the new one. Two snapshots in → a list of events out.

```
prev snapshot  ─┐
                ├─►  deriveEvents()  ─►  [round_started, player_bet, pot_changed, ...]
next snapshot  ─┘
```

This split is stated explicitly in **`game/events.ts`**:

> Semantic facts about *what just happened* … the single source of truth for transient
> effects (animations, sounds, toasts) — anything that fires **once** rather than
> reflecting steady-state. Steady-state ("what things are": chip counts, badges,
> halos, the pot amount) is rendered directly from the latest snapshot instead.

So there are two rendering disciplines, and every visual belongs to exactly one:

| | Source | Examples | Re-applying is… |
|---|---|---|---|
| **Steady-state** | latest snapshot | chip counts, badges, turn halo, pot pyramid, community cards present | idempotent (a no-op) |
| **Transient** | derived events | deal animation, flying chip, card flip, winner chips, floating ±delta | fires once |

### The pieces

**`game/events.ts`** — the `GameEvent` union type and the `DeriveContext`:

```ts
export type GameEvent =
  | { kind: 'round_started' }
  | { kind: 'community_revealed'; added: string[]; total: number }
  | { kind: 'player_bet'; playerId: number; amount: number }
  | { kind: 'turn_changed'; playerId: number | null }
  | { kind: 'pot_changed'; from: number; to: number }
  | { kind: 'chips_changed'; playerId: number; delta: number }
  | { kind: 'player_status_changed'; playerId: number; status: PlayerStatus }
  | { kind: 'showdown'; hands: Record<number, BestHand>; winnerIds: number[] }
  | { kind: 'pot_awarded'; winners: { playerId: number; delta: number }[] }
  | { kind: 'game_paused' }
  | { kind: 'game_resumed' }
```

This is a **discriminated union** — every variant has a `kind` literal, so a
`switch (event.kind)` lets TypeScript narrow each branch to exactly the right fields.
You'll see that switch in the renderer's `handleEvent`.

`DeriveContext` holds the one thing a single diff can't know: each player's chip count
*at the start of the hand*, needed to compute who won and by how much. It persists
across frames (one per connection) and is mutated in place by the deriver.

**`game/deriveEvents.ts`** — the *only* place the app diffs game state. Pure except
for bookkeeping on `ctx`. The logic reads naturally:

```ts
// New hand → deal animation.
if (prev.roundStage == null && next.roundStage === 'BET_BLINDS')
  events.push({ kind: 'round_started' })

// Acting player changed.
if (prev.nextPlayerIdToAct !== next.nextPlayerIdToAct)
  events.push({ kind: 'turn_changed', playerId: next.nextPlayerIdToAct })

// Community cards added (flop/turn/river).
if (next.communityCards.length > prev.communityCards.length)
  events.push({ kind: 'community_revealed', added: next.communityCards.slice(prev.communityCards.length), total: next.communityCards.length })

// Per-player bet increases → flying chip.
for (const p of next.players)
  if ((prevBets.get(p.id) ?? 0) < p.currentBet)
    events.push({ kind: 'player_bet', playerId: p.id, amount: p.currentBet - (prevBets.get(p.id) ?? 0) })
```

And the cold-start guard at the top:

```ts
if (prev === null) {
  if (next.gameStatus === 'RUNNING') ctx.roundStartChips = chipMap(next)
  return []          // a cold snapshot animates nothing
}
```

This file has a corresponding unit test (`deriveEvents.test.ts`) — because it's pure
(snapshot, snapshot) → events, it's trivial to test without any React or Pixi. That
testability is a direct payoff of isolating the diff logic.

**`game/frameBus.ts`** — the delivery channel. A `Frame` bundles the three things a
subscriber needs:

```ts
interface Frame {
  state: GameStateUpdate   // the snapshot (apply this)
  events: GameEvent[]      // what changed (play these)
  cold: boolean            // first frame after connect? (apply, don't animate)
}
```

The bus itself is ~20 lines of hand-rolled synchronous pub/sub:

```ts
export function createFrameBus(): FrameBus {
  const listeners = new Set<FrameListener>()
  let last: Frame | null = null
  return {
    get last() { return last },
    emit(frame) { last = frame; for (const l of listeners) l(frame) },
    subscribe(listener) {
      listeners.add(listener)
      if (last) listener({ ...last, cold: true })   // replay last frame to latecomers
      return () => { listeners.delete(listener) }
    },
  }
}
```

Two design choices to appreciate:

- **Synchronous `emit`** — calls every listener immediately, in order. No React
  batching, no `flushSync`. When ten frames arrive in a burst, the renderer sees ten
  clean apply-then-events cycles, not one collapsed mega-update.
- **Replay-last-as-cold on subscribe** — the Pixi component might finish initialising
  *after* the first frame already arrived. Replaying `last` (flagged `cold`) means a
  late-mounting renderer instantly catches up to current reality without animating a
  history it missed.

### Putting it together (the data's full journey)

```
WebSocket frame
   │  JSON.parse
   ▼
normalizeGameState(raw)            ← fill in omitted defaults
   │
   ├─ deriveEvents(prev, next) ──► events[]
   │
   ▼
bus.emit({ state, events, cold })
   │                                    setGameState(next)
   ▼                                          │
Pixi subscriber:                              ▼
  applySnapshot(state, cold)            React re-renders
  for (e of events) handleEvent(e)      (ControlBar, banners, popups, timer)
```

The same normalized snapshot drives both worlds. The Pixi world *additionally*
consumes the derived events for animation. React doesn't need the events because DOM
chrome is all steady-state.

---

## 7. Selectors: deriving UI from state

Before we leave the data world for Pixi, one more pattern: **selectors** — pure
functions that turn raw state into exactly what a component needs to render.

**`game/selectors.ts`** has `selectControls(state, myPlayerId)`. The `ControlBar` has
a genuinely fiddly job: should it show Ready-Up buttons? Fold/Call/Raise? Nothing
(spectating)? "I'm back" (idle)? And if raising, what are the min/max legal amounts?
All of that is *derived* from the snapshot. Rather than tangle that logic into the
component's JSX, it's a pure function:

```ts
export function selectControls(state: GameStateUpdate, myPlayerId: number): Controls {
  const me = state.players.find((p) => p.id === myPlayerId)
  const roundInProgress = state.roundStage != null
  const isMyTurn = roundInProgress && state.nextPlayerIdToAct === myPlayerId
  // ...amountToCall, minRaise, maxRaiseOnTop, canRaise...

  const mode: ControlMode =
    roundInProgress && myChips === 0 ? 'spectating'
    : me?.status === 'IDLE' ? 'idle'
    : !roundInProgress ? 'lobby'
    : 'acting'

  return { mode, isMyTurn, amIReady, isGameOver, amountToCall, minRaise, maxRaiseOnTop, canRaise }
}
```

The component then just reads `mode` and renders the matching branch. Benefits:

- **Testable** — `selectors.test.ts` exercises this with plain objects, no rendering.
- **Readable component** — `ControlBar` becomes "compute controls, then four `if`
  branches", not a thicket of inline boolean logic.
- **Order-sensitive branch precedence** is documented and centralised (a 0-chip
  player mid-hand is `spectating` even if also `IDLE` — see the comment).

This mirrors the snapshot/event split: `selectControls` is the steady-state
counterpart for the *React* side, exactly as `deriveEvents` is the transient side for
the *Pixi* side. Both are pure functions of the snapshot, both are unit-tested, both
keep their consumers dumb.

---

## 8. Pixi.js from zero

Now the part that's new to you. Pixi.js is a 2D rendering library that draws to a
`<canvas>` using WebGL (the GPU), falling back to 2D canvas. It exists to do what the
DOM can't do well: draw and animate thousands of sprites at 60fps. You give it a tree
of display objects; it paints them every frame.

### The five concepts you need

1. **`Application`** — the engine. It owns the canvas, the WebGL renderer, and a
   ticker (the render loop). You create one and `await app.init({...})`.

2. **`Container`** — an invisible grouping node. It has a position, scale, rotation,
   and alpha, and it holds children. Transforms cascade: move a container and all its
   children move. This is the scene-graph node — think of it like a `<div>` for the
   canvas. The whole table is a tree of containers.

3. **`Graphics`** — a vector drawing surface. You issue drawing commands
   (`.circle()`, `.roundRect()`, `.moveTo().lineTo()`, `.fill()`, `.stroke()`) and it
   rasterises them. The felt, the avatars, the halos, the badge backgrounds — all
   `Graphics`.

4. **`Text`** — a text display object with a `TextStyle` (font, size, fill). Player
   names, chip counts, "Your turn", card ranks.

5. **`Ticker`** — the heartbeat. It calls your callback ~60×/second with the elapsed
   milliseconds (`deltaMS`). Every animation advances inside a ticker callback. This
   is the imperative render loop — utterly unlike React's "describe state, let React
   paint" model.

### The scene graph in this project

`PixiPokerTable.tsx` builds the tree once on init:

```tsx
const app = new Application()
await app.init({ width: LW, height: LH, backgroundAlpha: 0, antialias: true })

const root = new Container()
root.position.set(LW / 2, LH / 2)   // origin at canvas center
app.stage.addChild(root)

root.addChild(drawFelt())            // the green table (a Graphics)

// Ordered layers — later children paint on top:
const emptySeatsLayer = new Container()
const seatsLayer      = new Container()
const miniCardsLayer  = new Container()
const potContainer    = new Container()
const communityRow    = new Container()
const myCardsContainer= new Container()
const animLayer       = new Container()
root.addChild(emptySeatsLayer, seatsLayer, miniCardsLayer, potContainer,
              communityRow, myCardsContainer, animLayer)
```

Key Pixi facts visible here:

- **`app.stage`** is the root of everything Pixi renders. You add your `root` to it.
- **Z-order = child order.** `animLayer` is added last so flying chips and dealt cards
  render on top of seats and the pot. There's no `z-index`; you control layering by
  the order you add children (and which container they live in).
- **The coordinate system** is "logical" — the scene is authored in an 800×560 space
  (`LW`/`LH` in `layout.ts`) with the origin moved to the center via
  `root.position.set(LW/2, LH/2)`. So a seat at `x: -100` is left of center. The whole
  thing is then *scaled* to fit the viewport in `doResize` — author once at a fixed
  size, scale uniformly to any screen.

### Building a drawing: the felt

`drawFelt()` is the simplest complete example of `Graphics`:

```tsx
function drawFelt(): Graphics {
  const g = new Graphics()
  const W = FELT_W, H = FELT_H, R = H / 2, RAIL = 20
  g.roundRect(-W/2-RAIL, -H/2-RAIL, W+RAIL*2, H+RAIL*2, R+RAIL).fill({ color: 0x5b3a22 }) // wooden rail
  g.roundRect(-W/2, -H/2, W, H, R).fill({ color: 0x0c2f1d })                              // dark felt
  g.roundRect(-W/2+8, -H/2+8, W-16, H-16, R-8).fill({ color: 0x1f6b45 })                  // lighter inset
  g.roundRect(-W/2, -H/2, W, H, R).stroke({ color: 0x000000, alpha: 0.35, width: 10 })    // shadow edge
  return g
}
```

Read the Pixi v8 idiom: **command, then style**. `.roundRect(x, y, w, h, radius)`
defines a shape; the chained `.fill({...})` or `.stroke({...})` paints it. Colors are
hex numbers (`0x1f6b45`), not CSS strings. Coordinates are negative-to-positive around
the local origin because this Graphics lives in `root`, which is centered.

### Positioning on the table: the "stadium" math

Seats aren't on a circle — a poker table is a *stadium* (rectangle with semicircular
ends). `layout.ts` has `pointOnStadium(geometry, fraction)` which walks the perimeter:
`fraction = 0` is bottom-center and it sweeps clockwise. `slotPosition(...)` wraps it
with a crucial UX trick:

```ts
const displayed = (slot - mySlot + seatCount) % seatCount
```

This rotates the seating so **you are always at the bottom**, with opponents arranged
around the far side — exactly like sitting down at a real table. Three "stadium rings"
of different sizes (`SEAT_STADIUM`, `CARD_STADIUM`, felt) place avatars outside the
felt, mini-cards just inside it, etc. This is pure trig, fully decoupled from Pixi — a
nice example of keeping geometry logic out of the rendering code.

### Why a `<div ref>` wrapper instead of Pixi's canvas directly

```tsx
return <div ref={containerRef} style={{ position: 'fixed', inset: 0 }} />
```

React owns this empty `<div>`. Pixi creates *its own* canvas and the effect appends it
into the div. React never touches the canvas's insides — it's a black box from React's
perspective. The comment explains *why Pixi makes its own canvas* rather than reusing
a React-rendered one: in StrictMode the effect runs twice, and two `app.init()` calls
racing on the *same* canvas element cause WebGL "context lost" errors. Letting Pixi
mint a fresh canvas per init sidesteps that. This is the seam between the two worlds.

---

## 9. The React⇄Pixi bridge

`PixiPokerTable.tsx` is where React and Pixi meet. The entire bridge is **one
`useEffect`**, and understanding it teaches you both the Pixi lifecycle and advanced
effect discipline.

### The lifecycle effect

```tsx
useEffect(() => {
  const container = containerRef.current
  if (!container) return

  const app = new Application()
  let destroyed = false

  app.init({ ... }).then(() => {
    if (destroyed) { app.destroy(); return }   // unmounted during async init
    // ...build canvas, scene graph, ticker, subscribe to bus...
    cleanupRef.current = () => { /* unsubscribe, remove listeners, destroy app */ }
  })

  return () => {
    destroyed = true
    cleanupRef.current?.()
    cleanupRef.current = null
    sceneRef.current = null
  }
}, [bus])
```

Why each piece exists — these are the real-world gotchas of bridging an imperative
library into React:

- **`app.init()` is async**, but `useEffect` cleanup is sync. If the component
  unmounts *while init is still pending* (very real in StrictMode, where mount→unmount
  →mount happens immediately), the cleanup runs first with nothing to clean, then init
  resolves into a destroyed component. The `destroyed` flag + `cleanupRef` indirection
  bridges that gap: the cleanup sets `destroyed = true`; when init finally resolves it
  checks the flag and bails (`app.destroy()`), and if it *did* finish first, it parked
  the real teardown in `cleanupRef` for the cleanup to call.

- **`[bus]` as the only dependency** — the effect should run once for the lifetime of
  the connection. But `myPlayerId` and `maxPlayers` are also props the effect needs.
  Putting them in the dep array would tear down and rebuild the entire Pixi app
  whenever they change — catastrophic. Instead they're mirrored into refs
  (`myPlayerIdRef`, `maxPlayersRef`) updated by a *separate* tiny effect, and the
  long-lived Pixi callbacks read `myPlayerIdRef.current`. This is the "latest-value
  ref" pattern from section 3, applied to keep a once-only effect fresh.

- **`sceneRef`** holds the entire mutable Pixi scene (`SceneState`). It's a ref, not
  state, because mutating the scene must *never* trigger a React render — the canvas
  paints itself via its ticker. React's job ends at "the canvas exists".

### Where the two worlds actually touch

Exactly one line connects the data pipeline to the canvas:

```tsx
const unsubscribe = bus.subscribe((frame) => {
  const s = sceneRef.current
  if (!s) return
  s.myPlayerId = myPlayerIdRef.current
  s.maxPlayers = maxPlayersRef.current
  applySnapshot(s, frame.state, frame.cold)
  if (!frame.cold) for (const event of frame.events) handleEvent(s, event)
})
```

That's the whole bridge: **subscribe to the bus, apply the snapshot, then play the
events.** React state never enters this path. When `subscribe` is called it
immediately replays the last frame as cold (section 6), so even if Pixi finished
initialising after the first frame arrived, it catches up instantly.

The ticker, set up earlier, drives animation independently:

```tsx
const ticker = new Ticker()
ticker.add((t) => { if (sceneRef.current) tick(sceneRef.current, t.deltaMS) })
ticker.start()
```

So there are **two clocks**: the bus (event-driven, fires on each server frame) mutates
*what should be animating*; the ticker (time-driven, ~60fps) advances those animations
frame by frame. The next section is how those interact.

---

## 10. Inside the renderer: apply / handle / tick

Three functions, three responsibilities, mapping directly onto the snapshot/event
split from section 6.

### `applySnapshot(scene, state, cold)` — steady state, idempotent

Renders "what things are". Re-applying the same snapshot is a no-op, which is what
makes reconnects safe. It does **no diffing** — it just makes the scene match the
snapshot:

```tsx
function applySnapshot(scene, state, cold) {
  scene.lastApplied = state
  updatePot(scene, state.potTotal, state.roundStage)

  if (state.communityCards.length === 0) {
    if (scene.shownCommunity.length > 0) renderCommunity(scene, [], [])   // clear
  } else if (cold) {
    renderCommunity(scene, state.communityCards, state.communityCards)    // instant
  }
  refreshSeats(scene)
}
```

Note how `cold` changes behaviour: on a cold frame the community cards are rendered
*instantly* (you reconnected mid-hand; the flop is already out). On warm frames the
cards arrive via the `community_revealed` *event* instead, which animates them.

`refreshSeats` (in `seats.ts`) rebuilds each player's seat from the snapshot — chips,
badges, status, halos. It's the steady-state workhorse, called both here and after
animations settle. `scene.lastApplied` is stashed so event handlers can read current
geometry (where is player 5 sitting?) when they fire.

### `handleEvent(scene, event)` — transient effects

The discriminated-union switch from section 6, now consuming events to *start*
animations:

```tsx
function handleEvent(scene, event) {
  switch (event.kind) {
    case 'round_started':
      scene.winnerPlayerIds.clear()
      scene.retainedShowdownHands.clear()
      startDeal(scene)            // launch the dealing animation
      refreshSeats(scene)
      break
    case 'community_revealed':
      renderCommunity(scene, scene.lastApplied!.communityCards, scene.shownCommunity)
      break
    case 'player_bet': {
      const pos = seatPos(scene, event.playerId)
      spawnFlyingChip(scene, pos.x, pos.y)     // chip flies from seat to pot
      break
    }
    case 'chips_changed': { /* spawnDelta — floating +/- number */ }
    case 'showdown':       { /* retain best hands, mark winners, refresh */ }
    case 'pot_awarded':    { /* spawnWinnerChips from pot to winner */ }
    case 'turn_changed': case 'pot_changed': case 'player_status_changed':
    case 'game_paused':  case 'game_resumed':
      break   // already fully covered by applySnapshot — nothing transient to do
  }
}
```

The empty cases are instructive: `turn_changed` needs no transient effect because the
gold turn-halo is *steady state* — `applySnapshot`→`refreshSeats` already moved it to
the new player based on `nextPlayerIdToAct`. The event exists for completeness (and
future use like a sound), but the visual is handled by the snapshot path. This is the
discipline paying off: each visual lives in exactly one path, and you can always
answer "is this thing steady-state or transient?".

Note that `handleEvent` only *starts* animations (spawns sprites, sets phases). It
never waits for them. The actual frame-by-frame motion is the ticker's job.

### `tick(scene, dt)` — the per-frame animation loop

Called ~60×/second with `dt` = milliseconds since last frame. It walks every active
animation and advances it by `dt`. This is classic game-loop code:

```tsx
// Deal cards: fly from center to their seat, fading in.
for (const dc of scene.dealCards) {
  if (dc.done) continue
  dc.elapsed += dt
  const t = dc.elapsed - dc.delay
  if (t <= 0) continue
  const p = Math.min(t / DEAL_FLIGHT_MS, 1), e = easeOut(p)   // progress 0→1, eased
  dc.sprite.x = lerp(0, dc.toX, e)
  dc.sprite.y = lerp(0, dc.toY, e)
  dc.sprite.alpha = p < 0.12 ? p / 0.12 : 1
  if (p >= 1) dc.done = true
}
```

The universal animation idiom here:

1. accumulate `elapsed += dt`,
2. compute normalized progress `p = elapsed / duration` clamped to `[0,1]`,
3. ease it: `e = easeOut(p)` (so motion decelerates naturally),
4. interpolate the property: `value = lerp(start, end, e)`,
5. mark `done` when `p >= 1`, and prune done items.

`lerp`, `easeIn`, `easeOut` live in `tween.ts` — `easeOut(t) = 1 - (1-t)³`. The same
pattern drives community-card slide/flip, flying chips, winner chips, floating deltas.

The loop also handles two *continuous* (non-finishing) effects:

```tsx
// Rolling chip counter — eases the displayed number toward the real value.
if (Math.abs(s.chipsDisplayValue - s.chipsValue) >= 0.5) {
  const factor = 1 - Math.pow(0.85, dt / 16.67)
  s.chipsDisplayValue += (s.chipsValue - s.chipsDisplayValue) * factor
  s.chipsText.text = `${Math.round(s.chipsDisplayValue)} chips`
}
// Turn-halo pulse — sine wave on alpha.
if (s.isNextToAct && s.turnHaloGfx.visible)
  s.turnHaloGfx.alpha = 0.6 + 0.4 * Math.sin(now * Math.PI * 2)
```

The chip counter is a nice trick: `applySnapshot` sets the *target* (`chipsValue`),
and the ticker eases the *displayed* value toward it every frame — frame-rate
independent thanks to `Math.pow(0.85, dt/16.67)`. Snapshot sets the goal, ticker
animates to it. That's the snapshot/ticker collaboration in miniature.

### The `tween` helper — declarative one-offs

For simple "fade this in over 400ms" cases, writing a manual tick entry is overkill.
`tween.ts` provides a pool:

```tsx
tween(scene, entry.miniCards, { alpha: 1 }, 400)   // fade to alpha 1 over 400ms
tween(scene, dc.sprite, { alpha: 0 }, 400, 0, () => scene.animLayer.removeChild(dc.sprite))
```

You give it a target object, the props to animate to, a duration, optional delay, and
optional `onDone` callback. `tickTweens(scene, dt)` (called at the end of `tick`)
advances them all and runs `onDone` when finished — the `onDone` above removes the
sprite from the scene once it's faded out. It's a tiny home-grown animation engine, ~25
lines, sufficient for everything the bespoke per-frame code doesn't cover.

### One genuinely tricky Pixi lesson: the halos

`drawSeat.ts` has a long comment about why the turn/win halos are drawn **once** and
only toggled via `.visible`, never cleared and redrawn:

> Called ONCE per halo object at build time — the content is never cleared or redrawn,
> which prevents the stale-texture artefact that appeared when clear()+redraw changed
> the graphics bounds mid-session and the filter's backing texture retained old pixels.

The halos use a `BlurFilter` to get a soft glow. Filters render to an offscreen
texture sized to the graphic's bounds. If you `clear()` and redraw a `Graphics` at a
different size, the filter's cached texture can retain old pixels — a ghosting bug.
The fix: draw each halo shape once at build time, and animate only `.visible`/`.alpha`.
This is the kind of hard-won, library-specific knowledge you can't get from docs — note
how it's preserved in a comment right where someone would otherwise "simplify" it and
reintroduce the bug.

---

## 11. Styling with Emotion

The React/DOM components are styled with **Emotion** (`@emotion/styled`) — CSS-in-JS.
You write styled components: a tag plus a template literal of CSS.

```tsx
// ControlBar.tsx
const Bar = styled.div`
  position: fixed;
  bottom: 0; left: 0; right: 0;
  background: ${gradient.panel};
  border-top: 2px solid ${palette.bronze};
  display: flex; align-items: center; justify-content: center;
`
```

`Bar` is now a real React component you use as `<Bar>...</Bar>`; Emotion generates a
unique class name and injects the CSS. Things to notice in this codebase:

- **Theme tokens** come from `src/theme.ts` (`palette`, `gradient`, `hex`). Colours are
  centralised so the felt-and-gold casino look stays consistent — and `hex` exposes the
  same colours as numbers for Pixi (`hex.gold`), so DOM and canvas share one palette.

- **Props drive styles.** Styled components can take props and branch:
  ```tsx
  const Button = styled.button<{ variant?: 'danger' | 'primary' }>`
    background: ${(p) => p.variant === 'danger' ? gradient.danger
                       : p.variant === 'primary' ? gradient.gold
                       : gradient.green};
  `
  ```
  Then `<Button variant="danger">Fold</Button>`. This is how `ControlBar` themes its
  Fold/Call/Raise buttons differently from one styled definition.

- **`shouldForwardProp`** — the raise slider's `RangeInput` uses
  `styled('input', { shouldForwardProp: (p) => p !== 'fillPercent' })` so the custom
  `fillPercent` styling prop is consumed by Emotion and *not* leaked onto the real DOM
  `<input>` (which would warn about an unknown attribute). A small but real detail when
  you make style-only props.

- **`framer-motion`** handles enter/exit animations for DOM elements (toasts, vote
  popups). `<AnimatePresence>` keeps a component mounted long enough to play its `exit`
  animation when it's removed from the list:
  ```tsx
  // Toasts.tsx
  <AnimatePresence>
    {toasts.map((t) => (
      <Bubble key={t.id} initial={{opacity:0, y:10}} animate={{opacity:1, y:0}} exit={{opacity:0, y:-10}} />
    ))}
  </AnimatePresence>
  ```
  This is the DOM-side equivalent of the Pixi tweening — but here a library does it
  because DOM animation is well-trodden, whereas the canvas hand-rolls its own.

---

## 12. Component-by-component reference

A quick index so you can navigate. Each entry: what it does, what state it owns, and
the one thing worth learning from it.

| File | Role | Owns | Learn from it |
|---|---|---|---|
| `main.tsx` | Boot | — | `createRoot` + StrictMode |
| `App.tsx` | Routing + error net | — | Router setup; ErrorBoundary placement |
| `ErrorBoundary.tsx` | Catch render crashes | `hasError` | The one needed class component; `getDerivedStateFromError` |
| `pages/HomePage.tsx` | Create/landing | local form | (entry UX) |
| `pages/TablePage.tsx` | URL → session gate | — | `useParams`, input validation, provider placement |
| `context/SessionContext.tsx` | Identity context | `tableInfo`, `loading`, `error` | The full Context recipe + `useMemo`'d value |
| `pages/JoinForm.tsx` | Join a table | `playerName`, `submitting` | Controlled form input; calling a context action |
| `pages/GameScreen.tsx` | Compose the live game | — (consumes the socket) | Composition root; where the socket is lifted to |
| `ws/useGameSocket.ts` | The connection | `gameState`, `connectionState`, refs | Production WebSocket lifecycle in one effect |
| `game/deriveEvents.ts` | Snapshot diff → events | (mutates ctx) | Pure diff logic; the heart of the pipeline |
| `game/events.ts` | Event types | — | Discriminated unions |
| `game/frameBus.ts` | Pub/sub channel | `last` | Decoupling React from the renderer |
| `game/selectors.ts` | State → control UI | — | Pure selector pattern |
| `components/ControlBar/ControlBar.tsx` | Action bar | `raiseValue`, `busy`, flash | Mode-branching UI; `withBusy` async wrapper |
| `components/ControlBar/VotingMenu.tsx` | Open votes | local menu state | Calling vote endpoints |
| `components/TurnTimer/TurnTimer.tsx` | Countdown | `secondsLeft` | The textbook `setInterval` effect |
| `components/VotePopup/VotePopupLayer.tsx` | Vote popups | `expandedId`, `votedSessions` | "Adjust state during render" pattern |
| `components/Toasts.tsx` | Transient messages | — (props) | Presentational component + AnimatePresence |
| `hooks/useToasts.ts` | Toast state+timers | `toasts` | Custom hook encapsulating timers |
| `hooks/useErrorFlash.ts` | Transient flag | `failed` | Smallest reusable hook |
| `hooks/useStateLogger.ts` | Dev diff log | prev ref | Effect with no dep array (runs every render) |
| `components/PixiTable/*` | The canvas world | `SceneState` (ref) | All of section 8–10 |

Two components deserve a closer note because they show non-obvious React technique:

**`VotePopupLayer.tsx` — "adjusting state during render".** When the set of live votes
changes, it prunes its bookkeeping (`votedSessions`, `expandedId`) so those sets can't
grow forever. It does this **during render**, not in a `useEffect`:

```tsx
const activeIds = gameState.activeVotes.map((v) => v.sessionId).join(',')
const [prevActiveIds, setPrevActiveIds] = useState(activeIds)
if (activeIds !== prevActiveIds) {
  setPrevActiveIds(activeIds)
  // ...prune votedSessions / expandedId to only live votes...
}
```

This is a documented React pattern (the "store-previous-prop / derive-during-render"
idiom) and it's *preferred* over an effect here: calling a setter during render makes
React immediately re-run the render with the new state **before** painting, avoiding a
flash of stale UI and the extra commit an effect would cause. Reach for it when state
needs to *react to a prop change synchronously*.

**`ControlBar.tsx` — the `withBusy` wrapper.** Every action (fold, call, ready, etc.)
shares the same async ceremony: disable buttons, await the request, flash an error on
failure, re-enable. Rather than repeat that, one helper wraps it:

```tsx
async function withBusy(fn: () => Promise<unknown>, onSuccess?: () => void) {
  setBusy(true)
  try { await fn(); onSuccess?.() }
  catch { showError() }
  finally { setBusy(false) }
}
const handleAction = (type, value) =>
  withBusy(() => sendAction(tableId, { type, value }), () => setRaiseValue(0))
```

DRY async handling — the kind of small abstraction that keeps event handlers readable.

---

## 13. Patterns worth stealing

The transferable lessons, distilled:

1. **Separate "what is" from "what happened."** Snapshots for steady state, derived
   events for transient effects. This cleanly answers "where does this visual come
   from?" for every single element. (sections 6, 10)

2. **Sanitise external data at the boundary, once.** `normalizeGameState` fixes the
   server's omitted-defaults quirk so the entire interior can trust its types.

3. **Pure functions for derivations, unit-tested in isolation.** `deriveEvents` and
   `selectControls` are pure `(state) → output`; both have `.test.ts` files and need no
   React/Pixi to test. Push logic out of components into pure functions.

4. **Choose state's home by *scope × frequency*.** Local → lifted → context →
   out-of-React. High-frequency game data deliberately bypasses React via the FrameBus;
   low-frequency identity data sits in Context. (section 4)

5. **Bridge imperative libraries through one well-guarded effect.** The Pixi effect
   handles async init, StrictMode double-mount, and stale-prop capture with a
   `destroyed` flag, `cleanupRef`, and latest-value refs. (section 9)

6. **Keep style tokens in one place, shared across renderers.** `theme.ts` serves both
   Emotion (`palette`) and Pixi (`hex`).

7. **Custom hooks for stateful clusters.** When `useState`+`useEffect` pile up around
   one concept, extract a `useX`. (`useToasts`, `useGameSocket`)

8. **Preserve hard-won library knowledge in comments.** The halo stale-texture note,
   the "Pixi makes its own canvas" note, the cold-frame rationale — these stop the next
   person (or you, in six months) from "simplifying" a bug back in.

---

## 14. Exercises

Hands-on tasks, roughly increasing in difficulty. Each touches a different layer.

1. **Read the logs.** Run the app (`npm run dev` + the backend), open the console, play
   a hand. `useStateLogger` prints every state transition. Match each log line to the
   event `deriveEvents` would have produced for it. This wires sections 5–6 into your
   head fast.

2. **Add a toast on pause.** When `game_paused` fires, show a toast. Decide: does this
   belong in `deriveEvents` (it already emits the event), in `handleEvent` (Pixi side),
   or in a React effect watching `gameState.gameStatus`? Justify the altitude using
   section 4's reasoning, then implement the cheapest correct one.

3. **A new transient effect.** Add a brief "FOLD" label that floats up from a player's
   seat when they fold. You'll need: a new `player_folded` event in `events.ts`, the
   diff in `deriveEvents.ts` (a player's `isActive` went `true→false` mid-hand), a case
   in `handleEvent` that spawns a label (model it on `spawnDelta` in `chips.ts`), and a
   ticker entry if `spawnDelta`'s float doesn't already cover it. This exercise alone
   exercises the *entire* pipeline end to end.

4. **A new steady-state element.** Show each player's win count as a small badge under
   their name. This is pure snapshot → `drawSeat`/`updateSeat` work; no events involved.
   Contrast how much simpler it is than #3 — that contrast *is* the snapshot/event
   distinction made tangible.

5. **Make the raise slider snap to pot-fractions.** In `ControlBar`, add ½-pot and
   pot-size quick-raise buttons. You'll read `gameState.potTotal` and the `Controls`
   from `selectControls`, and call `handleAction('RAISE', amount)`. Pure React/DOM.

6. **Refactor a hook.** Extract the connection-status banners logic out of
   `GameScreen` into a `useConnectionBanner(connectionState, gameStatus)` hook that
   returns the banner to show (or null). Notice what does and doesn't belong in the
   hook — this builds the instinct for #7 in section 13.

7. **Trace a reconnect.** Kill the backend mid-hand, watch `useGameSocket` reconnect
   (console logs), bring it back. Explain *why* the first post-reconnect frame doesn't
   replay the whole hand as animation. (Answer: `cold`. See sections 5–6, 10.) Then
   read `frameBus.subscribe`'s replay-as-cold line and explain how a late-mounting Pixi
   canvas catches up.

---

*Where to go next:* the corresponding backend lives in `server-gcp/` and `poker-engine/`,
and the data contract you learned in `api/types.ts` is the exact mirror of the Kotlin
`GameStateUpdate` the server emits. The project's `CLAUDE.md` documents the backend
architecture and the single-write-path persistence model if you want to follow a player
action all the way from this `ControlBar` button to Firestore and back.
```
