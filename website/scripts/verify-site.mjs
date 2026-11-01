import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';

const root = process.cwd();
const dist = join(root, 'dist');
const routes = [
  '/', '/pt-br', '/es', '/support', '/pt-br/support', '/es/support', '/privacy', '/notices',
  '/docs', '/docs/getting-started', '/docs/lab-records', '/docs/medications-reminders-exports',
  '/docs/privacy-and-data', '/docs/offline-models', '/docs/developers',
  '/pt-br/docs', '/pt-br/docs/getting-started', '/es/docs', '/es/docs/getting-started',
];
const expectedLang = (route) => route.startsWith('/pt-br') ? 'pt-BR' : route.startsWith('/es') ? 'es' : 'en';
const htmlFile = (route) => join(dist, route === '/' ? 'index.html' : `${route.replace(/^\//, '')}/index.html`);
const fileForUrl = (url) => {
  const path = url.replace(/^\//, '');
  if (!path) return join(dist, 'index.html');
  if (/\.[a-z0-9]+$/i.test(path)) return join(dist, path);
  return join(dist, path.replace(/\/$/, ''), 'index.html');
};
const requiredText = {
  '/': ['Keep everyday health records together.', 'Weight', '72.4', 'Heart rate', '6,842', 'Demo Medicine', 'data-workspace'],
  '/pt-br': ['Mantenha os registros de saúde do dia a dia juntos.', 'Peso', '72,4', 'Frequência cardíaca', '6.842', 'data-workspace'],
  '/es': ['Mantén juntos los registros de salud diarios.', 'Peso', '72,4', 'Frecuencia cardíaca', '6.842', 'data-workspace'],
  '/docs/developers': ['JDK 17', 'Android SDK API 37', 'iOS 26 SDK', 'EAS Submit', 'app.vezuna'],
  '/docs/privacy-and-data': ['same device key', 'Denying health permissions', 'Vercel'],
  '/privacy': ['Vezuna privacy policy', 'Publisher and contact', 'Vercel hosts the website'],
  '/support': ['GitHub private vulnerability reporting', 'Never attach actual medical documents'],
};

assert(existsSync(dist), 'dist is missing; run npm run build first');
const documents = new Map();
for (const route of routes) {
  const file = htmlFile(route);
  assert(existsSync(file), `Missing route: ${route}`);
  const html = readFileSync(file, 'utf8');
  documents.set(route, html);
  assert(html.includes(`<html lang="${expectedLang(route)}"`), `Wrong language metadata: ${route}`);
  assert(html.includes('<main'), `Missing main landmark: ${route}`);
  assert(html.includes('rel="canonical"'), `Missing canonical: ${route}`);
  assert(html.includes('property="og:image"'), `Missing social preview: ${route}`);
  assert(html.includes('/fonts/NotoSans-Regular.ttf'), `Missing local font: ${route}`);
  assert(!/TODO:|PLACEHOLDER|lorem ipsum|example\.com/i.test(html), `Placeholder content: ${route}`);
  for (const text of requiredText[route] ?? []) assert(html.includes(text), `Missing required claim on ${route}: ${text}`);
  for (const image of html.matchAll(/<img\b[^>]*>/g)) assert(/\balt="[^"]*"/.test(image[0]), `Missing image alt on ${route}`);
}

let checkedLinks = 0;
for (const [route, html] of documents) {
  for (const match of html.matchAll(/\b(?:href|src)="([^"]+)"/g)) {
    const original = match[1];
    if (/^(?:mailto:|tel:|data:|javascript:)/.test(original)) continue;
    if (/^https?:\/\//.test(original) && !original.startsWith('https://vezuna.app')) continue;
    const local = original.startsWith('https://vezuna.app') ? original.slice('https://vezuna.app'.length) : original;
    const [withoutHash, hash] = local.split('#');
    const path = withoutHash.split('?')[0];
    if (!path && hash) {
      assert(html.includes(`id="${hash}"`), `Missing same-page anchor ${hash} on ${route}`);
      checkedLinks++;
      continue;
    }
    if (!path || !path.startsWith('/')) continue;
    const file = fileForUrl(path);
    assert(existsSync(file), `Broken internal link on ${route}: ${original} -> ${relative(root, file)}`);
    if (hash && file.endsWith('.html')) {
      const target = readFileSync(file, 'utf8');
      assert(target.includes(`id="${hash}"`), `Missing target anchor ${hash} in ${path}`);
    }
    checkedLinks++;
  }
}

for (const fileName of ['sitemap-index.xml', 'sitemap-0.xml', 'robots.txt', 'site.webmanifest']) {
  assert(existsSync(join(dist, fileName)), `Missing ${fileName}`);
}
assert(existsSync(join(dist, 'pagefind/pagefind-component-ui.js')), 'Missing Pagefind UI');
assert(existsSync(join(dist, 'pagefind/pagefind-component-ui.css')), 'Missing Pagefind styles');
assert(existsSync(join(dist, '404.html')), 'Missing custom 404');
const sitemap = readFileSync(join(dist, 'sitemap-0.xml'), 'utf8');
for (const route of routes) {
  const canonical = `https://vezuna.app${route === '/' ? '/' : route}`;
  assert(sitemap.includes(`<loc>${canonical}</loc>`), `Missing sitemap route: ${canonical}`);
}
const allHtml = [...documents.values()].join('\n');
assert(!/apps\.apple\.com|play\.google\.com\/store|googletagmanager|google-analytics/.test(allHtml), 'Unverified store or analytics action found');
console.log(`SITE_CONTENT_VERIFIED routes=${routes.length} internalLinks=${checkedLinks} languages=3`);
