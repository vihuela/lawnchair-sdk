import { strFromU8, unzipSync } from "fflate";

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

async function readArtifactJob(env: Env, artifact: Artifact): Promise<Record<string, unknown>> {
  const archive = unzipSync(new Uint8Array(await downloadArtifact(env, artifact)));
  const jobName = Object.keys(archive).find(name => name === "job.json" || name.endsWith("/job.json"));
  if (!jobName) throw new Error("CI 产物中缺少 job.json");
  return JSON.parse(strFromU8(archive[jobName])) as Record<string, unknown>;
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
  const progress = await runProgress(env, run);
  if (run.status !== "completed") {
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
    if (run.conclusion === "success") {
      return json({ id: jobId, state: "running", progress: 98, message: "等待 CI 结果归档", log: progress.log, actionsUrl: run.html_url });
    }
    return json({ id: jobId, state: "failed", progress: 100, message: "GitHub Actions 构建失败", error: `CI 结论：${run.conclusion || "unknown"}`, log: progress.log, actionsUrl: run.html_url });
  }

  const job = await readArtifactJob(env, artifact);
  if (job.state !== "completed" || !job.result) {
    return json({ ...job, actionsUrl: run.html_url, log: Array.isArray(job.log) ? job.log : progress.log });
  }
  const result = job.result as Record<string, unknown>;
  result.downloads = [];
  return json({ ...job, state: "completed", progress: 100, message: "GitHub Actions 构建并发布完成", result, actionsUrl: run.html_url });
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
      if (request.method === "GET" && url.pathname === "/api/config") return configResponse(env);
      if (request.method === "GET" && url.pathname === "/api/history") return historyResponse(request, env);
      if (request.method === "POST" && url.pathname === "/api/package-version") return packageVersionResponse(request, env);
      if (request.method === "POST" && url.pathname === "/api/build") return buildResponse(request, env, context);

      const jobMatch = url.pathname.match(/^\/api\/jobs\/([0-9a-f]{12})\/?$/);
      if (request.method === "GET" && jobMatch) return jobResponse(env, jobMatch[1]);
      if (url.pathname.startsWith("/api/")) return json({ error: "接口不存在" }, 404);

      return withSecurityHeaders(await env.ASSETS.fetch(request));
    } catch (error) {
      const message = error instanceof Error ? error.message : "服务端发生未知错误";
      return json({ error: message }, message.includes("包名格式") || message.includes("请求内容") ? 400 : 502);
    }
  },
} satisfies ExportedHandler<Env>;
