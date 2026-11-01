import assert from 'node:assert/strict';

const origin = 'https://vezuna.app';
const routes = [
  '/', '/pt-br', '/es', '/support', '/pt-br/support', '/es/support', '/privacy', '/notices',
  '/docs', '/docs/getting-started', '/docs/lab-records', '/docs/medications-reminders-exports',
  '/docs/privacy-and-data', '/docs/offline-models', '/docs/developers',
  '/pt-br/docs', '/pt-br/docs/getting-started', '/es/docs', '/es/docs/getting-started',
];
for (const route of routes) {
  const response = await fetch(`${origin}${route}`, { redirect: 'follow', headers: { 'user-agent': 'Vezuna-site-verification/1.0' } });
  assert.equal(response.status, 200, `${route} returned ${response.status}`);
  assert(response.url.startsWith(origin), `${route} redirected away from production: ${response.url}`);
  assert((response.headers.get('content-type') ?? '').includes('text/html'), `${route} did not serve HTML`);
  assert.equal(response.headers.get('x-content-type-options'), 'nosniff', `${route} missing security header`);
  const html = await response.text();
  assert(html.includes('<html lang='), `${route} missing language metadata`);
  assert(html.includes('rel="canonical"'), `${route} missing canonical`);
  assert(html.includes('<main'), `${route} missing main content`);
  assert(!/Deployment not found|404: NOT_FOUND|This page could not be found/.test(html), `${route} served an error page`);
}
for (const asset of ['/robots.txt', '/sitemap-index.xml', '/sitemap-0.xml', '/assets/og-1200x630.png', '/pagefind/pagefind-component-ui.js']) {
  const response = await fetch(`${origin}${asset}`);
  assert.equal(response.status, 200, `${asset} returned ${response.status}`);
}
console.log(`LIVE_SITE_VERIFIED origin=${origin} routes=${routes.length} production=true`);
