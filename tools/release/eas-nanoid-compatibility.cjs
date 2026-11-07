// Run against the installed candidate graph after parent heavy-slot admission:
// node tools/release/eas-nanoid-compatibility.cjs [6.0.2|3.3.20]
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const { pathToFileURL } = require('node:url');

// Fail closed if any caller or dependency attempts network activity.
const denyNetwork = () => { throw new Error('Network forbidden in compatibility fixtures'); };
require('node:net').Socket.prototype.connect = denyNetwork;
require('node:http').request = denyNetwork;
require('node:https').request = denyNetwork;
globalThis.fetch = denyNetwork;

const expectedNanoidVersion = process.argv[2] || '6.0.2';
assert(['6.0.2', '3.3.20'].includes(expectedNanoidVersion), 'Select candidate or explicit baseline');
assert.equal(process.version, 'v24.22.2', 'Qualification requires the pinned Node platform');

const easPackage = require.resolve('eas-cli/package.json');
const eas = createRequire(easPackage);
const easVersion = JSON.parse(fs.readFileSync(easPackage, 'utf8')).version;
assert.equal(easVersion, '27.5.0', 'Caller coverage was source-traced for EAS 27.5.0');
const callers = [
  './build/credentials/ios/actions/AscApiKeyUtils.js',
  './build/credentials/android/actions/BuildCredentialsUtils.js',
  './build/commandUtils/new/configs.js',
];
console.log(JSON.stringify({ node: process.version, easVersion, easPackage }));
for (const caller of callers) {
  const callerPath = eas.resolve(caller);
  const callerRequire = createRequire(callerPath);
  const nanoidPath = callerRequire.resolve('nanoid');
  // NanoID need not export package.json. Read metadata beside its resolved entry.
  let directory = path.dirname(nanoidPath);
  let nanoidPackage;
  for (;;) {
    const metadata = path.join(directory, 'package.json');
    if (fs.existsSync(metadata)) {
      const candidate = JSON.parse(fs.readFileSync(metadata, 'utf8'));
      if (candidate.name === 'nanoid') {
        nanoidPackage = candidate;
        break;
      }
    }
    const parent = path.dirname(directory);
    assert.notEqual(parent, directory, 'Cannot locate resolved NanoID metadata');
    directory = parent;
  }
  console.log(JSON.stringify({ caller, nanoidPath, nanoidVersion: nanoidPackage.version }));
  assert.equal(nanoidPackage.version, expectedNanoidVersion, 'Every actual caller must resolve the selected NanoID version');
  // Load the real CommonJS caller, including its actual require("nanoid").
  const loaded = eas(caller);
  assert(loaded && typeof loaded === 'object', `${caller} must load its CommonJS exports`);

  const { nanoid } = callerRequire('nanoid');
  assert.equal(typeof nanoid, 'function', `${caller} requires the named nanoid export`);
  for (const size of [6, 10]) {
    const name = nanoid(size);
    assert.equal(typeof name, 'string');
    assert.equal(name.length, size);
    assert.match(name, /^[A-Za-z0-9_-]+$/, 'Generated names must be URL-safe');
  }
}

const nano = eas('nanoid');
assert.equal(typeof nano.nanoid, 'function');
assert.equal(new Set(nano.urlAlphabet).size, 64);
assert.match(nano.urlAlphabet, /^[A-Za-z0-9_-]{64}$/);
assert.equal(nano.nanoid(0), '');
assert.throws(() => nano.nanoid(-1), RangeError);
for (const size of [6, 10]) {
  for (let i = 0; i < 100; i++) {
    const generated = nano.nanoid(size);
    assert.equal(generated.length, size);
    assert.match(generated, new RegExp(`^[A-Za-z0-9_-]{${size}}$`));
  }
}

(async () => {
  // 1. Dual ESM/CJS resolution check
  const esm = await import(pathToFileURL(eas.resolve('nanoid')).href);
  assert.equal(esm.nanoid, nano.nanoid);
  // The package export condition named default selects index.js; it is not an ESM default export.
  if (expectedNanoidVersion === '6.0.2') assert.equal('default' in esm, false);

  // 2. iOS AscApiKeyUtils pure naming caller
  const ios = eas('./build/credentials/ios/actions/AscApiKeyUtils.js');
  assert.equal(typeof ios.getAscApiKeyName, 'function');
  for (const [purpose, prefix] of [
    [ios.AppStoreApiKeyPurpose.SUBMISSION_SERVICE, '[Expo] EAS Submit '],
    [ios.AppStoreApiKeyPurpose.ASC_APP_CONNECTION, '[Expo] EAS Connect '],
    ['Synthetic', '[Expo] Synthetic '],
  ]) {
    const name = ios.getAscApiKeyName(purpose);
    assert.equal(name.slice(0, prefix.length), prefix);
    assert.match(name.slice(prefix.length), /^[A-Za-z0-9_-]{10}$/);
  }

  // 3. Android BuildCredentialsUtils pure helpers and prompt name generation
  const android = eas('./build/credentials/android/actions/BuildCredentialsUtils.js');
  assert.equal(typeof android.sortBuildCredentials, 'function');
  const sorted = android.sortBuildCredentials([
    { name: 'Beta', isDefault: false },
    { name: 'Alpha', isDefault: false },
    { name: 'Zeta', isDefault: true },
  ]);
  assert.equal(sorted[0].name, 'Zeta');
  assert.equal(sorted[1].name, 'Alpha');
  assert.equal(sorted[2].name, 'Beta');

  const prompts = eas('./build/prompts.js');
  const origPromptAsync = prompts.promptAsync;
  try {
    prompts.promptAsync = async (options) => {
      assert.match(options.initial, /^Build Credentials [A-Za-z0-9_-]{10}$/);
      assert.equal(options.validate(''), false);
      assert.equal(options.validate('Valid Name'), true);
      return { providedName: options.initial };
    };
    const generatedName = await android.promptForNameAsync();
    assert.match(generatedName, /^Build Credentials [A-Za-z0-9_-]{10}$/);
  } finally {
    prompts.promptAsync = origPromptAsync;
  }

  // 4. configs.js nanoid(6) collision fallback & pure helpers
  const configs = eas('./build/commandUtils/new/configs.js');
  assert.equal(typeof configs.findAvailableProjectNameAsync, 'function');
  assert.equal(typeof configs.getAccountChoices, 'function');

  const choices = configs.getAccountChoices({
    id: 'user-1',
    accounts: [
      {
        name: 'team-acc',
        ownerUserActor: { id: 'user-2' },
        viewerUserPermission2: { effectivePermissions: ['PUBLISH'] },
      },
      {
        name: 'personal-acc',
        ownerUserActor: { id: 'user-1' },
        viewerUserPermission2: { effectivePermissions: ['PUBLISH'] },
      },
    ],
  });
  assert(Array.isArray(choices));
  assert.equal(choices.length, 2);
  assert(choices.every(choice => !choice.disabled), 'Synthetic accounts must allow publishing');
  const deniedChoices = configs.getAccountChoices({
    id: 'user-1',
    accounts: [{ name: 'denied', viewerUserPermission2: { effectivePermissions: [] } }],
  });
  assert.equal(deniedChoices[0].disabled, true, 'Missing PUBLISH permission must disable the account');

  const graph = eas('./build/project/fetchOrCreateProjectIDForWriteToConfigWithConfirmationAsync.js');
  const fsExtra = eas('fs-extra');
  const origPathExists = fsExtra.pathExists;
  const origFindProjectId = graph.findProjectIdByAccountNameAndSlugNullableAsync;
  try {
    // Collision case 1: local directory already exists -> triggers nanoid(6) fallback
    fsExtra.pathExists = async () => true;
    graph.findProjectIdByAccountNameAndSlugNullableAsync = async () => null;
    const localCollision = await configs.findAvailableProjectNameAsync(
      'my-project',
      '/synthetic/parent',
      { graphqlClient: {}, projectAccount: 'synthetic-account' }
    );
    assert.match(localCollision.projectName, /^my-project-[A-Za-z0-9_-]{6}$/);
    assert.equal(localCollision.projectDirectory, path.join('/synthetic/parent', localCollision.projectName));

    // Collision case 2: remote project slug already exists -> triggers nanoid(6) fallback
    fsExtra.pathExists = async () => false;
    graph.findProjectIdByAccountNameAndSlugNullableAsync = async () => 'synthetic-existing-id';
    const remoteCollision = await configs.findAvailableProjectNameAsync(
      'my-project',
      '/synthetic/parent',
      { graphqlClient: {}, projectAccount: 'synthetic-account' }
    );
    assert.match(remoteCollision.projectName, /^my-project-[A-Za-z0-9_-]{6}$/);
    assert.equal(remoteCollision.projectDirectory, path.join('/synthetic/parent', remoteCollision.projectName));

    // Non-collision case: neither local nor remote exists -> retains pure baseName
    fsExtra.pathExists = async () => false;
    graph.findProjectIdByAccountNameAndSlugNullableAsync = async () => null;
    const cleanResult = await configs.findAvailableProjectNameAsync(
      'clean-project',
      '/synthetic/parent',
      { graphqlClient: {}, projectAccount: 'synthetic-account' }
    );
    assert.equal(cleanResult.projectName, 'clean-project');
    assert.equal(cleanResult.projectDirectory, path.join('/synthetic/parent', 'clean-project'));
  } finally {
    fsExtra.pathExists = origPathExists;
    graph.findProjectIdByAccountNameAndSlugNullableAsync = origFindProjectId;
  }

  console.log(`EAS_NANOID_CALLER_LOAD_AND_GENERATOR_PASSED nanoid=${expectedNanoidVersion}`);
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
