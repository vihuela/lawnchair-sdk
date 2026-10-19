// Local CPU comparison only; edge validation is authoritative for the 10 ms limit.
import {readFileSync} from 'node:fs';
import {strFromU8,unzipSync} from 'fflate';
import {artifactGzip} from '../src/index.ts';
const zip=new Uint8Array(readFileSync(process.argv[2]));
for(const [label,fn] of [['original',()=>{const a=unzipSync(zip);const n=Object.keys(a).find(n=>n==='job.json'||n.endsWith('/job.json'));return JSON.parse(strFromU8(a[n]));}],['compressed-transport',()=>artifactGzip(zip)]]){
 const samples=[];for(let i=0;i<200;i++){const start=process.cpuUsage();const j=await fn();JSON.stringify(j);const use=process.cpuUsage(start);samples.push((use.user+use.system)/1000);}samples.sort((a,b)=>a-b);console.log(label,JSON.stringify({count:samples.length,p50:samples[99],p99:samples[197],max:samples[199]}));
}
