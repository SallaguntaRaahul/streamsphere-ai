import { Page, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const realm = JSON.parse(readFileSync(join(__dirname, '../../../infrastructure/docker/keycloak/streamsphere-realm.json'), 'utf8'));

/** Local seeded TEST users only (infrastructure/docker/keycloak). */
export function testPassword(user: string): string {
  return realm.users.find((u: { username: string }) => u.username === user).credentials[0].value;
}

export async function login(page: Page, user: string) {
  await page.goto('/');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.locator('#username').fill(user);
  await page.locator('#password').fill(testPassword(user));
  await page.locator('#kc-login').click();
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
}

/** WCAG 2.2 A/AA scan; fails on serious/critical issues and returns all for the report. */
export async function a11y(page: Page, label: string) {
  const r = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
  const bad = r.violations.filter(v => v.impact === 'serious' || v.impact === 'critical');
  if (bad.length) console.log(`[axe] ${label}:`, JSON.stringify(bad.map(v => ({ id: v.id, impact: v.impact, nodes: v.nodes.slice(0, 3).map(n => n.target) })), null, 1));
  expect(bad, `${label}: serious/critical accessibility violations`).toEqual([]);
  return r.violations;
}

export async function shot(page: Page, name: string) {
  await page.screenshot({ path: join(__dirname, '../../../docs/screenshots', `${name}.png`), fullPage: false });
}
