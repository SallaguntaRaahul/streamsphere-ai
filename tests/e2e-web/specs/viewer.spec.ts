import { expect, test } from '@playwright/test';
import { a11y, login, shot } from './helpers';

test('@responsive home renders real catalog, hero and rows', async ({ page }, info) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await expect(page.getByRole('heading', { name: /Trending now|Recommended for you/ })).toBeVisible();
  await expect(page.locator('ss-title-card').first()).toBeVisible();
  await a11y(page, 'home');
  await shot(page, `home-${info.project.name}`);
});

test('@responsive semantic search returns meaningful results', async ({ page }, info) => {
  await page.goto('/search?q=' + encodeURIComponent('astronauts cooking in space') + '&mode=semantic');
  const first = page.getByRole('main').locator('li a h2').first();
  await expect(first).toHaveText(/Orbit Kitchen/);
  await expect(page.getByRole('status')).toContainText('semantic via');
  await a11y(page, 'search');
  await shot(page, `search-${info.project.name}`);
});

test('title detail is accessible and shows similar titles', async ({ page }) => {
  await page.goto('/search?q=Tidal%20Lanterns&mode=keyword');
  await page.getByRole('main').getByRole('link', { name: /Tidal Lanterns/ }).first().click();
  await expect(page.getByRole('heading', { level: 1, name: 'Tidal Lanterns' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'More like this' })).toBeVisible();
  await a11y(page, 'title-detail');
  await shot(page, 'title-detail-desktop');
});

test('viewer signs in (OIDC PKCE) and plays authorized adaptive HLS with captions', async ({ page }) => {
  const corsErrors: string[] = [];
  page.on('console', m => { if (m.type() === 'error' && /CORS|blocked/i.test(m.text())) corsErrors.push(m.text()); });
  await login(page, 'viewer');
  await page.goto('/search?q=Circuit%20Garden&mode=keyword');
  await page.getByRole('main').getByRole('link', { name: /Circuit Garden/ }).first().click();
  await page.getByRole('button', { name: /Play|Resume/ }).click();
  await expect(page).toHaveURL(/\/watch\//);
  const video = page.locator('video');
  await expect.poll(async () => video.evaluate((v: HTMLVideoElement) => v.readyState), { timeout: 30_000 }).toBeGreaterThanOrEqual(3);
  await expect.poll(async () => video.evaluate((v: HTMLVideoElement) => v.currentTime), { timeout: 30_000 }).toBeGreaterThan(1);
  const h = await video.evaluate((v: HTMLVideoElement) => v.videoHeight);
  expect([360, 480, 720]).toContain(h);
  // clip is short: pause, rewind, then exercise captions and keyboard control
  await video.evaluate((v: HTMLVideoElement) => { v.pause(); v.currentTime = 1; });
  await page.mouse.move(400, 300); // reveal auto-hiding controls
  await page.locator('#cc').selectOption('0', { force: true });
  await expect.poll(async () => video.evaluate((v: HTMLVideoElement) => [...v.textTracks].some(t => t.mode === 'showing' && (t.cues?.length ?? 0) > 0))).toBe(true);
  await page.locator('video').focus();
  await page.keyboard.press('k'); // keyboard shortcut: play
  await expect.poll(async () => video.evaluate((v: HTMLVideoElement) => v.paused)).toBe(false);
  await page.keyboard.press('k'); // pause
  await expect.poll(async () => video.evaluate((v: HTMLVideoElement) => v.paused)).toBe(true);
  await a11y(page, 'player');
  await shot(page, 'player-desktop');
  expect(corsErrors).toEqual([]);
});

test('library and profile pages for a signed-in viewer', async ({ page }) => {
  await login(page, 'viewer');
  await page.goto('/library');
  await expect(page.getByRole('heading', { name: 'My library' })).toBeVisible();
  await a11y(page, 'library');
  await page.goto('/profile');
  await expect(page.getByLabel('Display name')).toBeVisible();
  await a11y(page, 'profile');
});

test('unknown route shows a friendly 404', async ({ page }) => {
  await page.goto('/definitely/not/here');
  await expect(page.getByRole('heading', { name: "We couldn't find that page" })).toBeVisible();
  await a11y(page, '404');
});

test('viewer is forbidden from moderation', async ({ page }) => {
  await login(page, 'viewer');
  await page.goto('/moderation');
  await expect(page.getByRole('heading', { name: "You don't have access" })).toBeVisible();
});
