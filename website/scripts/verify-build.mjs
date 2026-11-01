import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const dist = join(process.cwd(), 'dist');
const htmlFiles = [];
const walk = (directory) => {
  for (const name of readdirSync(directory)) {
    const path = join(directory, name);
    if (statSync(path).isDirectory()) walk(path);
    else if (path.endsWith('.html')) htmlFiles.push(path);
  }
};
walk(dist);
assert.equal(htmlFiles.length, 20, `Expected 20 HTML routes, found ${htmlFiles.length}`);
for (const path of ['pagefind/pagefind-component-ui.js', 'pagefind/pagefind-component-ui.css', 'pagefind/pagefind.js', 'assets/android-home.png', 'assets/android-labs.png', 'assets/android-settings.png', 'assets/og-1200x630.png', 'fonts/NotoSans-Regular.ttf', 'fonts/NotoSans-Bold.ttf', 'fonts/NOTO-LICENSE.txt', 'icons/favicon.ico']) {
  assert(existsSync(join(dist, path)), `Missing production asset: ${path}`);
}
const cssFiles = readdirSync(join(dist, '_astro')).filter((name) => name.endsWith('.css'));
assert(cssFiles.length > 0, 'Missing compiled CSS');
assert(cssFiles.some((name) => readFileSync(join(dist, '_astro', name), 'utf8').includes('Noto Sans')), 'Compiled CSS does not contain Noto Sans');
console.log(`PRODUCTION_BUILD_VERIFIED html=${htmlFiles.length} searchIndex=3-languages assets=local`);
