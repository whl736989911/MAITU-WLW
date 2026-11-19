"""Linux-only tool and private-environment resolution; no work runs on import.

No host service is selected or stopped. Callers own their fresh data/processes.
Tools: JAVA_HOME > FIRE_TOOLS_DIR/jdk-21 > PATH; PG_BIN > local PG17 >
Ubuntu pg_config/17 bindir. Explicit invalid overrides fail, never fall back.
"""
from __future__ import annotations

import atexit
import ctypes
import ctypes.util
import json
from functools import lru_cache
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import threading

ROOT = Path(__file__).resolve().parent.parent


def require_linux():
    if sys.platform != "linux":
        raise RuntimeError("These fixtures support Ubuntu/Linux only")


def _absolute_override(name):
    value = os.environ.get(name)
    if not value:
        return None
    path = Path(value).expanduser()
    if not path.is_absolute():
        raise RuntimeError(name + " must be an absolute Linux path")
    return path


def tools_directory():
    return (_absolute_override("FIRE_TOOLS_DIR") or ROOT / ".tools/linux").resolve()


def elf_file(path, *, executable=False):
    require_linux()
    path = Path(path).expanduser().resolve(strict=True)
    with path.open("rb") as stream:
        if stream.read(4) != b"\x7fELF":
            raise RuntimeError("Fixture tools must be native Linux ELF files")
    if executable and not os.access(path, os.X_OK):
        raise RuntimeError("Fixture tool is not executable")
    return path


_SHORT_TMPS = {}
_SHORT_TMP_LOCK = threading.Lock()


def _directory_identity(path):
    value = Path(path).lstat()
    if not stat.S_ISDIR(value.st_mode):
        raise RuntimeError("Private short temporary path is no longer a real directory")
    return (value.st_dev, value.st_ino, value.st_uid, stat.S_IMODE(value.st_mode))


def _cleanup_short_tmp(path, identity):
    """Never recurse through a renamed/replaced/symlink/foreign-mode directory."""
    try:
        path = Path(path)
        if path.parent != Path("/tmp") or not path.name.startswith("ff-"):
            return
        if identity[2:] != (os.getuid(), 0o700) or _directory_identity(path) != identity:
            return
        if not shutil.rmtree.avoids_symlink_attacks:
            return
        shutil.rmtree(path)
    except FileNotFoundError:
        pass
    except (OSError, RuntimeError):
        # Retain the run marker for explicit final census/manual diagnosis.
        # Do not weaken the ownership check or fall back to a broad deletion.
        pass


def _short_tmp(tmp):
    """One real, short /tmp directory for each canonical run-local tmp input."""
    key = str(tmp)
    with _SHORT_TMP_LOCK:
        if key in _SHORT_TMPS:
            path, identity = _SHORT_TMPS[key]
            if _directory_identity(path) != identity:
                raise RuntimeError("Cached private short temporary directory identity changed")
            return path
        if not stat.S_ISDIR(Path("/tmp").lstat().st_mode):
            raise RuntimeError("Linux /tmp must be a real directory, not an alias")
        path = Path(tempfile.mkdtemp(prefix="ff-", dir="/tmp"))
        identity = _directory_identity(path)
        if identity[2:] != (os.getuid(), 0o700) or path.resolve() != path:
            raise RuntimeError("New private short temporary directory ownership/mode is invalid")
        try:
            # The persistent nonsecret marker survives forced process termination;
            # Root can census its path and recorded inode even after normal cleanup.
            marker = tmp / "linux-short-tmp.json"
            flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW
            descriptor = os.open(marker, flags, 0o600)
            with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
                json.dump({"kind": "owned-linux-short-tmp", "owner_pid": os.getpid(), "source_tmp": key,
                           "directory": str(path), "device": identity[0], "inode": identity[1],
                           "uid": identity[2], "mode": identity[3]}, stream, sort_keys=True)
                stream.write("\n")
        except BaseException:
            _cleanup_short_tmp(path, identity)
            raise
        _SHORT_TMPS[key] = (path, identity)
        atexit.register(_cleanup_short_tmp, path, identity)
        return path


def fixture_environment(tmp):
    """No inherited APP_*, PG*, proxies, JVM/Node injection or user profiles."""
    require_linux()
    tmp = Path(tmp).resolve()
    tmp.mkdir(parents=True, exist_ok=True, mode=0o700)
    tmp.chmod(0o700)
    names = ("PATH", "JAVA_HOME", "FIRE_TOOLS_DIR", "PG_BIN", "LIBPQ_PATH", "BROWSER_EXECUTABLE")
    env = {name: os.environ[name] for name in names if name in os.environ}
    env["PATH"] = env.get("PATH", os.defpath)
    env.update(LANG="C.UTF-8", LC_ALL="C.UTF-8", TZ="UTC", TMPDIR=str(_short_tmp(tmp)), PYTHONUTF8="1")
    for key, name in (("HOME", "home"), ("XDG_CONFIG_HOME", "config"), ("XDG_CACHE_HOME", "cache"), ("XDG_DATA_HOME", "data"), ("XDG_RUNTIME_DIR", "runtime")):
        directory = tmp / name
        directory.mkdir(exist_ok=True, mode=0o700)
        directory.chmod(0o700)
        env[key] = str(directory)
    return env


@lru_cache(maxsize=16)
def _java21(path):
    java = elf_file(path, executable=True)
    # Private flags cannot be supplied by JAVA_TOOL_OPTIONS or the caller's JVM.
    result = subprocess.run([str(java), "-version"], env={"PATH": os.defpath, "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8"},
                            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
    if result.returncode or not re.search(rb'version\s+"21(?:[.\-+"]|$)', result.stdout + result.stderr):
        raise RuntimeError("Fixture Java must be Java21")
    return str(java)


def java_executable():
    require_linux()
    home = _absolute_override("JAVA_HOME")
    if home:
        return _java21(str(home / "bin/java"))
    local = tools_directory() / "jdk-21/bin/java"
    if local.is_file():
        return _java21(str(local))
    found = shutil.which("java")
    if not found:
        raise RuntimeError("Set Linux JAVA_HOME, prepare .tools/linux/jdk-21, or install Java21 on PATH")
    return _java21(found)


def libpq_library(binary):
    """Prefer an explicit or matching installation library; never a foreign DLL."""
    require_linux()
    explicit = _absolute_override("LIBPQ_PATH")
    if explicit:
        return str(elf_file(explicit))
    base = Path(binary).resolve().parent
    for folder in (base / "lib", base / "lib64", base / "lib/x86_64-linux-gnu", base / "lib/aarch64-linux-gnu"):
        for name in ("libpq.so.5", "libpq.so"):
            if (folder / name).is_file():
                return str(elf_file(folder / name))
    # ctypes may return a soname. Resolve it through the Linux loader, then bind
    # its real ELF path; do not guess a host/global database or its credentials.
    found = ctypes.util.find_library("pq")
    if not found:
        raise RuntimeError("Set LIBPQ_PATH or install Linux libpq matching the PG17 tools")
    ctypes.CDLL(found)
    maps = Path("/proc/self/maps").read_text(encoding="utf-8")
    candidates = set()
    for line in maps.splitlines():
        fields = line.split(None, 5)
        if len(fields) == 6 and fields[5].startswith("/") and re.fullmatch(r"libpq\.so(?:\.[0-9]+)*", Path(fields[5]).name):
            candidates.add(str(elf_file(fields[5])))
    if len(candidates) != 1:
        raise RuntimeError("Linux loader did not resolve exactly one libpq ELF; set LIBPQ_PATH")
    return candidates.pop()


def postgres_tools():
    require_linux()
    configured = _absolute_override("PG_BIN")
    if configured:
        candidates = [configured]
    else:
        candidates = [tools_directory() / "postgresql-17.11/bin"]
        config = shutil.which("pg_config") if not candidates[0].is_dir() else None
        if config:
            # Ubuntu may supply a native binary or a Linux interpreter wrapper.
            config = Path(config).resolve(strict=True)
            with config.open("rb") as source:
                header = source.read(4)
            if not os.access(config, os.X_OK) or not (header == b"\x7fELF" or header.startswith(b"#!")):
                raise RuntimeError("pg_config must be a Linux executable or interpreter wrapper")
            version = subprocess.run([str(config), "--version"], env={"PATH": os.defpath, "LANG": "C.UTF-8"}, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
            if version.returncode == 0 and re.search(rb"\b17\.", version.stdout):
                result = subprocess.run([str(config), "--bindir"], env={"PATH": os.defpath, "LANG": "C.UTF-8"}, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
                if result.returncode == 0:
                    candidates.append(Path(result.stdout.decode("utf-8").strip()))
        candidates.append(Path("/usr/lib/postgresql/17/bin"))
    for candidate in candidates:
        if not candidate.is_dir() and not configured:
            continue
        binary = candidate.resolve(strict=True)
        tools = {name: elf_file(binary / name, executable=True) for name in ("initdb", "postgres", "pg_ctl")}
        version = subprocess.run([str(tools["postgres"]), "--version"], env={"PATH": str(binary) + ":" + os.defpath, "LANG": "C.UTF-8"},
                                 stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
        if version.returncode or not re.search(rb"\b17\.", version.stdout):
            raise RuntimeError("Only PostgreSQL17 tools may initialize an owned fixture")
        return {"bin": binary, "libpq": libpq_library(binary), "major": 17}
    raise RuntimeError("Set PG_BIN, prepare .tools/linux/postgresql-17.11, or provide Ubuntu PG17 tools")
