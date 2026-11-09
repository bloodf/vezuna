// Run against the installed candidate graph after parent heavy-slot admission.
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
// Same fail-closed guard as eas-compatibility.cjs; install before loading EAS.
const denyNetwork = () => { throw new Error('Network forbidden in compatibility fixtures'); };
require('node:net').Socket.prototype.connect = denyNetwork;
require('node:http').request = denyNetwork;
require('node:https').request = denyNetwork;
globalThis.fetch = denyNetwork;

const easPackage = require.resolve('eas-cli/package.json');
const eas = createRequire(easPackage);
const easVersion = JSON.parse(fs.readFileSync(easPackage, 'utf8')).version;
assert.equal(easVersion, '27.4.1', 'Caller coverage was source-traced for EAS 27.4.1');
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
// Source-traced naming helpers are not exercised here: no private extraction,
// service calls or invented public exports. This proves load/generator boundaries.
console.log('EAS_NANOID_CALLER_LOAD_AND_GENERATOR_PASSED');
