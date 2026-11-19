"""Shared finite HTTP fixture. Fresh owned JVM/database; credentials stay in memory."""
from __future__ import annotations
from contextlib import contextmanager, ExitStack
from decimal import Decimal
from pathlib import Path
import json
import os
import secrets
import shutil
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pg_fixture import free_port, postgres_fixture
from linux_fixture import fixture_environment, java_executable

ROOT = Path(__file__).resolve().parent.parent


class Api:
    def __init__(self, port):
        self.base = f"http://127.0.0.1:{port}/api"
        self.checks = []
        self.secrets = []

    def call(self, method, path, body=None, token=None, tenant=None, key=None, expected=200, json_text=None):
        headers = {"Accept": "application/json"}
        if body is not None or json_text is not None:
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        if tenant:
            headers["X-Tenant-Id"] = tenant
        if method != "GET":
            headers["Idempotency-Key"] = key or uuid.uuid4().hex
        payload = json_text.encode() if json_text is not None else None if body is None else json.dumps(body, allow_nan=False).encode()
        request = urllib.request.Request(self.base + path, data=payload, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        result = json.loads(raw, parse_float=Decimal) if raw else None
        if status != expected:
            code = result.get("code", "unknown") if isinstance(result, dict) else "unknown"
            raise AssertionError(f"{method} {path}: expected {expected}, got {status}; code={code}")
        # Inspect the wire text without rounding exact response numbers through float.
        serialized = raw.decode("utf-8")
        assert not any(secret in serialized for secret in self.secrets), "Response exposed a fixture password"
        for forbidden in ('"password_hash"', '"secret_verifier"', '"initial_password"', '"new_password"'):
            assert forbidden not in serialized, "Response exposed a sensitive field"
        return result


    def password(self):
        value = secrets.token_urlsafe(24)
        self.secrets.append(value)
        return value

    def login(self, username, password, audience):
        return self.call("POST", "/auth/login", {"username": username, "password": password, "audience": audience})

    def ready(self, username, password, audience):
        first = self.login(username, password, audience)
        assert first["user"]["must_change_password"]
        changed = self.password()
        self.call("POST", "/auth/change-password", {"current_password": password, "new_password": changed}, token=first["access_token"], expected=204)
        self.call("GET", "/auth/me", token=first["access_token"], expected=401)
        result = self.login(username, changed, audience)
        assert not result["user"]["must_change_password"]
        return result, changed
def assign_fixture_all_tenant(api, root_token, user_id, tenant_ids, *, platform=True):
    """Explicitly preserve a legacy synthetic actor's whole-unit device fixture."""
    route = "/management/platform-users/" if platform else "/management/users/"
    for tenant_id in tenant_ids:
        api.call("PUT", route + user_id + "/scope",
                 {"mode": "ALL_TENANT", "device_ids": [], "place_ids": [], "expected_revision": 0,
                  "reason": "Legacy fixture preserves its original whole-unit device visibility"},
                 token=root_token, tenant=tenant_id)


@contextmanager
def app_fixture(storage="h2-dev", username="fixture-root", label="http", capture=False):
    jar = ROOT / "backend/target/fire-platform-backend-0.1.0-SNAPSHOT.jar"
    java = java_executable()
    assert java and jar.is_file(), "Build backend JAR with Java21 first"
    run = ROOT / ".runtime" / (label + "-" + uuid.uuid4().hex)
    run.mkdir(parents=True)
    (run / "tmp").mkdir()
    with ExitStack() as stack:
        database = stack.enter_context(postgres_fixture()) if storage == "postgresql" else None
        port = free_port()
        api = Api(port)
        initial = api.password()
        env = fixture_environment(run / "tmp")
        env.update(APP_PORT=str(port), APP_BIND_ADDRESS="127.0.0.1", APP_PROFILE="prod" if database else "test", APP_STORAGE=storage,
                   APP_ROLES="all", APP_GENERIC_INGEST_ENABLED="true" if capture else "false", APP_BOOTSTRAP_USERNAME=username, APP_BOOTSTRAP_PASSWORD=initial,
                   APP_DB_URL=database["url"] if database else "jdbc:h2:mem:fixture;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
                   APP_DB_USERNAME=database["username"] if database else "sa", APP_DB_PASSWORD=database["password"] if database else "")
        with (run / "server.log").open("wb") as log:
            process = subprocess.Popen([java, "-Djava.io.tmpdir=" + str(run / "tmp"), "-jar", str(jar)], cwd=run, env=env, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 90
                while True:
                    if process.poll() is not None:
                        raise AssertionError("Owned application exited before readiness; inspect local server.log")
                    try:
                        with urllib.request.urlopen(api.base + "/health", timeout=2) as response:
                            health = json.load(response)
                        assert health["storage_mode"] == storage
                        if capture or "generic_ingest_enabled" in health:
                            assert health.get("generic_ingest_enabled") is capture
                        break
                    except (urllib.error.URLError, TimeoutError, ConnectionError, AssertionError):
                        if time.monotonic() >= deadline:
                            raise AssertionError("Application readiness timeout")
                        time.sleep(0.25)
                yield api, initial, run
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)
