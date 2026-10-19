import {test} from 'node:test';
import assert from 'node:assert/strict';
import {zipSync,strToU8} from 'fflate';
import worker, {extractArtifactJob} from '../src/index.ts';
const job={id:'abcdef123456',state:'completed',log:['done'],result:{coordinate:'test',downloads:['old']}};
const archive=zipSync({'job.json':strToU8(JSON.stringify(job))});
test('extracts report while skipping an unrelated large compressed file',async()=>{
  const zip=zipSync({'sdk.bin':new Uint8Array(16*1024*1024),'nested/job.json':strToU8(JSON.stringify(job))});
  assert.deepEqual(JSON.parse(await extractArtifactJob(zip)),job);
});
test('rejects missing, corrupt and oversized reports',async()=>{
 await assert.rejects(()=>extractArtifactJob(zipSync({'other.txt':strToU8('x')})),/缺少/);
 await assert.rejects(()=>extractArtifactJob(new Uint8Array([1,2,3])));
 await assert.rejects(()=>extractArtifactJob(zipSync({'job.json':new Uint8Array(2*1024*1024+1)})),/2 MiB/);
});
test('completed job is unchanged and second lookup avoids artifact download',async t=>{
 const realFetch=globalThis.fetch,realCaches=globalThis.caches;t.after(()=>{globalThis.fetch=realFetch;globalThis.caches=realCaches;});
 const map=new Map();globalThis.caches={open:async()=>({match:async k=>map.get(k.url)?.clone(),put:async(k,r)=>map.set(k.url,r)})};
 let downloads=0;globalThis.fetch=async(url,init)=>{
  assert.notEqual(init?.method,'POST');
  if(url.includes('/artifacts/8/zip')){downloads++;return new Response(archive);}
  if(url.includes('/artifacts?'))return Response.json({artifacts:[{id:8,name:'launcher-sdk-abcdef123456',expired:false}]});
  if(url.includes('/jobs?'))return Response.json({jobs:[]});
  if(url.includes('/runs?'))return Response.json({workflow_runs:[{id:1,display_title:'Launcher SDK · test.app · abcdef123456',status:'completed',conclusion:'success',html_url:'https://github.com/example/runs/1'}]});
  throw new Error('unexpected '+url);
 };
 const env={GITHUB_OWNER:'owner',GITHUB_REPOSITORY:'repo',GITHUB_TOKEN:'fixture',GITHUB_WORKFLOW:'build.yml'};
 const pending=[],ctx={waitUntil:p=>pending.push(p)};
 const req=new Request('https://packer.example/api/jobs/abcdef123456');
 const first=await worker.fetch(req,env,ctx);assert.equal(first.status,200);const a=await first.json();await Promise.all(pending);
 const second=await worker.fetch(req,env,ctx);assert.deepEqual(await second.json(),a);assert.equal(a.state,'completed');assert.deepEqual(a.result.downloads,[]);assert.equal(downloads,1);
});
test('asynchronous upstream failures return JSON errors',async t=>{
 const real=globalThis.fetch;t.after(()=>{globalThis.fetch=real;});globalThis.fetch=async()=>new Response('failed',{status:503});
 const r=await worker.fetch(new Request('https://packer.example/api/history'),{GITHUB_WORKFLOW:'build.yml'},{});
 assert.equal(r.status,502);assert.match((await r.json()).error,/503/);
});

test('build submission still dispatches exactly one workflow with the requested package',async t=>{
 const realFetch=globalThis.fetch,realCaches=globalThis.caches;t.after(()=>{globalThis.fetch=realFetch;globalThis.caches=realCaches;});
 const requests=[];globalThis.fetch=async(url,init)=>{requests.push({url,init});return url.includes('/dispatches')?new Response(null,{status:204}):Response.json({workflow_runs:[]});};
 const pending=[];globalThis.caches={open:async()=>({match:async()=>undefined,put:async()=>{}})};
 const r=await worker.fetch(new Request('https://packer.example/api/build',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({packageName:'com.example.test'})}),{GITHUB_OWNER:'owner',GITHUB_REPOSITORY:'repo',GITHUB_WORKFLOW:'build.yml',GITHUB_REF:'test-branch',GITHUB_TOKEN:'fixture'},{waitUntil:p=>pending.push(p)});
 const d=await r.json();await Promise.all(pending);assert.equal(r.status,202);assert.match(d.id,/^[0-9a-f]{12}$/);
 const dispatches=requests.filter(r=>r.init?.method==='POST');assert.equal(dispatches.length,1);assert.deepEqual(JSON.parse(dispatches[0].init.body),{ref:'test-branch',inputs:{package_name:'com.example.test',job_id:d.id}});
});

test('uncompressed and streaming ZIP reports work with the native extraction path',async()=>{
 const {Zip,ZipDeflate}=await import('fflate');
 assert.deepEqual(JSON.parse(await extractArtifactJob(zipSync({'job.json':[strToU8(JSON.stringify(job)),{level:0}]}))),job);
 const chunks=[];const zip=new Zip((error,data)=>{if(error)throw error;chunks.push(data);});const file=new ZipDeflate('nested/job.json');zip.add(file);file.push(strToU8(JSON.stringify(job)),true);zip.end();
 const data=new Uint8Array(chunks.reduce((sum,c)=>sum+c.length,0));let offset=0;for(const c of chunks){data.set(c,offset);offset+=c.length;}
 assert.deepEqual(JSON.parse(await extractArtifactJob(data)),job);
});
