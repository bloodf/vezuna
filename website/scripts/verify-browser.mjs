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
  // Marketing home: real captures only, no fake product UI, honest availability.
  assert.equal(await page.locator('[data-health-scene], [data-workspace]').count(), 0, 'Discarded scene or simulated workspace is still present');
  assert.equal(await page.locator('.screen img').count(), 3, 'Expected three real app captures');
  const heroImage = page.locator('.hero .screen img');
  assert.equal(await heroImage.getAttribute('loading'), 'eager', 'Hero capture must not lazy-load');
  assert((await page.locator('#price').innerText()).includes('US$9.99'));
  assert((await page.locator('.price-status').innerText()).includes('No stable release has been published'));
  assert.equal(await page.locator('#price a[rel="external"]').getAttribute('href'), 'https://github.com/bloodf/vezuna');
  assert.equal(await page.locator('.data-map tbody tr').count(), 5, 'Privacy data map rows missing');
  assert.equal(await page.locator('script[src]:not([src*="pagefind"])').count(), 0, 'Marketing page should ship no external scripts');

  const firstFaq = page.locator('.faq-item').first();
  await firstFaq.locator('summary').click();
  assert.equal(await firstFaq.evaluate((element) => element.open), true, 'FAQ item did not open');
  await firstFaq.locator('summary').focus();
  await page.keyboard.press('Enter');
  assert.equal(await firstFaq.evaluate((element) => element.open), false, 'FAQ item did not close from keyboard');

  for (const hash of ['how', 'records', 'privacy', 'price', 'faq']) {
    await page.locator(`.primary-nav a[href="#${hash}"]`).click();
    assert.equal(new URL(page.url()).hash, `#${hash}`);
  }

  await page.locator('.language-nav a[href="/pt-br"]').click();
  await page.waitForURL('**/pt-br');
  assert.equal(await page.locator('html').getAttribute('lang'), 'pt-BR');
  assert((await page.locator('h1').innerText()).includes('registros de saúde'));
  await page.locator('.language-nav a[href="/es"]').click();
  await page.waitForURL('**/es');
  assert.equal(await page.locator('html').getAttribute('lang'), 'es');
  assert((await page.locator('h1').innerText()).includes('registros de salud'));

  await page.goto(origin, { waitUntil: 'networkidle' });
  const shift = await page.evaluate(() => new Promise((resolve) => {
    let total = 0;
    new PerformanceObserver((list) => { for (const entry of list.getEntries()) if (!entry.hadRecentInput) total += entry.value; })
      .observe({ type: 'layout-shift', buffered: true });
    setTimeout(() => resolve(total), 500);
  }));
  assert(shift < 0.02, `Layout shift on home: ${shift}`);
  await page.screenshot({ path: 'screenshots/redesign-desktop.png', fullPage: false });
  await page.screenshot({ path: 'screenshots/redesign-desktop-full.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: 'screenshots/redesign-mobile.png', fullPage: false });
  await page.screenshot({ path: 'screenshots/redesign-mobile-full.png', fullPage: true });
  const captureWidth = await page.locator('.screen').first().evaluate((element) => element.getBoundingClientRect().width);
  assert(captureWidth >= 280, `Mobile captures are too small: ${captureWidth}`);
  assert.equal(await page.locator('.nav-panel').isVisible(), false, 'Mobile menu should start closed');
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
  assert(await page.locator('.faq-item .icon-plus').first().evaluate((element) => ['0s', '0ms'].includes(getComputedStyle(element).transitionDuration)), 'Reduced motion still animates');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${origin}/docs/developers`, { waitUntil: 'networkidle' });
  const docsMenu = page.locator('.docs-menu');
  assert.equal(await docsMenu.evaluate((element) => element.open), false, 'Docs menu should start closed on phones');
  await docsMenu.locator('summary').click();
  assert(await page.locator('.docs-nav a[aria-current="page"]').isVisible(), 'Docs menu did not open on phones');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${origin}/docs/getting-started`, { waitUntil: 'networkidle' });
  await page.screenshot({ path: 'screenshots/docs-mobile.png', fullPage: false });

  const axeRoutes = ['/', '/pt-br', '/es', '/docs', '/docs/developers', '/docs/privacy-and-data', '/pt-br/docs/getting-started', '/es/support', '/support', '/privacy', '/notices', '/404'];
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
  console.log(`BROWSER_VERIFIED layouts=${layoutChecks} axePages=${axeRoutes.length} captures=3 faqKeyboard=passed docsMobileMenu=passed layoutShift=passed reducedMotion=passed screenshots=6`);
} finally {
  if (browser) await browser.close();
  if (server.pid) {
    try { process.kill(-server.pid, 'SIGTERM'); } catch { server.kill('SIGTERM'); }
  }
}
