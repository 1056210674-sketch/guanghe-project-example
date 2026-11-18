import react from '@vitejs/plugin-react'
import { configDefaults, defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  build: {
    manifest: true,
    sourcemap: false,
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: true,
    restoreMocks: true,
    maxWorkers: 2,
    // Multi-step jsdom/Ant Design flows need a test budget, not a production latency SLA.
    // Keep unit tests on Vitest's default budget and preserve their default file scope.
    projects: [
      {
        extends: true,
        test: {
          name: 'integration',
          include: ['**/*.integration.test.{ts,tsx}'],
          testTimeout: 20_000,
        },
      },
      {
        extends: true,
        test: {
          name: 'unit',
          include: configDefaults.include,
          exclude: [...configDefaults.exclude, '**/*.integration.test.{ts,tsx}'],
        },
      },
    ],
  },
})
