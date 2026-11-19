// 常驻 ingest 前置：只做 Token 校验 + 原样写磁盘队列 + 立即 202，不解析、不依赖 Java。
// 与 Java 共用 spool 目录；Java 重启时本进程继续接收。
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";

const port = Number(process.env.INGEST_PORT || 8090);
const spool = process.env.MIRROR_SPOOL_DIR || "/opt/fire-platform/data/mirror-spool";
const tokenFile = process.env.MIRROR_TOKEN_FILE || "/opt/fire-platform/config/mirror-token";
const queue = path.join(spool, "queue");
fs.mkdirSync(queue, { recursive: true });

function tokens() {
  try {
    return fs.readFileSync(tokenFile, "utf8").split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  } catch {
    return [];
  }
}

function authorized(header) {
  if (!header) return false;
  const supplied = Buffer.from(header);
  let ok = false;
  for (const token of tokens()) {
    const expected = Buffer.from(token);
    if (expected.length === supplied.length && crypto.timingSafeEqual(expected, supplied)) ok = true;
  }
  return ok;
}

function headersOf(req) {
  const out = {};
  for (const [key, value] of Object.entries(req.headers)) {
    if (key.toLowerCase() === "x-ingest-token" || key.toLowerCase() === "authorization") continue;
    out[key] = Array.isArray(value) ? value.join(", ") : String(value ?? "");
  }
  return out;
}

const mirrorPrefix = /^(?:\/api)?\/ingest\/(ttl|cat1)(?=\/|$)/;

function route(requestUrl, originalUri) {
  const requestPath = (requestUrl || "").split("?")[0];
  const channel = requestPath.match(mirrorPrefix)?.[1];
  const original = (originalUri || requestPath).split("?")[0].replace(mirrorPrefix, "");
  if (channel === "ttl") {
    const ttl = original.match(/^(?:\/api)?\/api\/notify\/v1\/[^/]+\/([A-Za-z0-9_]+)\/telemetry\/?$/);
    return { ingress: "HTTP_NOTIFY", original, platform: ttl?.[1] ?? null, messageType: null };
  }
  if (channel === "cat1") {
    const cat1 = original.match(/^(?:\/api)?\/v1\/formttl\/([A-Za-z0-9_-]+)\/telemetry\/?$/);
    return { ingress: "HTTP_FORM_TTL", original, platform: "formttl", messageType: cat1?.[1] ?? null };
  }
  return { ingress: "HTTP_NOTIFY", original, platform: null, messageType: null };
}

const server = http.createServer((req, res) => {
  if (req.method !== "POST") {
    res.writeHead(405, { "Content-Type": "application/json" });
    res.end('{"code":"METHOD_NOT_ALLOWED"}');
    return;
  }
  if (!authorized(req.headers["x-ingest-token"])) {
    res.writeHead(401, { "Content-Type": "application/json" });
    res.end('{"code":"MIRROR_INGEST_UNAUTHORIZED"}');
    return;
  }
  const chunks = [];
  let size = 0;
  let rejected = false;
  req.on("data", (chunk) => {
    size += chunk.length;
    if (size > 2 * 1024 * 1024) {
      rejected = true;
      res.writeHead(413, { "Content-Type": "application/json" });
      res.end('{"code":"REQUEST_TOO_LARGE"}');
      req.destroy();
      return;
    }
    if (!rejected) chunks.push(chunk);
  });
  req.on("end", () => {
    if (rejected) return;
    if (size === 0) {
      res.writeHead(400, { "Content-Type": "application/json" });
      res.end('{"code":"EMPTY_BODY"}');
      return;
    }
    const body = Buffer.concat(chunks);
    const sha = crypto.createHash("sha256").update(body).digest("hex");
    for (const dir of ["queue", "done", "dead", "ignored"]) {
      if (fs.existsSync(path.join(spool, dir, `${sha}.json`))) {
        res.writeHead(202);
        res.end();
        return;
      }
    }
    const r = route(req.url, req.headers["x-original-uri"]);
    const stored = {
      id: sha,
      ingress: r.ingress,
      original_uri: r.original.slice(0, 500),
      platform: r.platform,
      message_type: r.messageType,
      body: body.toString("utf8"),
      headers: headersOf(req),
      sha256: sha,
      received_at: new Date().toISOString(),
      attempts: 0,
    };
    const temp = path.join(queue, `.${crypto.randomUUID()}.tmp`);
    try {
      fs.writeFileSync(temp, JSON.stringify(stored), "utf8");
      fs.renameSync(temp, path.join(queue, `${sha}.json`));
    } catch (error) {
      try { fs.unlinkSync(temp); } catch {}
      res.writeHead(503, { "Content-Type": "application/json" });
      res.end('{"code":"SPOOL_UNAVAILABLE"}');
      return;
    }
    res.writeHead(202);
    res.end();
  });
});

server.listen(port, "127.0.0.1", () => {
  console.log(`ingest-front listening on 127.0.0.1:${port}, spool=${spool}`);
});
