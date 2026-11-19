import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { once } from "node:events";
import { mkdtemp, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

async function unusedPort() {
  const listener = createServer();
  listener.listen(0, "127.0.0.1");
  await once(listener, "listening");
  const { port } = listener.address();
  listener.close();
  await once(listener, "close");
  return port;
}

test("standalone mirror preserves transport and parse inputs without raw route or headers", { timeout: 10000 }, async (t) => {
  const spool = await mkdtemp(join(tmpdir(), "ingest-front-test-"));
  t.after(() => rm(spool, { recursive: true, force: true }));
  const tokenFile = join(spool, "synthetic-token-file");
  await writeFile(tokenFile, "synthetic-ingest-token\n");
  const port = await unusedPort();
  const child = spawn(process.execPath, [fileURLToPath(new URL("./ingest-front.mjs", import.meta.url))], {
    env: { ...process.env, INGEST_PORT: String(port), MIRROR_SPOOL_DIR: spool, MIRROR_TOKEN_FILE: tokenFile },
    stdio: ["ignore", "pipe", "inherit"],
  });
  const stopped = once(child, "exit");
  t.after(async () => { child.kill(); await stopped; });
  await Promise.race([once(child.stdout, "data"), stopped.then(() => assert.fail("mirror front exited before startup"))]);

  const ttlBody = '{"deveui":"0000000000000001"}\n';
  const headers = {
    "X-Ingest-Token": "synthetic-ingest-token",
    "X-Original-Uri": "/api/api/notify/v1/synthetic-path-token/lorawan_turbo/telemetry?q=synthetic-query",
    "Content-Type": "application/json; profile=synthetic-parameter",
    Authorization: "Bearer synthetic-auth",
    Cookie: "sid=synthetic-cookie",
    "X-Forwarded-For": "192.0.2.42",
    "X-Debug-Extra": "synthetic-header",
  };
  const queue = join(spool, "queue");
  const post = (path, body, headers) => fetch(`http://127.0.0.1:${port}${path}`, { method: "POST", body, headers });
  for (let i = 0; i < 2; i++) assert.equal((await post("/api/ingest/ttl", ttlBody, headers)).status, 202);
  const sha = createHash("sha256").update(ttlBody).digest("hex");
  assert.deepEqual(await readdir(queue), [`${sha}.json`]);
  const ttl = JSON.parse(await readFile(join(queue, `${sha}.json`), "utf8"));
  assert.deepEqual(
    [ttl.ingress, ttl.original_uri, ttl.platform, ttl.message_type, ttl.body, ttl.sha256, ttl.headers],
    ["HTTP_NOTIFY", "/api/notify/v1/{redacted}/lorawan_turbo/telemetry", "lorawan_turbo", null, ttlBody, sha, { "Content-Type": "application/json" }],
  );

  const catBody = 'msg=%7B%22deviceId%22%3A%220000000000000002%22%7D';
  assert.equal((await post("/ingest/cat1/v1/formttl/synthetic-fault-alarm-label/telemetry?synthetic-query", catBody, {
    "X-Ingest-Token": "synthetic-ingest-token", "Content-Type": "application/x-www-form-urlencoded",
  })).status, 202);
  const catSha = createHash("sha256").update(catBody).digest("hex");
  const cat = JSON.parse(await readFile(join(queue, `${catSha}.json`), "utf8"));
  assert.deepEqual([cat.ingress, cat.original_uri, cat.platform, cat.message_type, cat.body],
    ["HTTP_FORM_TTL", "/v1/formttl/fault/telemetry", "formttl", "fault", catBody]);

  const unknownBody = '{"deveui":"0000000000000003"}';
  assert.equal((await post("/ingest/ttl", unknownBody, { ...headers,
    "X-Original-Uri": "/api/notify/v1/synthetic-path/synthetic_secret_label/telemetry",
    "Content-Type": "application/x-synthetic-secret",
  })).status, 202);
  const unknownSha = createHash("sha256").update(unknownBody).digest("hex");
  const unknown = JSON.parse(await readFile(join(queue, `${unknownSha}.json`), "utf8"));
  assert.deepEqual([unknown.original_uri, unknown.platform, unknown.body, unknown.headers],
    ["/api/ingest/ttl", "unknown", unknownBody, {}]);
});
