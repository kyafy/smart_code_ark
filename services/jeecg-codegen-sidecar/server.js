const fs = require('fs');
const path = require('path');
const http = require('http');

const PORT = Number(process.env.PORT || 19090);
const WORKSPACE_ROOT = process.env.JEECG_WORKSPACE_ROOT || '/tmp/smartark';
const UPSTREAM_BASE_URL = String(process.env.JEECG_UPSTREAM_BASE_URL || '').trim();
const LOGIN_PATH = process.env.JEECG_LOGIN_PATH || '/sys/login';
const CODEGEN_PATH = process.env.JEECG_CODEGEN_PATH || '/online/cgform/api/codeGenerate';
const TOKEN_HEADER = process.env.JEECG_TOKEN_HEADER || 'X-Access-Token';
const TOKEN_JSON_PATH = process.env.JEECG_TOKEN_JSON_PATH || 'result.token';
const ACCESS_TOKEN = String(process.env.JEECG_ACCESS_TOKEN || '').trim();
const USERNAME = String(process.env.JEECG_USERNAME || '').trim();
const PASSWORD = String(process.env.JEECG_PASSWORD || '').trim();
const REQUEST_TIMEOUT_MS = parseIntSafe(process.env.JEECG_REQUEST_TIMEOUT_MS, 45000);
const MAX_SCAN_FILES = parseIntSafe(process.env.JEECG_MAX_SCAN_FILES, 20000);
const MAX_SCAN_DEPTH = parseIntSafe(process.env.JEECG_MAX_SCAN_DEPTH, 12);

function parseIntSafe(value, fallback) {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : fallback;
}

function json(res, statusCode, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
  });
  res.end(body);
}

function normalizePosix(p) {
  return String(p || '').replace(/\\/g, '/').replace(/\/+/g, '/').replace(/^\.\/+/, '');
}

function isSubPath(parentAbs, childAbs) {
  const rel = path.relative(parentAbs, childAbs);
  return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel));
}

function safeWorkspaceDir(requested) {
  const rootAbs = path.resolve(WORKSPACE_ROOT);
  if (!requested || String(requested).trim() === '') {
    return rootAbs;
  }
  const requestedAbs = path.resolve(String(requested));
  if (isSubPath(rootAbs, requestedAbs)) {
    return requestedAbs;
  }
  return path.resolve(rootAbs, path.basename(requestedAbs));
}

function joinUrl(baseUrl, reqPath) {
  const base = String(baseUrl || '').trim().replace(/\/+$/, '');
  const suffix = `/${String(reqPath || '').trim().replace(/^\/+/, '')}`;
  return `${base}${suffix}`;
}

function deepGet(obj, dottedPath) {
  if (!obj || !dottedPath) {
    return undefined;
  }
  return String(dottedPath)
    .split('.')
    .filter(Boolean)
    .reduce((acc, key) => (acc && typeof acc === 'object' ? acc[key] : undefined), obj);
}

function pickString(...candidates) {
  for (const raw of candidates) {
    if (raw === null || raw === undefined) {
      continue;
    }
    const value = String(raw).trim();
    if (value) {
      return value;
    }
  }
  return '';
}

function pickObject(value) {
  return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let raw = '';
    req.on('data', (chunk) => {
      raw += chunk;
      if (raw.length > 4 * 1024 * 1024) {
        reject(new Error('payload too large'));
        req.destroy();
      }
    });
    req.on('end', () => {
      if (!raw.trim()) {
        resolve({});
        return;
      }
      try {
        resolve(JSON.parse(raw));
      } catch (err) {
        reject(err);
      }
    });
    req.on('error', reject);
  });
}

async function fetchWithTimeout(url, options, timeoutMs) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, {
      ...options,
      signal: controller.signal,
    });
    const text = await response.text();
    return { response, text };
  } finally {
    clearTimeout(timeout);
  }
}

function extractSetCookies(headers) {
  if (!headers) {
    return [];
  }
  if (typeof headers.getSetCookie === 'function') {
    return headers.getSetCookie();
  }
  const single = headers.get('set-cookie');
  return single ? [single] : [];
}

async function resolveJeecgAuth() {
  if (ACCESS_TOKEN) {
    return {
      token: ACCESS_TOKEN,
      cookie: '',
      authMode: 'static-token',
    };
  }
  if (!USERNAME || !PASSWORD) {
    return {
      token: '',
      cookie: '',
      authMode: 'none',
    };
  }
  if (!UPSTREAM_BASE_URL) {
    throw new Error('JEECG_UPSTREAM_BASE_URL is empty');
  }
  const loginUrl = joinUrl(UPSTREAM_BASE_URL, LOGIN_PATH);
  const body = JSON.stringify({
    username: USERNAME,
    password: PASSWORD,
  });
  const { response, text } = await fetchWithTimeout(
    loginUrl,
    {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body,
    },
    Math.min(REQUEST_TIMEOUT_MS, 15000)
  );
  let parsed = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch (_err) {
    parsed = null;
  }
  const token = pickString(deepGet(parsed, TOKEN_JSON_PATH), deepGet(parsed, 'result'));
  const cookies = extractSetCookies(response.headers)
    .map((item) => String(item).split(';')[0].trim())
    .filter(Boolean);
  if (!response.ok) {
    throw new Error(`jeecg login failed status=${response.status} body=${truncate(text, 400)}`);
  }
  if (!token && cookies.length === 0) {
    throw new Error(`jeecg login succeeded but token/cookie missing, body=${truncate(text, 400)}`);
  }
  return {
    token,
    cookie: cookies.join('; '),
    authMode: token ? 'login-token' : 'login-cookie',
  };
}

function truncate(value, maxLen) {
  if (!value || value.length <= maxLen) {
    return value || '';
  }
  return `${value.slice(0, maxLen)}...`;
}

function objectToFormData(data) {
  const out = new URLSearchParams();
  for (const [key, value] of Object.entries(data || {})) {
    if (value === null || value === undefined) {
      continue;
    }
    if (typeof value === 'object') {
      out.set(key, JSON.stringify(value));
    } else {
      out.set(key, String(value));
    }
  }
  return out.toString();
}

function extractParams(payload) {
  const jeecg = pickObject(payload.jeecg);
  const extras = pickObject(jeecg.extraParams);
  const params = {
    id: pickString(jeecg.id, jeecg.formId, jeecg.cgformId, jeecg.code),
    formId: pickString(jeecg.formId, jeecg.id, jeecg.cgformId),
    code: pickString(jeecg.code, jeecg.formId, jeecg.id, payload.templateId),
    cgformId: pickString(jeecg.cgformId, jeecg.formId, jeecg.id),
    tableName: pickString(jeecg.tableName),
    projectPath: pickString(jeecg.projectPath, payload.workspaceDir),
    packageName: pickString(jeecg.packageName, jeecg.entityPackage),
    entityPackage: pickString(jeecg.entityPackage, jeecg.packageName),
    bussiPackage: pickString(jeecg.bussiPackage),
    moduleName: pickString(jeecg.moduleName),
    stylePath: pickString(jeecg.stylePath, jeecg.templateStyle),
    templateStyle: pickString(jeecg.templateStyle, jeecg.stylePath),
    vueStyle: pickString(jeecg.vueStyle),
  };
  const merged = { ...params, ...extras };
  const cleaned = {};
  for (const [key, value] of Object.entries(merged)) {
    if (value === null || value === undefined) {
      continue;
    }
    if (typeof value === 'string' && value.trim() === '') {
      continue;
    }
    cleaned[key] = value;
  }
  return cleaned;
}

function buildCandidates(payload) {
  const jeecg = pickObject(payload.jeecg);
  const request = pickObject(jeecg.request);
  const params = extractParams(payload);
  const codegenPath = pickString(jeecg.codegenPath, CODEGEN_PATH);
  const candidates = [];

  if (request.path || request.url || request.body || request.method) {
    candidates.push({
      name: 'explicit',
      method: pickString(request.method, 'POST').toUpperCase(),
      path: pickString(request.path, request.url, codegenPath),
      contentType: pickString(request.contentType, request.headers && request.headers['Content-Type'], 'application/json'),
      body: request.body === undefined ? params : request.body,
      headers: pickObject(request.headers),
      query: pickObject(request.query),
    });
  }

  candidates.push({
    name: 'json',
    method: 'POST',
    path: codegenPath,
    contentType: 'application/json',
    body: params,
    headers: {},
    query: {},
  });

  candidates.push({
    name: 'form-urlencoded',
    method: 'POST',
    path: codegenPath,
    contentType: 'application/x-www-form-urlencoded',
    body: params,
    headers: {},
    query: {},
  });

  candidates.push({
    name: 'query-get',
    method: 'GET',
    path: codegenPath,
    contentType: '',
    body: null,
    headers: {},
    query: params,
  });

  return candidates;
}

function isLikelySuccess(status, parsedBody) {
  if (status < 200 || status >= 300) {
    return false;
  }
  if (!parsedBody || typeof parsedBody !== 'object') {
    return true;
  }
  if (typeof parsedBody.success === 'boolean') {
    return parsedBody.success;
  }
  const code = parsedBody.code;
  if (typeof code === 'number') {
    return code === 0 || code === 200;
  }
  if (typeof code === 'string') {
    return code === '0' || code === '200' || /^success$/i.test(code);
  }
  return true;
}

async function callCodegenCandidate(auth, candidate) {
  const url = new URL(joinUrl(UPSTREAM_BASE_URL, candidate.path));
  for (const [key, value] of Object.entries(candidate.query || {})) {
    if (value === null || value === undefined) {
      continue;
    }
    url.searchParams.set(key, typeof value === 'string' ? value : JSON.stringify(value));
  }

  const headers = {
    Accept: 'application/json',
    ...pickObject(candidate.headers),
  };
  if (auth.token) {
    headers[TOKEN_HEADER] = auth.token;
  }
  if (auth.cookie) {
    headers.Cookie = auth.cookie;
  }

  let body = undefined;
  if (candidate.method !== 'GET' && candidate.method !== 'HEAD') {
    if (candidate.contentType === 'application/x-www-form-urlencoded') {
      headers['Content-Type'] = 'application/x-www-form-urlencoded';
      body = objectToFormData(candidate.body || {});
    } else if (candidate.contentType && candidate.contentType !== 'application/json') {
      headers['Content-Type'] = candidate.contentType;
      if (typeof candidate.body === 'string') {
        body = candidate.body;
      } else {
        body = JSON.stringify(candidate.body || {});
      }
    } else {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(candidate.body || {});
    }
  }

  const { response, text } = await fetchWithTimeout(
    url.toString(),
    {
      method: candidate.method,
      headers,
      body,
    },
    REQUEST_TIMEOUT_MS
  );

  let parsed = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch (_err) {
    parsed = null;
  }

  return {
    candidate: candidate.name,
    url: url.toString(),
    status: response.status,
    ok: isLikelySuccess(response.status, parsed),
    parsed,
    text,
  };
}

function collectTemplateFallbackFiles(payload) {
  const files = [];
  const templateFiles = pickObject(payload.templateFiles);
  for (const key of Object.keys(templateFiles)) {
    const normalized = normalizePosix(key);
    if (normalized && !normalized.startsWith('/') && !normalized.includes('..')) {
      files.push(normalized);
    }
  }
  return files;
}

function collectArrayStrings(value) {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .map((item) => (item === null || item === undefined ? '' : String(item).trim()))
    .filter(Boolean)
    .map(normalizePosix);
}

function extractFilesFromResult(parsed) {
  if (!parsed || typeof parsed !== 'object') {
    return [];
  }
  const candidates = [
    parsed.files,
    parsed.fileList,
    parsed.genFiles,
    parsed.result && parsed.result.files,
    parsed.result && parsed.result.fileList,
    parsed.result && parsed.result.genFiles,
    parsed.data && parsed.data.files,
    parsed.data && parsed.data.fileList,
    parsed.data && parsed.data.genFiles,
  ];
  const merged = [];
  for (const entry of candidates) {
    merged.push(...collectArrayStrings(entry));
  }
  return dedup(merged);
}

function dedup(items) {
  const set = new Set();
  for (const item of items || []) {
    const normalized = normalizePosix(item);
    if (!normalized) {
      continue;
    }
    set.add(normalized);
  }
  return Array.from(set);
}

function snapshotRoot(rootPath, workspaceDir, snapshot, depth = 0, state = { count: 0 }) {
  if (depth > MAX_SCAN_DEPTH || state.count >= MAX_SCAN_FILES) {
    return;
  }
  let entries = [];
  try {
    entries = fs.readdirSync(rootPath, { withFileTypes: true });
  } catch (_err) {
    return;
  }
  for (const entry of entries) {
    if (state.count >= MAX_SCAN_FILES) {
      return;
    }
    const abs = path.join(rootPath, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === '.git' || entry.name === 'node_modules') {
        continue;
      }
      snapshotRoot(abs, workspaceDir, snapshot, depth + 1, state);
      continue;
    }
    if (!entry.isFile()) {
      continue;
    }
    const stat = fs.statSync(abs);
    const rel = normalizePosix(path.relative(workspaceDir, abs));
    if (!rel || rel.startsWith('../') || rel.startsWith('/') || rel.includes('/../')) {
      continue;
    }
    snapshot.set(rel, stat.mtimeMs);
    state.count += 1;
  }
}

function snapshotWorkspace(workspaceDir) {
  const snapshot = new Map();
  if (!workspaceDir || !fs.existsSync(workspaceDir) || !fs.statSync(workspaceDir).isDirectory()) {
    return snapshot;
  }
  snapshotRoot(workspaceDir, workspaceDir, snapshot);
  return snapshot;
}

function diffSnapshots(before, after) {
  const changed = [];
  for (const [rel, mtime] of after.entries()) {
    const old = before.get(rel);
    if (old === undefined || old !== mtime) {
      changed.push(rel);
    }
  }
  return dedup(changed);
}

async function renderWithJeecg(payload) {
  if (!UPSTREAM_BASE_URL) {
    throw new Error('JEECG_UPSTREAM_BASE_URL is empty, cannot call real Jeecg engine');
  }
  const workspaceDir = safeWorkspaceDir(payload.workspaceDir);
  const beforeSnapshot = snapshotWorkspace(workspaceDir);
  const auth = await resolveJeecgAuth();
  const candidates = buildCandidates(payload);
  const attempts = [];

  let successResult = null;
  for (const candidate of candidates) {
    try {
      const result = await callCodegenCandidate(auth, candidate);
      attempts.push({
        candidate: result.candidate,
        status: result.status,
        ok: result.ok,
        url: result.url,
        bodyPreview: truncate(result.text, 240),
      });
      if (result.ok) {
        successResult = result;
        break;
      }
    } catch (err) {
      attempts.push({
        candidate: candidate.name,
        status: 0,
        ok: false,
        url: joinUrl(UPSTREAM_BASE_URL, candidate.path),
        bodyPreview: truncate(String(err && err.message ? err.message : err), 240),
      });
    }
  }

  if (!successResult) {
    throw new Error(`all codeGenerate attempts failed: ${JSON.stringify(attempts)}`);
  }

  const afterSnapshot = snapshotWorkspace(workspaceDir);
  const changedFiles = diffSnapshots(beforeSnapshot, afterSnapshot);
  const resultFiles = extractFilesFromResult(successResult.parsed);
  const fallbackFiles = collectTemplateFallbackFiles(payload);
  const files = dedup([...changedFiles, ...resultFiles, ...fallbackFiles]);

  const message = pickString(
    successResult.parsed && successResult.parsed.message,
    successResult.parsed && successResult.parsed.result && successResult.parsed.result.message,
    `ok (candidate=${successResult.candidate}, status=${successResult.status}, auth=${auth.authMode})`
  );

  return {
    success: true,
    message,
    files,
    diagnostics: {
      candidate: successResult.candidate,
      status: successResult.status,
      authMode: auth.authMode,
      attempts,
    },
  };
}

async function handler(req, res) {
  if (req.method === 'GET' && req.url === '/health') {
    json(res, 200, {
      status: 'ok',
      detail: 'ready',
      upstreamBaseUrl: UPSTREAM_BASE_URL || null,
      codegenPath: CODEGEN_PATH,
    });
    return;
  }

  if (req.method === 'POST' && req.url === '/api/codegen/jeecg/render') {
    try {
      const payload = await readJsonBody(req);
      const renderResult = await renderWithJeecg(payload || {});
      json(res, 200, {
        success: true,
        message: renderResult.message,
        files: renderResult.files,
        diagnostics: renderResult.diagnostics,
      });
    } catch (err) {
      json(res, 500, {
        success: false,
        message: `render failed: ${err && err.message ? err.message : String(err)}`,
        files: [],
      });
    }
    return;
  }

  json(res, 404, { success: false, message: 'not found' });
}

http.createServer(handler).listen(PORT, '0.0.0.0', () => {
  console.log(`jeecg-codegen-sidecar listening on :${PORT}`);
  if (!UPSTREAM_BASE_URL) {
    console.warn('JEECG_UPSTREAM_BASE_URL is empty, render requests will fail');
  } else {
    console.log(`jeecg upstream: ${UPSTREAM_BASE_URL}`);
  }
});
