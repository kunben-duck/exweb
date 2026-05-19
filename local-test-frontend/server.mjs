import http from "node:http";
import https from "node:https";
import net from "node:net";
import tls from "node:tls";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.join(__dirname, "public");
const port = Number(process.env.PORT || 5173);
const backend = new URL(process.env.BACKEND_URL || "http://localhost:8080");
const backendBasePath = normalizeBasePath(backend.pathname);
const proxyProfileCookieName = "finex_proxy_profile";
const proxyProfileHeaderName = "x-finex-proxy-profile";
const authProfiles = new Map();

const mimeTypes = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml"
};

const server = http.createServer(async (req, res) => {
  try {
    if (req.url === "/__config") {
      sendJson(res, {
        backendUrl: backend.toString().replace(/\/$/, ""),
        proxyProfileCookieName,
        proxyProfileHeaderName
      });
      return;
    }
    if (req.url === "/__auth-config" && req.method === "POST") {
      await saveAuthConfig(req, res);
      return;
    }
    if (req.url?.startsWith("/__auth-config/") && req.method === "DELETE") {
      deleteAuthConfig(req, res);
      return;
    }
    if (req.url?.startsWith("/api/")) {
      proxyHttp(req, res);
      return;
    }
    await serveStatic(req, res);
  } catch (error) {
    res.writeHead(500, { "content-type": "text/plain; charset=utf-8" });
    res.end(error instanceof Error ? error.message : String(error));
  }
});

server.on("upgrade", (req, socket, head) => {
  if (!req.url?.startsWith("/api/")) {
    socket.destroy();
    return;
  }
  proxyWebSocket(req, socket, head);
});

server.listen(port, () => {
  console.log(`FinanceEX local test frontend: http://localhost:${port}`);
  console.log(`Proxy backend: ${backend.toString().replace(/\/$/, "")}`);
});

async function serveStatic(req, res) {
  const requestPath = decodeURIComponent(new URL(req.url || "/", "http://localhost").pathname);
  const safePath = requestPath === "/" ? "/index.html" : requestPath;
  const filePath = path.normalize(path.join(publicDir, safePath));
  const relativePath = path.relative(publicDir, filePath);
  if (relativePath.startsWith("..") || path.isAbsolute(relativePath)) {
    res.writeHead(403);
    res.end("Forbidden");
    return;
  }
  try {
    const data = await fs.readFile(filePath);
    const contentType = mimeTypes[path.extname(filePath)] || "application/octet-stream";
    res.writeHead(200, { "content-type": contentType, "cache-control": "no-store" });
    res.end(data);
  } catch {
    const fallback = await fs.readFile(path.join(publicDir, "index.html"));
    res.writeHead(200, { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" });
    res.end(fallback);
  }
}

function proxyHttp(req, res) {
  const target = backendTarget(req.url);
  const headers = backendHeaders(req, target);
  const transport = target.protocol === "https:" ? https : http;

  const proxy = transport.request({
    protocol: target.protocol,
    hostname: target.hostname,
    port: target.port || (target.protocol === "https:" ? 443 : 80),
    method: req.method,
    path: target.pathname + target.search,
    headers
  }, backendRes => {
    res.writeHead(backendRes.statusCode || 502, backendRes.headers);
    backendRes.pipe(res);
  });

  proxy.on("error", error => {
    res.writeHead(502, { "content-type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({ error: "BACKEND_PROXY_FAILED", message: error.message }));
  });

  req.pipe(proxy);
}

function proxyWebSocket(req, clientSocket, head) {
  const target = backendTarget(req.url);
  const port = Number(target.port || (target.protocol === "https:" ? 443 : 80));
  const connect = target.protocol === "https:" ? tls.connect : net.connect;
  const backendSocket = connect({ host: target.hostname, port }, () => {
    const headers = backendHeaders(req, target);
    const headerLines = Object.entries(headers)
      .filter(([, value]) => value !== undefined)
      .map(([name, value]) => `${name}: ${Array.isArray(value) ? value.join(", ") : value}`);
    backendSocket.write(`${req.method} ${target.pathname + target.search} HTTP/${req.httpVersion}\r\n`);
    backendSocket.write(headerLines.join("\r\n"));
    backendSocket.write("\r\n\r\n");
    if (head.length > 0) {
      backendSocket.write(head);
    }
    backendSocket.pipe(clientSocket);
    clientSocket.pipe(backendSocket);
  });

  backendSocket.on("error", () => clientSocket.destroy());
  clientSocket.on("error", () => backendSocket.destroy());
}

function sendJson(res, body) {
  res.writeHead(200, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  res.end(JSON.stringify(body));
}

async function saveAuthConfig(req, res) {
  try {
    const payload = JSON.parse(await readBody(req));
    const profileId = requireProfileId(payload.profileId);
    const headers = normalizeAuthHeaders(payload.headers);
    authProfiles.set(profileId, headers);
    sendJson(res, { profileId, headerCount: Object.keys(headers).length });
  } catch (error) {
    res.writeHead(400, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
    res.end(JSON.stringify({
      error: "BAD_AUTH_CONFIG",
      message: error instanceof Error ? error.message : String(error)
    }));
  }
}

function deleteAuthConfig(req, res) {
  const pathname = new URL(req.url || "/", "http://localhost").pathname;
  const profileId = decodeURIComponent(pathname.slice("/__auth-config/".length));
  if (profileId) {
    authProfiles.delete(profileId);
  }
  sendJson(res, { profileId, deleted: true });
}

function backendTarget(requestUrl) {
  const incoming = new URL(requestUrl || "/", "http://localhost");
  const target = new URL(backend.toString());
  target.pathname = joinPaths(backendBasePath, incoming.pathname);
  target.search = incoming.search;
  target.searchParams.delete("proxyProfileId");
  target.searchParams.delete("__finexProfile");
  return target;
}

function normalizeBasePath(pathname) {
  const value = pathname || "";
  if (!value || value === "/") {
    return "";
  }
  return value.endsWith("/") ? value.slice(0, -1) : value;
}

function joinPaths(basePath, requestPath) {
  const normalizedRequestPath = requestPath.startsWith("/") ? requestPath : `/${requestPath}`;
  return `${basePath}${normalizedRequestPath}` || "/";
}

function backendHeaders(req, target) {
  const headers = { ...req.headers, host: target.host };
  delete headers[proxyProfileHeaderName];
  delete headers["cookie"];
  delete headers["origin"];

  const profileId = proxyProfileId(req);
  const configuredHeaders = profileId ? authProfiles.get(profileId) : null;
  if (!configuredHeaders) {
    return headers;
  }
  for (const [name, value] of Object.entries(configuredHeaders)) {
    const lowerName = name.toLowerCase();
    if (!isProxySafeHeader(lowerName)) {
      continue;
    }
    headers[lowerName] = value;
  }
  return headers;
}

function proxyProfileId(req) {
  const headerValue = req.headers[proxyProfileHeaderName];
  if (Array.isArray(headerValue) && headerValue[0]) {
    return headerValue[0];
  }
  if (typeof headerValue === "string" && headerValue) {
    return headerValue;
  }
  const urlProfile = new URL(req.url || "/", "http://localhost").searchParams.get("proxyProfileId");
  if (urlProfile) {
    return urlProfile;
  }
  return cookieValue(req.headers.cookie || "", proxyProfileCookieName);
}

function cookieValue(cookieHeader, name) {
  for (const part of String(cookieHeader || "").split(";")) {
    const [rawName, ...rest] = part.trim().split("=");
    if (rawName === name) {
      return decodeURIComponent(rest.join("="));
    }
  }
  return "";
}

function normalizeAuthHeaders(input) {
  const result = {};
  const entries = Array.isArray(input) ? input : [];
  for (const item of entries) {
    if (!item || item.enabled === false) {
      continue;
    }
    const name = String(item.name || "").trim();
    const value = String(item.value ?? "");
    if (!name) {
      continue;
    }
    if (!/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/.test(name)) {
      throw new Error(`非法 header 名称: ${name}`);
    }
    if (/[\r\n]/.test(value)) {
      throw new Error(`header 值不能包含换行: ${name}`);
    }
    if (!isProxySafeHeader(name.toLowerCase())) {
      continue;
    }
    result[name.toLowerCase()] = value;
  }
  return result;
}

function isProxySafeHeader(lowerName) {
  return ![
    "host",
    "connection",
    "upgrade",
    "content-length",
    "transfer-encoding",
    "sec-websocket-key",
    "sec-websocket-version",
    "sec-websocket-protocol",
    "sec-websocket-extensions"
  ].includes(lowerName);
}

function requireProfileId(value) {
  const profileId = String(value || "").trim();
  if (!/^[A-Za-z0-9._:-]{8,128}$/.test(profileId)) {
    throw new Error("profileId 格式非法");
  }
  return profileId;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = "";
    req.setEncoding("utf8");
    req.on("data", chunk => {
      body += chunk;
      if (body.length > 256 * 1024) {
        reject(new Error("请求体过大"));
        req.destroy();
      }
    });
    req.on("end", () => resolve(body || "{}"));
    req.on("error", reject);
  });
}
