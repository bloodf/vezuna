import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const marketing = readFileSync(new URL('../src/data/marketing.ts', import.meta.url), 'utf8');

test('all three brand lines are exact', () => {
  for (const line of ['Your health, in one place.', 'Sua saúde, em um só lugar.', 'Tu salud, en un solo lugar.']) {
    assert(marketing.includes(`title: '${line}'`));
  }
});

test('marketing avoids unverified commercial promises', () => {
  assert(!/waitlist|testimonial|\$\d+|App Store link|Play Store link/i.test(marketing));
  assert(marketing.includes('no stable release has been published'));
  assert(marketing.includes('does not diagnose'));
});

test('support never requests medical records', () => {
  assert(marketing.includes('Never attach real medical documents'));
  assert(marketing.includes('Use synthetic examples only'));
});
