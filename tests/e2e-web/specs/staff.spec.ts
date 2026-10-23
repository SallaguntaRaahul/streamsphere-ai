import { expect, test } from '@playwright/test';
import { a11y, login, shot } from './helpers';

test('creator studio lists titles and opens the title wizard', async ({ page }) => {
  await login(page, 'creator');
  await page.goto('/creator');
  await expect(page.getByRole('table')).toBeVisible();
  await a11y(page, 'creator-studio');
  await shot(page, 'creator-studio-desktop');
  await page.getByRole('link', { name: /Open studio/ }).first().click();
  await expect(page.getByRole('heading', { name: /Details/ })).toBeVisible();
  await expect(page.getByRole('heading', { name: /Processing/ })).toBeVisible();
  await a11y(page, 'title-studio');
  await shot(page, 'title-studio-desktop');
});

test('creator analytics shows real aggregates', async ({ page }) => {
  await login(page, 'creator');
  await page.goto('/creator/analytics');
  await expect(page.getByRole('heading', { name: 'How your films are doing' })).toBeVisible();
  await a11y(page, 'creator-analytics');
  await shot(page, 'creator-analytics-desktop');
});

test('moderator sees the queue', async ({ page }) => {
  await login(page, 'moderator');
  await page.goto('/moderation');
  await expect(page.getByRole('heading', { name: 'Moderation queue' })).toBeVisible();
  await a11y(page, 'moderation');
  await shot(page, 'moderation-desktop');
});

test('admin operations dashboard shows every service UP', async ({ page }) => {
  await login(page, 'admin');
  await page.goto('/admin');
  await expect(page.getByRole('heading', { name: 'Services' })).toBeVisible();
  await expect(page.getByText('● DOWN')).toHaveCount(0);
  await expect(page.getByText('● UP').first()).toBeVisible();
  await a11y(page, 'admin');
  await shot(page, 'admin-desktop');
});
