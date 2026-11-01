import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const marketing = readFileSync(new URL('../src/data/marketing.ts', import.meta.url), 'utf8');

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
