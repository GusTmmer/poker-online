import { request } from '@playwright/test'

export default async function globalSetup() {
  const ctx = await request.newContext({
    baseURL: `http://localhost:${process.env.BACKEND_PORT ?? '8080'}`,
  })
  try {
    await ctx.get('/api/tables', { timeout: 5_000 })
  } catch {
    throw new Error(
      'Backend is not running. Start it first:\n  ./gradlew :server-gcp:run\n  or\n  ./gradlew :server-gcp:runLocal',
    )
  } finally {
    await ctx.dispose()
  }
}
