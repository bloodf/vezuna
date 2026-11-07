import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:net';
import test from 'node:test';
import { withPreview } from './with-preview.mjs';

async function assertPortReleased(origin) {
  const server = createServer();
  try {
    await new Promise((resolve, reject) => {
      server.once('error', reject);
      server.listen(Number(new URL(origin).port), '127.0.0.1', resolve);
    });
  } finally {
    if (server.listening) await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  }
}

for (const fails of [false, true]) {
  test(`preview releases its listening port after ${fails ? 'failed' : 'successful'} verification`, async () => {
    const root = await mkdtemp(join(tmpdir(), 'vezuna-preview-'));
    let origin;
    const failure = new Error('deliberate verification failure');
    try {
      await mkdir(join(root, 'src', 'pages'), { recursive: true });
      await mkdir(join(root, 'dist'));
      await writeFile(join(root, 'astro.config.mjs'), 'export default {};\n');
      await writeFile(join(root, 'dist', 'index.html'), '<h1>Owned preview fixture</h1>');
      const run = withPreview(async (url) => {
        origin = url;
        assert.match(await (await fetch(url)).text(), /Owned preview fixture/);
        if (fails) throw failure;
        return 'verified';
      }, root);
      if (fails) await assert.rejects(run, (error) => error === failure);
      else assert.equal(await run, 'verified');
      assert(origin);
      await assertPortReleased(origin);
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  });
}
