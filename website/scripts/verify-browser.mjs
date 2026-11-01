import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { chromium } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

const cwd = process.cwd();
const origin = 'http://127.0.0.1:4173';
const routes = [
  '/', '/pt-br', '/es', '/support', '/pt-br/support', '/es/support', '/privacy', '/notices',
  '/docs', '/docs/getting-started', '/docs/lab-records', '/docs/medications-reminders-exports',
  '/docs/privacy-and-data', '/docs/offline-models', '/docs/developers',
  '/pt-br/docs', '/pt-br/docs/getting-started', '/es/docs', '/es/docs/getting-started',
];
mkdirSync('screenshots', { recursive: true });
const server = spawn('npm', ['run', 'preview', '--', '--host', '127.0.0.1', '--port', '4173'], {
  cwd,
  detached: true,
  stdio: ['ignore', 'pipe', 'pipe'],
});
let serverOutput = '';
server.stdout.on('data', (chunk) => { serverOutput += chunk; });
server.stderr.on('data', (chunk) => { serverOutput += chunk; });
let browser;

try {
  let ready = false;
  for (let attempt = 0; attempt < 60; attempt++) {
    try {
      const response = await fetch(origin);
      if (response.ok) { ready = true; break; }
    } catch { /* preview is still starting */ }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  assert(ready, `Preview did not start: ${serverOutput}`);
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ permissions: ['clipboard-read', 'clipboard-write'] });
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', (error) => errors.push(`pageerror: ${error.message}`));
  page.on('console', (message) => { if (message.type() === 'error') errors.push(`console: ${message.text()}`); });
  page.on('response', (response) => {
    if (response.url().startsWith(origin) && response.status() >= 400) errors.push(`${response.status()}: ${response.url()}`);
  });

  let layoutChecks = 0;
  for (const viewport of [{ name: 'phone', width: 390, height: 844 }, { name: 'desktop', width: 1440, height: 1000 }]) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    for (const route of routes) {
      await page.goto(`${origin}${route}`, { waitUntil: 'networkidle' });
      assert(await page.locator('main').isVisible(), `Main is not visible: ${viewport.name} ${route}`);
      const metrics = await page.evaluate(() => ({
        documentWidth: document.documentElement.scrollWidth,
        viewportWidth: window.innerWidth,
        h1Size: parseFloat(getComputedStyle(document.querySelector('h1')).fontSize),
      }));
      assert(metrics.documentWidth <= metrics.viewportWidth + 1, `Horizontal page overflow on ${viewport.name} ${route}: ${metrics.documentWidth} > ${metrics.viewportWidth}`);
      assert(metrics.h1Size >= 36, `Tiny page heading on ${viewport.name} ${route}: ${metrics.h1Size}`);
      const brokenImages = await page.locator('img').evaluateAll((images) => images.filter((image) => image.complete && image.naturalWidth === 0).map((image) => image.getAttribute('src')));
      assert.deepEqual(brokenImages, [], `Broken images on ${route}: ${brokenImages.join(', ')}`);
      layoutChecks++;
    }
  }

  await page.setViewportSize({ width: 820, height: 1180 });
  for (const route of ['/', '/pt-br', '/es', '/docs', '/docs/developers']) {
    await page.goto(`${origin}${route}`, { waitUntil: 'networkidle' });
    const widths = await page.evaluate(() => [document.documentElement.scrollWidth, window.innerWidth]);
    assert(widths[0] <= widths[1] + 1, `Tablet overflow on ${route}: ${widths.join(' > ')}`);
    layoutChecks++;
  }

  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(origin, { waitUntil: 'networkidle' });
  assert.equal(await page.locator('.store-pending').count(), 2, 'Store availability states are missing');
  assert.equal(await page.locator('.availability-actions a').getAttribute('href'), 'https://github.com/bloodf/vezuna');
  assert.equal(await page.locator('[data-health-scene]').count(), 0, 'Discarded abstract scene is still present');
  assert(await page.locator('#workspace-panel-labs').isVisible(), 'Lab panel is not visible by default');
  assert(await page.locator('.lab-chart-line').isVisible(), 'Lab comparison chart is missing');
  assert((await page.locator('.lab-results').innerText()).includes('13.8'));

  await page.locator('#workspace-tab-meds').click();
  assert(await page.locator('#workspace-panel-meds').isVisible(), 'Medication panel did not open');
  assert.equal(await page.locator('.meds-schedule-row').count(), 3);
  assert((await page.locator('.meds-schedule').innerText()).includes('Snoozed'));
  await page.locator('#workspace-tab-device').click();
  assert(await page.locator('#workspace-panel-device').isVisible(), 'Device panel did not open');
  const deviceText = await page.locator('.device-metrics').innerText();
  for (const text of ['Weight', '72.4', 'Heart rate', '68', 'Steps', '6,842']) assert(deviceText.includes(text), `Missing device metric: ${text}`);
  await page.locator('.device-panel').screenshot({ path: 'screenshots/workspace-device.png' });
  await page.locator('#workspace-tab-household').click();
  assert(await page.locator('#workspace-panel-household').isVisible(), 'Household panel did not open');
  assert.equal(await page.locator('.household-profile-row').count(), 2);

  await page.locator('#workspace-tab-labs').focus();
  await page.keyboard.press('ArrowRight');
  assert.equal(await page.locator('#workspace-tab-meds').getAttribute('aria-selected'), 'true', 'Keyboard tab navigation failed');
  await page.keyboard.press('ArrowLeft');
  assert.equal(await page.locator('#workspace-tab-labs').getAttribute('aria-selected'), 'true', 'Keyboard return to labs failed');
  await page.locator('.primary-nav a[href="#workspace-tab-device"]').click();
  assert.equal(new URL(page.url()).hash, '#workspace-tab-device');
  await page.locator('#workspace-panel-device').waitFor({ state: 'visible' });
  assert(await page.locator('#workspace-panel-device').isVisible(), 'Direct device navigation did not select the panel');
  await page.locator('.primary-nav a[href="#privacy"]').click();
  assert.equal(new URL(page.url()).hash, '#privacy');
  await page.locator('.primary-nav a[href="#faq"]').click();
  assert.equal(new URL(page.url()).hash, '#faq');

  await page.locator('.language-nav a[href="/pt-br"]').click();
  await page.waitForURL('**/pt-br');
  assert.equal(await page.locator('html').getAttribute('lang'), 'pt-BR');
  assert((await page.locator('h1').innerText()).includes('registros de saúde'));
  await page.locator('.language-nav a[href="/es"]').click();
  await page.waitForURL('**/es');
  assert.equal(await page.locator('html').getAttribute('lang'), 'es');
  assert((await page.locator('h1').innerText()).includes('registros de salud'));

  await page.goto(origin, { waitUntil: 'networkidle' });
  await page.screenshot({ path: 'screenshots/redesign-desktop.png', fullPage: false });
  await page.screenshot({ path: 'screenshots/redesign-desktop-full.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: 'screenshots/redesign-mobile.png', fullPage: false });
  await page.screenshot({ path: 'screenshots/redesign-mobile-full.png', fullPage: true });
  await page.locator('.workspace-section').scrollIntoViewIfNeeded();
  await page.locator('.workspace-section').screenshot({ path: 'screenshots/workspace-mobile.png' });
  const galleryWidth = await page.locator('.gallery-item').first().evaluate((element) => element.getBoundingClientRect().width);
  assert(galleryWidth >= 280, `Mobile screenshots are too small: ${galleryWidth}`);
  await page.locator('.navigation > summary').click();
  assert(await page.locator('.nav-panel').isVisible(), 'Mobile menu did not open');
  await page.locator('.primary-nav a[href="#faq"]').click();
  assert.equal(new URL(page.url()).hash, '#faq');

  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(`${origin}/docs`, { waitUntil: 'networkidle' });
  await page.screenshot({ path: 'screenshots/docs-desktop.png', fullPage: false });
  const search = page.locator('pagefind-searchbox');
  const searchInput = search.locator('input').first();
  await searchInput.waitFor();
  await searchInput.fill('Keystore');
  await page.waitForTimeout(800);
  const searchText = await search.innerText();
  assert(/Developer guide|Privacy and data|Getting started/.test(searchText), `Search returned no relevant results: ${searchText}`);
  assert(await search.locator('a').count() > 0, 'Documentation search returned no links');

  await page.goto(`${origin}/docs/developers`, { waitUntil: 'networkidle' });
  const copyButton = page.locator('.copy-code').first();
  await copyButton.click();
  await page.waitForFunction(() => document.querySelector('.copy-code')?.textContent === 'Copied');
  assert.equal(await copyButton.innerText(), 'Copied');
  assert((await page.evaluate(() => navigator.clipboard.readText())).includes('shared/'));
  await page.keyboard.press('Tab');
  assert(await page.evaluate(() => document.activeElement !== document.body), 'Keyboard focus did not move');
  await page.emulateMedia({ reducedMotion: 'reduce' });
  assert.equal(await page.evaluate(() => getComputedStyle(document.documentElement).scrollBehavior), 'auto');
  await page.goto(origin, { waitUntil: 'networkidle' });
  assert(await page.locator('#workspace-panel-labs').evaluate((element) => ['0s', '0ms'].includes(getComputedStyle(element).animationDuration)));
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${origin}/docs/getting-started`, { waitUntil: 'networkidle' });
  await page.screenshot({ path: 'screenshots/docs-mobile.png', fullPage: false });

  const axeRoutes = ['/', '/pt-br', '/es', '/docs', '/docs/developers', '/pt-br/docs/getting-started', '/support', '/privacy'];
  const violations = [];
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  for (const route of axeRoutes) {
    await page.goto(`${origin}${route}`, { waitUntil: 'networkidle' });
    const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze();
    for (const violation of result.violations) {
      violations.push(`${route} ${violation.id}: ${violation.help} (${violation.nodes.map((node) => node.target.join(' ')).join(', ')})`);
    }
  }
  assert.deepEqual(violations, [], `Accessibility violations:\n${violations.join('\n')}`);
  assert.deepEqual(errors, [], `Browser errors:\n${[...new Set(errors)].join('\n')}`);
  console.log(`BROWSER_VERIFIED layouts=${layoutChecks} axePages=${axeRoutes.length} workspaces=4 keyboardTabs=passed deviceMetrics=passed reducedMotion=passed screenshots=7`);
} finally {
  if (browser) await browser.close();
  if (server.pid) {
    try { process.kill(-server.pid, 'SIGTERM'); } catch { server.kill('SIGTERM'); }
  }
}
