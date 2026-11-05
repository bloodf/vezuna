import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const marketing = readFileSync(new URL('../src/data/marketing.ts', import.meta.url), 'utf8');
const pages = readFileSync(new URL('../src/data/pages.ts', import.meta.url), 'utf8');
const both = `${marketing}\n${pages}`;

test('all three task-led headlines are present', () => {
  for (const line of [
    'Keep your household’s health records on your phone.',
    'Guarde os registros de saúde da família no seu celular.',
    'Guarda los registros de salud de tu familia en tu teléfono.',
  ]) {
    assert(marketing.includes(`title: '${line}'`), `Missing headline: ${line}`);
  }
});

test('marketing avoids unverified commercial promises', () => {
  assert(!/waitlist|testimonial|apps\.apple\.com|play\.google\.com|download now|rating/i.test(marketing));
  // The only price is the owner-confirmed store price, and it must stay prospective.
  const prices = [...marketing.matchAll(/US\$\s?\d+[.,]\d{2}/g)].map((match) => match[0].replace(',', '.'));
  assert(prices.length > 0, 'Price is missing');
  assert(prices.every((price) => price === 'US$9.99'), `Unexpected price: ${prices.join(', ')}`);
  assert(/no stable release (?:has been|is) published/i.test(marketing));
  assert(/Nenhuma versão estável foi publicada/.test(marketing));
  assert(/No se ha publicado una versión estable/.test(marketing));
  assert(marketing.includes('does not diagnose'));
});

test('marketing promises no AI features', () => {
  assert(marketing.includes('no AI features are available'));
  assert(!/AI (?:assistant|explains|summar|insight)/i.test(marketing));
});

test('marketing keeps platform and backup facts accurate', () => {
  assert(marketing.includes('Android 8.0'));
  assert(marketing.includes('iOS 18.5'));
  assert(!/macOS|Windows|desktop app/i.test(marketing.replace('There is no Mac or desktop version.', '')));
  assert(marketing.includes('Restore it on a new phone.'));
});

test('support never requests medical records', () => {
  assert(marketing.includes('Never attach actual medical documents'));
  assert(marketing.includes('Use synthetic examples only'));
});

test('the open source page separates MIT from third-party terms', () => {
  // The app source is MIT, and the page says so in every language.
  assert(pages.includes('The app source is MIT licensed'));
  assert(pages.includes('O código do app é MIT'));
  assert(pages.includes('El código de la app es MIT'));
  // Components that keep their own terms must be named, not blurred into "MIT".
  for (const term of [
    'SIL Open Font License 1.1',
    'ML Kit Terms of Service',
    'Play Core Software Development Kit Terms of Service',
    'Apache-2.0',
  ]) {
    assert(pages.includes(term), `Missing third-party term: ${term}`);
  }
  // Models and native libraries are called out as unresolved, not as MIT.
  assert(pages.includes('the repository ships no model'));
  assert(pages.includes('Llamatik'));
  assert(pages.includes('release gate'));
  // The artwork is MIT without trademark rights, which is a narrower grant than the code.
  assert(pages.includes('without trademark rights'));
});

test('the new pages never promise a published build', () => {
  assert(pages.includes('No stable release has been published'));
  assert(pages.includes('Nenhuma versão estável foi publicada'));
  assert(pages.includes('No se ha publicado ninguna versión estable'));
  assert(pages.includes('there are no download links'));
  assert(!/available (?:now )?for download|download (?:it )?now|release(?:d)? (?:and )?available/i.test(pages));
  assert(!/apps\.apple\.com|play\.google\.com/i.test(pages));
});

test('the new pages invent no social proof', () => {
  assert(!/\b\d+\s*(?:stars?|k stars|downloads|users|installs)\b/i.test(both), 'Star or download counts are not verifiable');
  assert(!/testimonial/i.test(both));
  assert(!/\bHIPAA\b|\bGDPR\b|clinically validated|clinically proven/i.test(both), 'Regulatory or clinical claims are not supported');
});

test('the new pages keep the AI boundary honest', () => {
  assert(pages.includes('no AI feature is available'));
  assert(/catalog is empty/i.test(pages), 'The empty production model catalog must be stated');
  assert(pages.includes('never depends on an AI model'), 'Exports and backups must stay independent of a model');
  assert(!/AI (?:assistant|explains|summar|insight)/i.test(pages));
});

test('the new pages repeat the verified backup and permission facts', () => {
  assert(pages.includes('twelve characters'));
  assert(pages.includes('24 MB'));
  assert(pages.includes('AES-256'));
  assert(pages.includes('Android Keystore'));
  assert(pages.includes('CryptoKit'));
  assert(pages.includes('INTERNET permission'));
  assert(pages.includes('No independent security audit'));
});

test('every new page exists in the three supported languages', () => {
  for (const block of ['openSourceCopy', 'screensCopy', 'howItWorksCopy', 'localDataCopy']) {
    assert(pages.includes(`export const ${block}: Record<Locale`), `Missing copy block: ${block}`);
  }
  for (const locale of ["en:", "'pt-br':", "es:"]) {
    assert(pages.includes(locale), `Missing locale: ${locale}`);
  }
  // Each page must ship a description, which feeds the meta tags.
  const descriptions = [...pages.matchAll(/description: '([^']{40,})'/g)].map((match) => match[1]);
  assert(descriptions.length >= 12, `Expected a description per page and locale, found ${descriptions.length}`);
});

test('no placeholder copy slipped into the new pages', () => {
  assert(!/lorem ipsum|TODO:|PLACEHOLDER|example\.com/i.test(pages));
  assert(!/Lorem/i.test(pages));
});
