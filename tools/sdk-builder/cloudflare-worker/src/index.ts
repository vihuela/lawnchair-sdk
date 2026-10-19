interface Env {
  ASSETS: Fetcher;
  GITHUB_USERNAME: string;
  GITHUB_TOKEN: string;
  GITHUB_OWNER: string;
  GITHUB_REPOSITORY: string;
  GITHUB_REF: string;
  GITHUB_WORKFLOW: string;
  GITHUB_PACKAGES_URL: string;
}

interface WorkflowRun {
  id: number;
  html_url: string;
  display_title: string;
  status: "queued" | "in_progress" | "completed" | "waiting" | "requested" | "pending";
  conclusion: string | null;
  created_at: string;
  updated_at: string;
  run_started_at: string | null;
  head_sha: string;
  head_branch: string | null;
  run_number: number;
}

interface Artifact {
  id: number;
  name: string;
  archive_download_url: string;
  expired: boolean;
}

const PACKAGE_PATTERN = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/;
const JOB_ID_PATTERN = /^[0-9a-f]{12}$/;
const JSON_HEADERS = {
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store",
};

function json(payload: unknown, status = 200, extraHeaders: HeadersInit = {}): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { ...JSON_HEADERS, ...extraHeaders },
  });
}

async function githubFetch(env: Env, path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/vnd.github+json");
  headers.set("Authorization", `Bearer ${env.GITHUB_TOKEN}`);
  headers.set("X-GitHub-Api-Version", "2022-11-28");
  headers.set("User-Agent", "launcher-packer-worker");
  const response = await fetch(`https://api.github.com${path}`, { ...init, headers });
  if (!response.ok) {
    const detail = (await response.text()).replace(/\s+/g, " ").slice(0, 240);
    throw new Error(`GitHub API 请求失败（HTTP ${response.status}）：${detail}`);
  }
  return response;
}

function repoPath(env: Env, suffix: string): string {
  return `/repos/${encodeURIComponent(env.GITHUB_OWNER)}/${encodeURIComponent(env.GITHUB_REPOSITORY)}${suffix}`;
}

async function readRequestPackage(request: Request): Promise<string> {
  const contentLength = Number(request.headers.get("content-length") || "0");
  if (contentLength > 2048) throw new Error("请求内容过大");
  const payload = await request.json() as { packageName?: unknown };
  const packageName = String(payload.packageName || "").trim();
  if (!PACKAGE_PATTERN.test(packageName)) {
    throw new Error("Android 包名格式无效：至少两段，每段以字母开头，且只能包含字母、数字和下划线");
  }
  return packageName;
}

async function listRunsPage(env: Env, page = 1, perPage = 50): Promise<{ runs: WorkflowRun[]; totalCount: number }> {
  const workflow = encodeURIComponent(env.GITHUB_WORKFLOW);
  const query = new URLSearchParams({ event: "workflow_dispatch", page: String(page), per_page: String(perPage) });
  const response = await githubFetch(env, repoPath(env, `/actions/workflows/${workflow}/runs?${query}`));
  const payload = await response.json() as { total_count?: number; workflow_runs?: WorkflowRun[] };
  return { runs: payload.workflow_runs || [], totalCount: payload.total_count || 0 };
}

async function listRuns(env: Env): Promise<WorkflowRun[]> {
  return (await listRunsPage(env)).runs;
}

async function findRun(env: Env, jobId: string): Promise<WorkflowRun | undefined> {
  const runs = await listRuns(env);
  return runs.find(run => run.display_title?.endsWith(`· ${jobId}`));
}

function parseRunTitle(displayTitle: string): { packageName: string; jobId: string } | null {
  const match = displayTitle.match(/^Launcher SDK · (.+) · ([0-9a-f]{12})$/);
  if (!match || !PACKAGE_PATTERN.test(match[1])) return null;
  return { packageName: match[1], jobId: match[2] };
}

async function historyResponse(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const requestedPage = Number(url.searchParams.get("page") || "1");
  const page = Number.isInteger(requestedPage) ? Math.min(Math.max(requestedPage, 1), 100) : 1;
  const perPage = 20;
  const { runs, totalCount } = await listRunsPage(env, page, perPage);
  const records = runs.flatMap(run => {
    const identity = parseRunTitle(run.display_title || "");
    if (!identity) return [];
    const startedAt = run.run_started_at || run.created_at;
    const endedAt = run.status === "completed" ? run.updated_at : null;
    const durationSeconds = endedAt
      ? Math.max(0, Math.round((Date.parse(endedAt) - Date.parse(startedAt)) / 1000))
      : null;
    return [{
      id: identity.jobId,
      runId: run.id,
      runNumber: run.run_number,
      packageName: identity.packageName,
      status: run.status,
      conclusion: run.conclusion,
      createdAt: run.created_at,
      startedAt,
      endedAt,
      durationSeconds,
      sourceRevision: run.head_sha?.slice(0, 12) || null,
      sourceBranch: run.head_branch,
      actionsUrl: run.html_url,
    }];
  });
  return json({
    page,
    perPage,
    totalCount,
    totalPages: Math.max(1, Math.ceil(totalCount / perPage)),
    records,
  });
}

async function findArtifact(env: Env, runId: number, jobId: string): Promise<Artifact | undefined> {
  const response = await githubFetch(env, repoPath(env, `/actions/runs/${runId}/artifacts?per_page=20`));
  const payload = await response.json() as { artifacts?: Artifact[] };
  return (payload.artifacts || []).find(artifact => artifact.name === `launcher-sdk-${jobId}` && !artifact.expired);
}

async function downloadArtifact(env: Env, artifact: Artifact): Promise<ArrayBuffer> {
  const response = await githubFetch(env, repoPath(env, `/actions/artifacts/${artifact.id}/zip`), {
    headers: { Accept: "application/octet-stream" },
  });
  return response.arrayBuffer();
}

// A complete GitHub ZIP has a central directory even when its local headers
// use data descriptors. Read that directory instead of scanning compressed data.
function reportEntry(data: Uint8Array): { bytes: Uint8Array<ArrayBuffer>; compression: number; size: number; crc: number } | undefined {
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
  for (let end = data.length - 22; end >= Math.max(0, data.length - 65557); end--) {
    if (view.getUint32(end, true) !== 0x06054b50 || end + 22 + view.getUint16(end + 20, true) !== data.length) continue;
    if (view.getUint16(end + 4, true) || view.getUint16(end + 6, true)) throw new Error("不支持分卷 ZIP");
    const count = view.getUint16(end + 10, true);
    let offset = view.getUint32(end + 16, true);
    if (count === 0xffff || offset === 0xffffffff) return undefined; // ZIP64: parser fallback
    const decoder = new TextDecoder();
    for (let i = 0; i < count; i++) {
      if (offset + 46 > end || view.getUint32(offset, true) !== 0x02014b50) throw new Error("CI ZIP 目录损坏");
      const length = view.getUint16(offset + 28, true);
      const next = offset + 46 + length + view.getUint16(offset + 30, true) + view.getUint16(offset + 32, true);
      if (next > end) throw new Error("CI ZIP 目录损坏");
      const name = decoder.decode(data.subarray(offset + 46, offset + 46 + length));
      if (name === "job.json" || name.endsWith("/job.json")) {
        const compression = view.getUint16(offset + 10, true);
        const size = view.getUint32(offset + 24, true);
        const compressedSize = view.getUint32(offset + 20, true);
        const local = view.getUint32(offset + 42, true);
        if (size === 0xffffffff || compressedSize === 0xffffffff || local === 0xffffffff) return undefined;
        if (view.getUint16(offset + 8, true) & 1) throw new Error("不支持加密 ZIP");
        if (local + 30 > offset || view.getUint32(local, true) !== 0x04034b50) throw new Error("CI ZIP 文件头损坏");
        const start = local + 30 + view.getUint16(local + 26, true) + view.getUint16(local + 28, true);
        if (start + compressedSize > offset) throw new Error("CI ZIP 数据不完整");
        return { bytes: Uint8Array.from(data.subarray(start, start + compressedSize)), compression, size, crc: view.getUint32(offset + 16, true) };
      }
      offset = next;
    }
    throw new Error("CI 产物中缺少 job.json");
  }
  return undefined;
}

// ZIP method 8 and gzip use the same DEFLATE payload. Rewrap it using the
// archive's CRC/size trailer; the browser verifies and inflates the report.
// This avoids inflating and parsing the full ABI tables/log on a cold Worker.
export function artifactGzip(data: Uint8Array): Blob | undefined {
  const entry = reportEntry(data);
  if (!entry || entry.compression !== 8) return undefined;
  if (entry.size > 2 * 1024 * 1024) throw new Error("CI 的 job.json 超过 2 MiB 限制");
  const trailer = new Uint8Array(8);
  const view = new DataView(trailer.buffer);
  view.setUint32(0, entry.crc, true);
  view.setUint32(4, entry.size, true);
  return new Blob([new Uint8Array([31,139,8,0,0,0,0,0,0,255]), entry.bytes, trailer]);
}

async function runProgress(env: Env, run: WorkflowRun): Promise<{ progress: number; message: string; log: string[] }> {
  const response = await githubFetch(env, repoPath(env, `/actions/runs/${run.id}/jobs?per_page=20`));
  const payload = await response.json() as {
    jobs?: Array<{
      status: string;
      steps?: Array<{ name: string; status: string; conclusion: string | null }>;
    }>;
  };
  const steps = payload.jobs?.[0]?.steps || [];
  if (!steps.length) return { progress: run.status === "queued" ? 5 : 10, message: "等待 GitHub Runner", log: [] };
  const completeCount = steps.filter(step => step.status === "completed").length;
  const current = steps.find(step => step.status === "in_progress");
  const progress = Math.min(96, 10 + Math.round((completeCount / steps.length) * 86));
  const log = steps.map(step => {
    const icon = step.status === "completed" ? (step.conclusion === "success" ? "✓" : "✕") : step.status === "in_progress" ? "…" : "○";
    return `${icon} ${step.name}`;
  });
  return { progress, message: current?.name || "整理 CI 构建结果", log };
}

async function configResponse(env: Env): Promise<Response> {
  let sdkVersion = "0.1";
  try {
    const path = repoPath(env, `/contents/launcher-core/build.gradle?ref=${encodeURIComponent(env.GITHUB_REF)}`);
    const sourceResponse = await githubFetch(env, path, { headers: { Accept: "application/vnd.github.raw+json" } });
    const source = await sourceResponse.text();
    sdkVersion = source.match(/orElse\("([^"-]+)(?:-local)?"\)/)?.[1] || sdkVersion;
  } catch (_) {
    // The build itself performs the authoritative version check.
  }
  return json({
    sdkVersion,
    versionSource: `${env.GITHUB_OWNER}/${env.GITHUB_REPOSITORY}@${env.GITHUB_REF}`,
    buildBackend: "github-actions",
    githubPackages: {
      owner: env.GITHUB_OWNER,
      repository: env.GITHUB_REPOSITORY,
      url: env.GITHUB_PACKAGES_URL,
      configured: Boolean(env.GITHUB_USERNAME && env.GITHUB_TOKEN),
      credentialSource: "cloudflare-secret",
    },
    source: {
      mode: "github-actions",
      owner: env.GITHUB_OWNER,
      repository: env.GITHUB_REPOSITORY,
      ref: env.GITHUB_REF,
      display: `${env.GITHUB_OWNER}/${env.GITHUB_REPOSITORY}@${env.GITHUB_REF}`,
    },
  });
}

async function packageVersionResponse(request: Request, env: Env): Promise<Response> {
  const packageName = await readRequestPackage(request);
  const artifactId = `${packageName}-launcher-sdk`;
  const metadataUrl = `${env.GITHUB_PACKAGES_URL}/com/launcher/generated/${encodeURIComponent(artifactId)}/maven-metadata.xml`;
  const authorization = btoa(`${env.GITHUB_USERNAME}:${env.GITHUB_TOKEN}`);
  const response = await fetch(metadataUrl, { headers: { Authorization: `Basic ${authorization}`, Accept: "application/xml" } });
  if (response.status === 404) {
    return json({ latestVersion: null, nextVersion: "0.1", exists: false, coordinate: `com.launcher.generated:${artifactId}:0.1` });
  }
  if (!response.ok) throw new Error(`GitHub Packages 版本查询失败（HTTP ${response.status}）`);
  const metadata = await response.text();
  const versions = [...metadata.matchAll(/<version>0\.(\d+)<\/version>/g)]
    .map(match => Number(match[1]))
    .filter(Number.isFinite)
    .sort((left, right) => left - right);
  const latestNumber = versions.at(-1) || 0;
  const latestVersion = latestNumber ? `0.${latestNumber}` : null;
  const nextVersion = `0.${latestNumber + 1}`;
  return json({
    latestVersion,
    nextVersion,
    exists: Boolean(latestVersion),
    coordinate: `com.launcher.generated:${artifactId}:${nextVersion}`,
  });
}

async function buildResponse(request: Request, env: Env, context: ExecutionContext): Promise<Response> {
  const packageName = await readRequestPackage(request);
  const activeRuns = (await listRuns(env)).filter(run => run.status !== "completed");
  if (activeRuns.length >= 3) {
    return json({ error: "当前已有 3 个 CI 构建任务，请稍后再试" }, 429, { "Retry-After": "60" });
  }

  const clientAddress = request.headers.get("CF-Connecting-IP") || "local";
  const cooldownKey = new Request(`https://launcher-packer-rate.invalid/${encodeURIComponent(clientAddress)}`);
  const rateCache = await caches.open("launcher-packer-build-rate");
  if (await rateCache.match(cooldownKey)) {
    return json({ error: "同一访问者每 30 秒只能创建一个构建任务" }, 429, { "Retry-After": "30" });
  }

  const jobId = crypto.randomUUID().replaceAll("-", "").slice(0, 12);
  const workflow = encodeURIComponent(env.GITHUB_WORKFLOW);
  await githubFetch(env, repoPath(env, `/actions/workflows/${workflow}/dispatches`), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      ref: env.GITHUB_REF,
      inputs: { package_name: packageName, job_id: jobId },
    }),
  });
  context.waitUntil(rateCache.put(cooldownKey, new Response("1", { headers: { "Cache-Control": "max-age=30" } })));
  return json({
    id: jobId,
    packageName,
    sdkVersion: "0.1",
    state: "queued",
    progress: 2,
    message: "已提交 GitHub Actions",
    log: [],
  }, 202);
}

async function jobResponse(env: Env, jobId: string): Promise<Response> {
  const run = await findRun(env, jobId);
  if (!run) {
    return json({ id: jobId, state: "queued", progress: 3, message: "等待 GitHub Actions 创建任务", log: [] });
  }
  if (run.status !== "completed") {
    const progress = await runProgress(env, run);
    return json({
      id: jobId,
      state: "running",
      progress: progress.progress,
      message: progress.message,
      log: progress.log,
      actionsUrl: run.html_url,
      sourceDisplay: `${env.GITHUB_OWNER}/${env.GITHUB_REPOSITORY}@${env.GITHUB_REF}`,
    });
  }

  const artifact = await findArtifact(env, run.id, jobId);
  if (!artifact) {
    const progress = await runProgress(env, run);
    if (run.conclusion === "success") {
      return json({
        id: jobId,
        state: "completed",
        progress: 100,
        message: "构建成功，但详细报告已超过保留期",
        resultAvailable: false,
        log: progress.log,
        actionsUrl: run.html_url,
      });
    }
    return json({ id: jobId, state: "failed", progress: 100, message: "GitHub Actions 构建失败", error: `CI 结论：${run.conclusion || "unknown"}`, log: progress.log, actionsUrl: run.html_url });
  }

  // All clients now use the same transport, including the old unqualified URL.
  // Report JSON is decoded and normalized in the browser, never in this route.
  const data = new Uint8Array(await downloadArtifact(env, artifact));
  const compressed = artifactGzip(data);
  const headers = { ...JSON_HEADERS, "X-Launcher-Report": "artifact", "X-Actions-Url": run.html_url };
  if (compressed) {
    return new Response(compressed, {
      headers: { ...headers, "Content-Encoding": "gzip" },
      encodeBody: "manual",
    });
  }
  const stored = reportEntry(data);
  if (stored?.compression === 0) {
    if (stored.size > 2 * 1024 * 1024 || stored.bytes.length !== stored.size) throw new Error("CI 报告大小无效");
    return new Response(stored.bytes, { headers });
  }
  throw new Error("CI 报告需要使用标准 ZIP 的 stored 或 DEFLATE 格式");
}

async function withSecurityHeaders(response: Response): Promise<Response> {
  const secured = new Response(response.body, response);
  secured.headers.set("X-Content-Type-Options", "nosniff");
  secured.headers.set("Referrer-Policy", "same-origin");
  secured.headers.set("X-Frame-Options", "DENY");
  secured.headers.set("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'");
  return secured;
}

export default {
  async fetch(request: Request, env: Env, context: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    try {
      if (request.method === "GET" && url.pathname === "/api/config") return await configResponse(env);
      if (request.method === "GET" && url.pathname === "/api/history") return await historyResponse(request, env);
      if (request.method === "POST" && url.pathname === "/api/package-version") return await packageVersionResponse(request, env);
      if (request.method === "POST" && url.pathname === "/api/build") return await buildResponse(request, env, context);

      const jobMatch = url.pathname.match(/^\/api\/jobs\/([0-9a-f]{12})\/?$/);
      if (request.method === "GET" && jobMatch) return await jobResponse(env, jobMatch[1]);
      if (url.pathname.startsWith("/api/")) return json({ error: "接口不存在" }, 404);

      return withSecurityHeaders(await env.ASSETS.fetch(request));
    } catch (error) {
      const message = error instanceof Error ? error.message : "服务端发生未知错误";
      return json({ error: message }, message.includes("包名格式") || message.includes("请求内容") ? 400 : 502);
    }
  },
} satisfies ExportedHandler<Env>;
