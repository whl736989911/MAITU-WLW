// 静态前端 + /api 反向代理（含 WebSocket），仅依赖 Node 内置模块。
// 用法: node web-proxy.mjs <webRoot> <apiHost> <apiPort> <listenPort>
import http from "node:http";
import net from "node:net";
import fs from "node:fs";
import path from "node:path";

const [, , webRootArg, apiHost = "127.0.0.1", apiPortArg = "8080", listenArg = "8081"] = process.argv;
const webRoot = path.resolve(webRootArg || "web");
const apiPort = Number(apiPortArg);
const listenPort = Number(listenArg);

const MIME = {
  ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8", ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml", ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
  ".webp": "image/webp", ".ico": "image/x-icon", ".woff": "font/woff", ".woff2": "font/woff2",
  ".map": "application/json; charset=utf-8", ".txt": "text/plain; charset=utf-8",
};

function proxy(req, res, body) {
  const upstream = http.request(
    { host: apiHost, port: apiPort, method: req.method, path: req.url, headers: req.headers },
    (up) => {
      res.writeHead(up.statusCode || 502, up.headers);
      up.pipe(res);
    },
  );
  upstream.on("error", () => {
    if (!res.headersSent) res.writeHead(502, { "Content-Type": "application/json" });
    res.end('{"code":"UPSTREAM_UNAVAILABLE","message":"backend unavailable"}');
  });
  if (body && body.length) upstream.write(body);
  upstream.end();
}

const server = http.createServer((req, res) => {
  if (req.url.startsWith("/api/")) {
    const chunks = [];
    req.on("data", (chunk) => chunks.push(chunk));
    req.on("end", () => proxy(req, res, Buffer.concat(chunks)));
    return;
  }
  const urlPath = decodeURIComponent(new URL(req.url, "http://x").pathname);
  let file = path.join(webRoot, path.normalize(urlPath).replace(/^(\.\.[/\\])+/, ""));
  if (!file.startsWith(webRoot)) file = path.join(webRoot, "index.html");
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(webRoot, "index.html");
  const ext = path.extname(file).toLowerCase();
  res.writeHead(200, {
    "Content-Type": MIME[ext] || "application/octet-stream",
    "Cache-Control": ext === ".html" ? "no-store" : "public, max-age=31536000, immutable",
  });
  fs.createReadStream(file).pipe(res);
});

server.on("upgrade", (req, socket, head) => {
  if (!req.url.startsWith("/api/")) {
    socket.destroy();
    return;
  }
  const upstream = net.connect(apiPort, apiHost, () => {
    const headers = Object.entries(req.headers).map(([k, v]) => `${k}: ${v}`).join("\r\n");
    upstream.write(`${req.method} ${req.url} HTTP/1.1\r\n${headers}\r\n\r\n`);
    if (head && head.length) upstream.write(head);
    socket.pipe(upstream).pipe(socket);
  });
  upstream.on("error", () => socket.destroy());
});

server.listen(listenPort, "0.0.0.0", () => {
  console.log(`web-proxy listening on 0.0.0.0:${listenPort} -> ${apiHost}:${apiPort}, root=${webRoot}`);
});
