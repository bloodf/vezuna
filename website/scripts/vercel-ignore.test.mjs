import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

test('Vercel config enables only main and invokes the tested gate', () => {
  const config = JSON.parse(readFileSync(new URL('../vercel.json', import.meta.url), 'utf8'));
  assert.deepEqual(config.git.deploymentEnabled, { '**': false, main: true });
  assert.equal(config.ignoreCommand, 'node scripts/vercel-ignore.mjs');
});

test('Vercel builds only proven main site changes', () => {
  const temp = mkdtempSync(join(tmpdir(), 'vezuna-deploy-gate-'));
  try {
    const root = join(temp, 'repo');
    mkdirSync(join(root, 'website/scripts'), { recursive: true });
    const script = join(root, 'website/scripts/vercel-ignore.mjs');
    copyFileSync(new URL('./vercel-ignore.mjs', import.meta.url), script);
    const git = (...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
    const write = (path, content = path) => {
      mkdirSync(join(root, path, '..'), { recursive: true });
      writeFileSync(join(root, path), content);
    };
    const commit = () => { git('add', '.'); git('commit', '-qm', 'fixture'); return git('rev-parse', 'HEAD'); };
    const run = (before, expected, overrides = {}, cwd = join(root, 'website'), target = script) => {
      const result = spawnSync(process.execPath, [target], {
        cwd, encoding: 'utf8', env: {
          ...process.env, VERCEL_GIT_COMMIT_REF: 'main', VERCEL_ENV: 'production',
          VERCEL_GIT_PULL_REQUEST_ID: '', VERCEL_GIT_PREVIOUS_SHA: before,
          VERCEL_GIT_COMMIT_SHA: git('rev-parse', 'HEAD'), ...overrides,
        },
      });
      assert.equal(result.status, expected, result.stdout + result.stderr);
      assert.match(result.stdout, expected === 1 ? /^Build:/ : /^Skip:/);
    };
    git('init', '-q', '-b', 'main');
    git('config', 'user.email', 'fixture@example.invalid');
    git('config', 'user.name', 'Fixture');
    git('config', 'commit.gpgsign', 'false');
    write('website/src/page.txt');
    const base = commit();
    run(base, 0);
    write('shared/src/native.kt');
    write('README.md');
    const native = commit();
    run(base, 0);
    write('website/src/page.txt', 'changed');
    const site = commit();
    run(base, 1);
    run(base, 1, {}, root);
    for (const branch of ['feature/test', 'feature', '', 'main-copy']) run(base, 0, { VERCEL_GIT_COMMIT_REF: branch });
    run(base, 0, { VERCEL_ENV: 'preview' });
    run(base, 0, { VERCEL_ENV: '' });
    run(base, 0, { VERCEL_GIT_PULL_REQUEST_ID: '123' });
    run('', 0);
    run('0'.repeat(40), 0);
    run('--help', 0);
    run(base, 0, { VERCEL_GIT_COMMIT_SHA: '' });
    run(base, 0, { VERCEL_GIT_COMMIT_SHA: native });
    run(base, 0, { PATH: '' });
    write('docs/unrelated.md');
    commit();
    run(site, 0);
    run(native, 1); // A later native/docs commit must not hide an undeployed site change.
    for (const path of ['pnpm-lock.yaml', 'pnpm-workspace.yaml', 'package.json', '.npmrc', 'vercel.json', 'patches/dependency.patch', 'brand/logo.svg', 'docs/PUBLISHER_PRIVACY_POLICY.txt', 'tools/release/sync_privacy_policy.py']) {
      const before = git('rev-parse', 'HEAD');
      write(path);
      commit();
      run(before, 1);
    }
    let before = git('rev-parse', 'HEAD');
    git('mv', 'website/src/page.txt', 'outside.txt');
    commit();
    run(before, 1); // Rename out of the site retains the deleted input.
    before = git('rev-parse', 'HEAD');
    git('mv', 'outside.txt', 'website/src/renamed.txt');
    commit();
    run(before, 1);
    before = git('rev-parse', 'HEAD');
    git('rm', 'website/src/renamed.txt');
    commit();
    run(before, 1);
    before = git('rev-parse', 'HEAD');
    write('website/src/with\nnewline.txt');
    commit();
    run(before, 1);
    before = git('rev-parse', 'HEAD');
    git('switch', '-qc', 'topic');
    write('website/src/merged.txt');
    const topic = commit();
    git('switch', '-q', 'main');
    write('native.txt');
    commit();
    run(topic, 0); // Both SHAs exist, but the previous deployment is not an ancestor.
    git('merge', '--no-ff', '-qm', 'merge fixture', 'topic');
    run(before, 1);
    before = git('rev-parse', 'HEAD');
    git('switch', '-qc', 'squashed');
    write('website/src/squashed.txt');
    commit();
    git('switch', '-q', 'main');
    git('merge', '--squash', 'squashed');
    commit();
    run(before, 1);
    const shallow = join(temp, 'shallow');
    git('clone', '-q', '--depth=1', `file://${root}`, shallow);
    run(base, 0, {}, join(shallow, 'website'), join(shallow, 'website/scripts/vercel-ignore.mjs'));
    git('checkout', '--detach', '-q');
    run(before, 1);
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }
});
