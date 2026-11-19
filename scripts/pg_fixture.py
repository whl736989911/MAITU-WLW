"""Owned PostgreSQL17 for finite Ubuntu tests; never installs/manages a service.
Fresh private cluster below .runtime, non-root, loopback TCP/SCRAM only.
"""
from __future__ import annotations
from contextlib import contextmanager
from pathlib import Path
import os
import secrets
import signal
import socket
import subprocess
import time
import uuid
from linux_fixture import fixture_environment, postgres_tools, require_linux

ROOT = Path(__file__).resolve().parent.parent


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


@contextmanager
def postgres_fixture():
    require_linux()
    if os.geteuid() == 0:
        raise RuntimeError("Run the owned PostgreSQL fixture as a non-root Ubuntu user")
    selected = postgres_tools()  # Validate Linux/major17 before any initdb/server.
    binary = selected["bin"]
    run = ROOT / ".runtime" / ("postgres-" + uuid.uuid4().hex)
    run.mkdir(parents=True, mode=0o700)
    run = run.resolve()
    data = run / "data"
    password = secrets.token_urlsafe(24)
    secret = run / "init-password.tmp"
    with secret.open("x", encoding="utf-8") as stream:
        secret.chmod(0o600)
        stream.write(password + "\n")
    env = fixture_environment(run / "tmp")
    env.update(PATH=str(binary) + ":" + env["PATH"], PGPASSWORD=password,
               PGCONNECT_TIMEOUT="3", PGCLIENTENCODING="UTF8")
    port = free_port()
    user, database = "fire_fixture", "postgres"
    process = None
    with (run / "fixture.log").open("ab") as log:
        def command(tool, args, check=True, timeout=40):
            result = subprocess.run([str(binary / tool), *args], cwd=run, env=env,
                                    stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
            if check and result.returncode:
                raise RuntimeError(f"PostgreSQL fixture {tool} failed; see its private fixture.log")
            return result.returncode

        def owned_state():
            marker = data / "postmaster.pid"
            state = marker.read_text(encoding="utf-8").splitlines() if marker.is_file() else []
            return state if len(state) >= 8 and state[0].strip() == str(process.pid) and Path(state[1]).resolve() == data.resolve() and state[3].strip() == str(port) else []

        def owned_signal(value):
            if process.poll() is None:
                try:
                    os.killpg(process.pid, value)
                except ProcessLookupError:
                    pass  # The tracked child exited between poll and signal.

        try:
            # Disposable initialization skips only its initial sync; server fsync stays on.
            command("initdb", ["-D", str(data), "-U", user, "-A", "scram-sha-256", "--pwfile", str(secret), "--encoding=UTF8", "--locale=C", "--no-sync"], timeout=180)
            assert secret.resolve().parent == run and secret.name == "init-password.tmp"
            secret.unlink()
            # Unix sockets are disabled: deep/Unicode project paths must not
            # hit sockaddr_un's small path limit or expose a shared /tmp socket.
            process = subprocess.Popen([str(binary / "postgres"), "-D", str(data), "-h", "127.0.0.1", "-p", str(port), "-k", "",
                                        "-c", "shared_buffers=32MB", "-c", "max_connections=20", "-c", "timezone=UTC"],
                                       cwd=run, env=env, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            deadline = time.monotonic() + 40
            while True:
                state = owned_state()
                if state and state[-1].strip() == "ready":
                    break
                if process.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError("Owned PostgreSQL fixture did not become ready")
                time.sleep(0.2)
            yield {"url": f"jdbc:postgresql://127.0.0.1:{port}/{database}", "username": user, "password": password,
                   "directory": str(run), "bin": str(binary), "libpq": selected["libpq"], "port": port, "database": database}
        finally:
            if secret.exists():
                assert secret.resolve().parent == run and secret.name == "init-password.tmp"
                secret.unlink()
            try:
                if process is not None and process.poll() is None:
                    # Never direct pg_ctl at a changed/foreign PID marker. The
                    # fallback signal group belongs only to our new session.
                    try:
                        if owned_state():
                            try:
                                stopped = command("pg_ctl", ["-D", str(data), "-m", "fast", "-w", "stop"], check=False, timeout=25)
                            except subprocess.TimeoutExpired:
                                stopped = 1
                            if stopped and process.poll() is None:
                                command("pg_ctl", ["-D", str(data), "-m", "immediate", "-w", "stop"], check=False, timeout=15)
                    finally:
                        if process.poll() is None:
                            owned_signal(signal.SIGQUIT)
                        try:
                            process.wait(timeout=10)
                        except subprocess.TimeoutExpired:
                            if process.poll() is None:
                                owned_signal(signal.SIGKILL)
                            process.wait(timeout=10)
            finally:
                # Retain the disposable cluster for diagnosis, never credentials.
                env.pop("PGPASSWORD", None)


if __name__ == "__main__":
    with postgres_fixture() as fixture:
        print("PostgreSQL17 fixture started on loopback with SCRAM and a fresh database")
    print("PostgreSQL17 fixture stopped cleanly")
