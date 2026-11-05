// Run after npm ci: node tools/release/eas-compatibility.cjs
// All keys/certificates are disposable synthetic data held only in memory.
'use strict';
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
// Fail closed if a future dependency starts network activity during these checks.
const denyNetwork = () => { throw new Error('Network forbidden in compatibility fixtures'); };
require('node:net').Socket.prototype.connect = denyNetwork;
require('node:http').request = denyNetwork;
require('node:https').request = denyNetwork;
globalThis.fetch = denyNetwork;

const eas = createRequire(require.resolve('eas-cli/package.json'));
const easJson = createRequire(eas.resolve('@expo/eas-json/package.json'));
const forge = eas('node-forge');
const pkcs12 = eas('@expo/pkcs12');
const signing = eas('@expo/code-signing-certificates');
const p12Certificate = eas('./build/credentials/ios/utils/p12Certificate.js');

const config = JSON.parse(fs.readFileSync(path.join(__dirname, '../../eas.json'), 'utf8'));
const { EasJsonSchema } = easJson('./build/schema.js');
const { ResolvedIosSubmitProfileSchema, AndroidSubmitProfileSchema } =
  easJson('./build/submit/schema.js');
const valid = EasJsonSchema.validate(config);
assert.ifError(valid.error);
assert.equal(valid.value.submit.production.android.track, 'internal');
assert.equal(valid.value.submit.production.android.releaseStatus, 'draft');
assert.equal(valid.value.submit.production.ios.ascApiKeyPath, '$ASC_API_KEY_PATH');
const invalidConfig = structuredClone(config);
invalidConfig.submit.production.android.releaseStatus = 'invalid-status';
assert(EasJsonSchema.validate(invalidConfig).error);
assert(AndroidSubmitProfileSchema.validate({ releaseStatus: 'draft', rollout: 0.5 }).error);
assert.ifError(AndroidSubmitProfileSchema.validate({ releaseStatus: 'inProgress', rollout: 0.5 }).error);
const resolvedIos = {
  ascApiKeyPath: '/synthetic/unused.p8', ascApiKeyId: 'SYNTH12345',
  ascApiKeyIssuerId: '00000000-0000-4000-8000-000000000001',
  ascAppId: '1234567890', appleTeamId: 'SYNTH12345', appleId: 'fixture@example.com',
};
assert.ifError(ResolvedIosSubmitProfileSchema.validate(resolvedIos).error);
assert(ResolvedIosSubmitProfileSchema.validate({ ...resolvedIos, appleId: 'invalid-email' }).error);
assert(ResolvedIosSubmitProfileSchema.validate({ ...resolvedIos, ascApiKeyId: 'lowercase' }).error);
console.log('EAS schemas accept valid profiles and reject invalid status, rollout, email and key ID');

const minimatch = eas('minimatch');
assert.equal(typeof minimatch, 'function');
assert.equal(minimatch('app.vezuna', 'app.*'), true);
assert.equal(minimatch('other.vezuna', 'app.*'), false);
const { nanoid } = eas('nanoid');
for (const size of [6, 10]) assert.match(nanoid(size), new RegExp(`^[A-Za-z0-9_-]{${size}}$`));

// Generate a self-signed test certificate with Node, then exercise Expo's Forge callers.
const { privateKey, publicKey } = crypto.generateKeyPairSync('rsa', {
  modulusLength: 2048,
  privateKeyEncoding: { type: 'pkcs1', format: 'pem' },
  publicKeyEncoding: { type: 'pkcs1', format: 'pem' },
});
const keyPair = signing.convertKeyPairPEMToKeyPair({ privateKeyPEM: privateKey, publicKeyPEM: publicKey });
const now = Date.now();
const certificate = signing.generateSelfSignedCodeSigningCertificate({
  keyPair, validityNotBefore: new Date(now - 60000),
  validityNotAfter: new Date(now + 86400000), commonName: 'Synthetic EAS fixture',
});
certificate.serialNumber = '00abcdef';
certificate.sign(keyPair.privateKey, forge.md.sha256.create());
signing.validateSelfSignedCertificate(certificate, keyPair);
const pem = signing.convertCertificateToCertificatePEM(certificate);
assert.equal(signing.convertCertificatePEMToCertificate(pem).subject.getField('CN').value,
  'Synthetic EAS fixture');
assert.equal(forge.pki.certificateFromPem(Buffer.from(pem)).serialNumber, '00abcdef');
assert.throws(() => forge.pki.certificateFromPem(Buffer.from('invalid PEM')));
const message = Buffer.from('synthetic release fixture');
const signature = signing.signBufferRSASHA256AndVerify(keyPair.privateKey, certificate, message);
assert.equal(crypto.verify('sha256', message, publicKey, Buffer.from(signature, 'base64')), true);
assert.equal(crypto.verify('sha256', Buffer.from('tampered'), publicKey, Buffer.from(signature, 'base64')), false);
const forged = forge.pki.certificateFromPem(pem);
assert.equal(certificate.verify(forged), true);
forged.signature = '\0'.repeat(forged.signature.length);
assert.throws(() => certificate.verify(forged), /Encryption block is invalid/);

const certDer = Buffer.from(forge.asn1.toDer(forge.pki.certificateToAsn1(certificate)).getBytes(), 'binary');
const expectedFingerprint = crypto.createHash('sha1').update(certDer).digest('hex').toUpperCase();
{
  const password = 'synthetic-password';
  const p12 = forge.pkcs12.toPkcs12Asn1(keyPair.privateKey, [certificate], password, {
    algorithm: 'aes256', count: 2048, friendlyName: 'synthetic',
  });
  const bytes = Buffer.from(forge.asn1.toDer(p12).getBytes(), 'binary');
  for (const input of [bytes, bytes.toString('base64')]) {
    assert.equal(p12Certificate.getCertData(input, password).serialNumber, 'ABCDEF');
    assert.equal(p12Certificate.getP12CertFingerprint(input, password), expectedFingerprint);
    const parsed = pkcs12.parsePKCS12(input, password);
    assert.equal(pkcs12.getFormattedSerialNumber(pkcs12.getX509Certificate(parsed)), 'ABCDEF');
  }
  assert.throws(() => p12Certificate.getCertData(bytes, 'wrong-password'), /password|MAC/i);
  // Forge 1.4.0 derives a constant 20-byte 0x03 MAC key when iterations is zero.
  // Forge a matching MAC without deriving a key from the password.
  p12.value[2].value[2].value = forge.asn1.integerToDer(0).getBytes();
  p12.value[2].value[0].value[1].value = crypto.createHmac('sha1', Buffer.alloc(20, 3))
    .update(Buffer.from(p12.value[1].value[1].value[0].value, 'binary')).digest('latin1');
  const zeroIterations = Buffer.from(forge.asn1.toDer(p12).getBytes(), 'binary');
  assert.throws(() => p12Certificate.getCertData(zeroIterations, password), /iteration count must be >= 1/);
  assert.throws(() => pkcs12.parsePKCS12(zeroIterations, password), /iteration count must be >= 1/);
}
// EAS uses null for an absent password; Expo's parser uses the empty string.
// These are different PKCS#12 MAC encodings in both the old and patched Forge.
for (const password of [null, '']) {
  const p12 = forge.pkcs12.toPkcs12Asn1(keyPair.privateKey, [certificate], password, { algorithm: 'aes256' });
  const bytes = Buffer.from(forge.asn1.toDer(p12).getBytes(), 'binary');
  const parsed = password === null
    ? p12Certificate.getCertData(bytes, '')
    : pkcs12.getX509Certificate(pkcs12.parsePKCS12(bytes, ''));
  assert.equal(parsed.subject.getField('CN').value, 'Synthetic EAS fixture');
}
assert.throws(() => p12Certificate.getCertData(Buffer.from('invalid DER'), 'synthetic'));
console.log('Expo certificate/Buffer PEM and EAS/Expo P12 checks pass; forged signature, bad password and zero iterations rejected');
// Exercise tar's create/list stream APIs against this source file, without extraction.
(async () => {
  const { parsedYamlFromWorkflowContents } = eas('./build/commandUtils/workflow/parse.js');
  const steps = createRequire(eas.resolve('@expo/steps/package.json'));
  const { readRawBuildConfigAsync } = steps('./dist/BuildConfig.js');
  const { BuildConfigYAMLError } = steps('./dist/errors.js');
  const dir = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'vezuna-yaml-'));
  try {
    const file = path.join(dir, 'build.yml');
    const normal = 'defaults: &defaults\n  image: latest\njob:\n  <<: *defaults\n  steps: [checkout, test]\n';
    const expected = { defaults: { image: 'latest' }, job: { image: 'latest', steps: ['checkout', 'test'] } };
    assert.deepEqual(parsedYamlFromWorkflowContents({ yamlConfig: normal }), expected);
    fs.writeFileSync(file, 'build:\n  steps: [checkout, test]\n');
    assert.deepEqual(await readRawBuildConfigAsync(file), { build: { steps: ['checkout', 'test'] } });
    const hostile = '['.repeat(5000) + '1' + ']'.repeat(5000);
    assert.throws(() => parsedYamlFromWorkflowContents({ yamlConfig: hostile }), BuildConfigYAMLError);
    fs.writeFileSync(file, hostile);
    await assert.rejects(readRawBuildConfigAsync(file), BuildConfigYAMLError);
    console.log('EAS workflow and Expo build YAML accept normal input and reject deep input with YAMLParseError');
  } finally {
    fs.rmSync(dir, { recursive: true });
  }
  const tar = eas('tar');
  const chunks = [];
  for await (const chunk of tar.c({ cwd: __dirname }, ['eas-compatibility.cjs'])) chunks.push(chunk);
  const entries = [];
  const listing = tar.t({ onReadEntry: entry => entries.push(entry.path) });
  const finished = require('node:events').once(listing, 'end');
  listing.end(Buffer.concat(chunks));
  await finished;
  assert.deepEqual(entries, ['eas-compatibility.cjs']);
  console.log('EAS tar create/list stream round trip passed');
  console.log('EAS_COMPATIBILITY_PASSED');
})().catch(error => { console.error(error); process.exitCode = 1; });
