import { defineConfig, devices } from '@playwright/test';

/**
 * Real-browser tests against the running local stack (make up + web dev server on :4200).
 * Playback runs in branded Chrome because open-source Chromium builds lack H.264/AAC decoders.
 */
export default defineConfig({
  testDir: './specs',
  timeout: 90_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never', outputFolder: '../../tmp/playwright-report' }]],
  outputDir: '../../tmp/playwright-results',
  use: { baseURL: process.env.WEB_URL ?? 'http://localhost:4200', trace: 'retain-on-failure', screenshot: 'only-on-failure', reducedMotion: 'reduce' },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'], channel: 'chrome', viewport: { width: 1440, height: 900 } } },
    { name: 'mobile', use: { ...devices['Pixel 7'], channel: 'chrome' }, grep: /@responsive/ },
  ],
});
