# How to Run the Game Locally

Running the game locally means starting two processes: the **backend** (Ktor
server) and the **frontend** (Vite dev server). Open two terminals.

## Prerequisites

- JDK 21
- Node.js (with `npm`)

## 1. Start the backend

From the repository root:

```bash
./gradlew :server-gcp:run
```

This starts the HTTP + WebSocket server on **http://localhost:8080**.

> If port 8080 is already in use, pick another port and tell the frontend where
> the backend is (see step 2):
>
> ```bash
> PORT=18080 ./gradlew :server-gcp:run
> ```

The dev server uses in-memory persistence and an in-memory task scheduler, so no
Firestore or Cloud Tasks setup is required for local play.

## 2. Start the frontend

In a second terminal:

```bash
cd frontend
npm install     # first time only
npm run dev
```

Vite serves the app (default **http://localhost:5173**) and proxies `/api` and
`/ws` to `localhost:8080`.

> If you started the backend on a non-default port, point the proxy at it:
>
> ```bash
> BACKEND_PORT=18080 npm run dev
> ```

## 3. Play

Open the URL Vite prints (e.g. **http://localhost:5173**) in your browser.
Open a second browser window/profile to join as another player at the same
table.

## Running tests (optional)

```bash
# Backend / engine (Kotlin)
./gradlew test

# Frontend E2E (needs BOTH backend and Vite dev server running)
cd frontend
npm run test:e2e
```
