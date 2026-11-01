import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const repositoryRoot = execFileSync('git', ['rev-parse', '--show-toplevel'], { cwd: process.cwd(), encoding: 'utf8' }).trim();
const git = (args) => execFileSync('git', args, { cwd: repositoryRoot, encoding: 'utf8' }).trim();
const branchBase = git(['merge-base', 'HEAD', 'main']);
const tracked = git(['diff', '--name-only', branchBase, '--']).split('\n').filter(Boolean);
const untracked = git(['ls-files', '--others', '--exclude-standard']).split('\n').filter(Boolean);
const changed = [...new Set([...tracked, ...untracked])].filter((path) => path !== 'GATES.md');
assert(changed.length > 0, 'No website changes found');
const outside = changed.filter((path) => !path.startsWith('website/'));
assert.deepEqual(outside, [], `Changes outside website/: ${outside.join(', ')}`);
for (const path of ['package.json', 'package-lock.json', 'eas.json', 'app.json', 'composeApp', 'shared', 'iosApp']) {
  const diff = git(['diff', '--name-only', branchBase, '--', path]);
  assert.equal(diff, '', `Native or root tooling changed: ${path}`);
}
console.log(`WEBSITE_SCOPE_VERIFIED changedPaths=${changed.length} nativeChanges=0 rootToolingChanges=0`);
