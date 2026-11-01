import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const repositoryRoot = execFileSync('git', ['rev-parse', '--show-toplevel'], { cwd: process.cwd(), encoding: 'utf8' }).trim();
const git = (args) => execFileSync('git', args, { cwd: repositoryRoot, encoding: 'utf8' }).trim();
const branchBase = git(['merge-base', 'HEAD', 'main']);
const head = git(['rev-parse', 'HEAD']);
const comparisonBase = branchBase === head ? 'HEAD^' : branchBase;
const tracked = git(['diff', '--name-only', comparisonBase, '--']).split('\n').filter(Boolean);
const untracked = git(['ls-files', '--others', '--exclude-standard']).split('\n').filter(Boolean);
const workingArtifacts = new Set(['GATES.md', 'AUDIT.md']);
const changed = [...new Set([...tracked, ...untracked])].filter((path) => !workingArtifacts.has(path) && !path.startsWith('.skill-sources/') && !path.startsWith('.skill-import/'));
assert(changed.length > 0, 'No website changes found');
const outside = changed.filter((path) => !path.startsWith('website/'));
assert.deepEqual(outside, [], `Changes outside website/: ${outside.join(', ')}`);
for (const path of ['package.json', 'package-lock.json', 'eas.json', 'app.json', 'composeApp', 'shared', 'iosApp']) {
  const diff = git(['diff', '--name-only', branchBase, '--', path]);
  assert.equal(diff, '', `Native or root tooling changed: ${path}`);
}
console.log(`WEBSITE_SCOPE_VERIFIED changedPaths=${changed.length} nativeChanges=0 rootToolingChanges=0`);
