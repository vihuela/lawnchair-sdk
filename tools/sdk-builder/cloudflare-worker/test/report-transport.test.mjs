import { test } from 'node:test';
import assert from 'node:assert/strict';
import { gunzipSync } from 'node:zlib';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { zipSync, strToU8, Zip, ZipDeflate } from 'fflate';
import worker, { artifactGzip } from '../src/index.ts';

const job = { id: 'abcdef123456', state: 'completed', progress: 80, message: 'old',
  log: ['all original logs'], result: { downloads: ['old'], coordinate: 'test', abi: Array(4000).fill('ABI 中文 "quoted" \\ path') } };
const payload = JSON.stringify(job);

test('raw DEFLATE transport preserves every report byte and verifies gzip CRC', async () => {
  for (const level of [1, 6, 9]) {
    const zip = zipSync({ 'nested/job.json': [strToU8(payload), { level }] });
    const gzip = new Uint8Array(await artifactGzip(zip).arrayBuffer());
    assert.equal(gunzipSync(gzip).toString(), payload);
    gzip[gzip.length - 8] ^= 1;
    assert.throws(() => gunzipSync(gzip));
  }
  const chunks = [];
  const zip = new Zip((error, chunk) => { if (error) throw error; chunks.push(chunk); });
  const file = new ZipDeflate('job.json'); zip.add(file); file.push(strToU8(payload), true); zip.end();
  const compressed = artifactGzip(Buffer.concat(chunks));
  assert.equal(gunzipSync(new Uint8Array(await compressed.arrayBuffer())).toString(), payload);
});

test('stored ZIP uses the existing extraction fallback and excessive reports are rejected', () => {
  assert.equal(artifactGzip(zipSync({ 'job.json': [strToU8(payload), { level: 0 }] })), undefined);
  assert.throws(() => artifactGzip(zipSync({ 'job.json': new Uint8Array(2 * 1024 * 1024 + 1) })), /2 MiB/);
  const truncated = zipSync({ 'job.json': strToU8(payload) }).slice(0, -3);
  assert.equal(artifactGzip(truncated), undefined);
});

test('browser artifact mode avoids step queries and produces the original normalized UI data', async t => {
  const realFetch = globalThis.fetch; t.after(() => { globalThis.fetch = realFetch; });
  const urls = [];
  globalThis.fetch = async url => {
    urls.push(url);
    if (url.includes('/artifacts/8/zip')) return new Response(zipSync({ 'job.json': strToU8(payload) }));
    if (url.includes('/artifacts?')) return Response.json({ artifacts: [{ id: 8, name: 'launcher-sdk-abcdef123456', expired: false }] });
    if (url.includes('/runs?')) return Response.json({ workflow_runs: [{ id: 1, display_title: 'Launcher SDK · test.app · abcdef123456', status: 'completed', html_url: 'https://github.com/example/runs/1' }] });
    throw new Error('unexpected upstream call: ' + url);
  };
  const response = await worker.fetch(new Request('https://example/api/jobs/abcdef123456?format=artifact'),
    { GITHUB_OWNER: 'owner', GITHUB_REPOSITORY: 'repo', GITHUB_WORKFLOW: 'build.yml' }, {});
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('content-encoding'), 'gzip');
  const decoded = JSON.parse(gunzipSync(new Uint8Array(await response.arrayBuffer())));
  assert.deepEqual(decoded, job); assert.equal(urls.length, 3);
  const html = readFileSync(new URL('../../static/index.html', import.meta.url), 'utf8');
  const source = html.slice(html.indexOf('    function normalizeJob('), html.indexOf('    async function loadHistoryResult'));
  const normalize = runInNewContext(source + '\nnormalizeJob');
  const normalized = normalize(decoded, response);
  assert.equal(normalized.progress, 100); assert.equal(normalized.message, 'GitHub Actions 构建并发布完成');
  assert.equal(normalized.actionsUrl, 'https://github.com/example/runs/1');
  assert.equal(normalized.result.downloads.length, 0); assert.deepEqual(normalized.log, job.log);
  assert.deepEqual(normalized.result.abi, job.result.abi);
  const failure = { state: 'failed', error: 'failed build', log: ['failure'] };
  assert.equal(normalize(failure, response).state, 'failed');
});

test('rejects invalid central-directory offsets and encrypted report entries', () => {
  const original = zipSync({ 'job.json': strToU8(payload) });
  const directory = new DataView(original.buffer).getUint32(original.length - 6, true);
  for (const corrupt of [
    view => view.setUint32(directory + 42, original.length, true),
    view => view.setUint32(directory + 20, original.length, true),
    view => view.setUint16(directory + 8, 1, true),
  ]) {
    const data = original.slice(); corrupt(new DataView(data.buffer));
    assert.throws(() => artifactGzip(data));
  }
});
