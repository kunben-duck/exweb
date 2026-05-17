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
      sendJson(res, { backendUrl: backend.toString().replace(/\/$/, "") });
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
  const target = new URL(req.url || "/", backend);
  const headers = { ...req.headers, host: target.host };
  delete headers["origin"];
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
  const target = new URL(req.url || "/", backend);
  const port = Number(target.port || (target.protocol === "https:" ? 443 : 80));
  const connect = target.protocol === "https:" ? tls.connect : net.connect;
  const backendSocket = connect({ host: target.hostname, port }, () => {
    const headers = { ...req.headers, host: target.host };
    delete headers["origin"];
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
