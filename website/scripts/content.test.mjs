import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const marketing = readFileSync(new URL('../src/data/marketing.ts', import.meta.url), 'utf8');

test('all three product-led headlines are present', () => {
  for (const line of ['Keep everyday health records together.', 'Mantenha os registros de saúde do dia a dia juntos.', 'Mantén juntos los registros de salud diarios.']) {
    assert(marketing.includes(`title: '${line}'`));
  }
});

test('marketing avoids unverified commercial promises', () => {
  assert(!/waitlist|testimonial|\$\d+|App Store link|Play Store link/i.test(marketing));
  assert(/no stable release (?:has been|is) published/i.test(marketing));
  assert(marketing.includes('does not diagnose'));
});

test('support never requests medical records', () => {
  assert(marketing.includes('Never attach actual medical documents'));
  assert(marketing.includes('Use synthetic examples only'));
});
