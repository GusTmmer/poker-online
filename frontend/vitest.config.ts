import { defineConfig } from 'vitest/config'

// Unit tests for pure game logic (no DOM/canvas). E2E lives under e2e/ on Playwright.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
