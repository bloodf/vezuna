import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// Vercel's Ignored Build Step uses 0 to skip and 1 to build.
const root = fileURLToPath(new URL('../../', import.meta.url));
const git = (...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
const sharedInputs = new Set([
  'package.json', 'pnpm-lock.yaml', 'pnpm-workspace.yaml', 'package-lock.json',
  'npm-shrinkwrap.json', 'yarn.lock', 'bun.lock', 'bun.lockb',
  '.npmrc', '.nvmrc', '.node-version', '.pnpmfile.cjs', 'pnpmfile.cjs',
  'vercel.json', 'tsconfig.json',
  'docs/PUBLISHER_PRIVACY_POLICY.txt', 'tools/release/sync_privacy_policy.py',
  'tools/branding/sync_assets.py',
]);

function decision() {
  const env = process.env;
  if (env.VERCEL_GIT_COMMIT_REF !== 'main' || env.VERCEL_ENV !== 'production' || env.VERCEL_GIT_PULL_REQUEST_ID) {
    return 'Skip: only production main commits without a pull request are eligible.';
  }
  const before = env.VERCEL_GIT_PREVIOUS_SHA;
  const after = env.VERCEL_GIT_COMMIT_SHA;
  if (![before, after].every((sha) => /^(?:[a-f0-9]{40}|[a-f0-9]{64})$/i.test(sha ?? ''))) {
    return 'Skip: missing or invalid deployment SHA; reviewed baseline recovery required.';
  }
  try {
    if (git('rev-parse', 'HEAD').trim() !== after) return 'Skip: checkout does not match deployment SHA.';
    git('cat-file', '-e', `${before}^{commit}`);
    git('merge-base', '--is-ancestor', before, after);
    // Tree-to-tree comparison includes all merged/squashed commits. Disable rename
    // detection so both the old and new paths participate, including deleted inputs.
    const paths = git('diff', '--name-only', '--no-renames', '-z', before, after, '--').split('\0');
    if (paths.some((path) => path.startsWith('website/') || path.startsWith('brand/') || path.startsWith('patches/') || sharedInputs.has(path))) {
      console.log('Build: production main changed website inputs since the last successful deployment.');
      return null;
    }
    return 'Skip: no website inputs changed since the last successful deployment.';
  } catch {
    return 'Skip: deployment history unavailable or divergent; reviewed baseline recovery required.';
  }
}

const skip = decision();
if (skip) console.log(skip);
process.exitCode = skip ? 0 : 1;
