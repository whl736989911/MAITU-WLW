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

function diagnosticHeaders(contentType) {
  const mediaType = contentType?.split(";", 1)[0].trim().toLowerCase();
  switch (mediaType) {
    case "application/json":
    case "application/x-www-form-urlencoded":
    case "application/octet-stream":
    case "text/plain":
      return { "Content-Type": mediaType };
    default:
      return {};
  }
}

// Keep these route classifications aligned with the Java registry and CAT1 normalizer.
const ttlPlatforms = new Set(["lorawan_turbo", "aep_turbo", "tyy_turbo", "oceanconnect_turbo", "turmass", "maitu_mqtt", "formttl"]);

function cat1Category(label) {
  const value = label.toLowerCase();
  if (value.includes("fault")) return "fault";
  if (value.includes("recover") && (value.includes("alarm") || value.includes("fire"))) return "alarmRecovery";
  if (value.includes("alarm")) return "alarm";
  if (value.includes("heartbeat")) return "heartbeat";
  if (value.includes("param")) return "param";
  if (value.includes("offline")) return "offline";
  return "other";
}

const mirrorPrefix = /^(?:\/api)?\/ingest\/(ttl|cat1)(?=\/|$)/;

function route(requestUrl, originalUri) {
  const requestPath = (requestUrl || "").split("?")[0];
  const channel = requestPath.match(mirrorPrefix)?.[1];
  const original = (originalUri || requestPath).split("?")[0].replace(mirrorPrefix, "");
  if (channel === "ttl") {
    const ttl = original.match(/^(?:\/api)?\/api\/notify\/v1\/[^/]+\/([A-Za-z0-9_]+)\/telemetry\/?$/);
    const platform = ttl?.[1] ?? null;
    const known = ttlPlatforms.has(platform);
    return {
      ingress: "HTTP_NOTIFY",
      canonical: known ? `/api/notify/v1/{redacted}/${platform}/telemetry` : "/api/ingest/ttl",
      platform: platform === null || known ? platform : "unknown",
      messageType: null,
    };
  }
  if (channel === "cat1") {
    const cat1 = original.match(/^(?:\/api)?\/v1\/formttl\/([A-Za-z0-9_-]+)\/telemetry\/?$/);
    const messageType = cat1 ? cat1Category(cat1[1]) : null;
    return {
      ingress: "HTTP_FORM_TTL",
      canonical: messageType !== null ? `/v1/formttl/${messageType}/telemetry` : "/api/ingest/cat1",
      platform: "formttl",
      messageType,
    };
  }
  return { ingress: "HTTP_NOTIFY", canonical: "/api/ingest/ttl", platform: null, messageType: null };
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
      original_uri: r.canonical,
      platform: r.platform,
      message_type: r.messageType,
      body: body.toString("utf8"),
      headers: diagnosticHeaders(req.headers["content-type"]),
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
