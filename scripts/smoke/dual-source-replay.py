#!/usr/bin/env python3
"""Replay one synthetic HTTP and one Turmass MQTT alarm through an owned local app."""
from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts"
sys.path.insert(0, str(SCRIPTS))

from http_fixture import Api  # noqa: E402
from linux_fixture import fixture_environment, java_executable, require_linux  # noqa: E402
from pg_fixture import free_port  # noqa: E402

FIXTURE = Path(__file__).with_name("dual-source-fixture.ndjson")
DEVICE_ID = re.compile(r"[A-Fa-f0-9]{8}\Z")
MAX_NDJSON_BYTES = 1024 * 1024
MAX_FRAME_BYTES = 32 * 1024
GATEWAY_TOPIC = "gateway/SMOKE-GATEWAY/uplink"


class SmokeError(RuntimeError):
    pass


def crc16_modbus(data: bytes) -> int:
    crc = 0xFFFF
    for value in data:
        crc ^= value
        for _ in range(8):
            crc = (crc >> 1) ^ (0xA001 if crc & 1 else 0)
    return crc


def load_records(path: Path) -> list[dict[str, str]]:
    resolved = path.expanduser().resolve(strict=True)
    if any(part.lower() == ".local-ingest" for part in resolved.parts):
        raise SmokeError("input under .local-ingest is not allowed; provide a sanitized NDJSON file")
    if not resolved.is_file() or resolved.stat().st_size > MAX_NDJSON_BYTES:
        raise SmokeError("NDJSON input must be a regular file no larger than 1 MiB")

    records: list[dict[str, str]] = []
    for line_number, line in enumerate(resolved.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as error:
            raise SmokeError(f"invalid NDJSON record at line {line_number}") from error
        if not isinstance(row, dict) or set(row) != {"source", "device_eui", "frame"}:
            raise SmokeError(f"record {line_number} must contain only source, device_eui, and frame")
        source, device_eui, frame_hex = row["source"], row["device_eui"], row["frame"]
        if source not in ("http", "mqtt") or not isinstance(device_eui, str) or not DEVICE_ID.fullmatch(device_eui):
            raise SmokeError(f"record {line_number} has an invalid source or synthetic Turmass device ID")
        if not isinstance(frame_hex, str) or len(frame_hex) > MAX_FRAME_BYTES * 2 or not re.fullmatch(r"[0-9A-Fa-f]+", frame_hex):
            raise SmokeError(f"record {line_number} has an invalid or oversized frame")
        frame = bytes.fromhex(frame_hex)
        if len(frame) < 25 or frame[0] != 0x7E or len(frame) != 12 + int.from_bytes(frame[8:10], "big"):
            raise SmokeError(f"record {line_number} is not a complete Turmass Java 7E frame")
        if int.from_bytes(frame[10:12], "little") != crc16_modbus(frame[12:]):
            raise SmokeError(f"record {line_number} has an invalid Turmass CRC")
        if frame[12:14] != b"\x65\x08" or frame[18:22].hex().upper() != device_eui.upper():
            raise SmokeError(f"record {line_number} frame identity does not match device_eui")
        has_fire_alarm = False
        index = 12
        while index < len(frame):
            if index + 2 > len(frame):
                raise SmokeError(f"record {line_number} has a truncated TLV header")
            tag, length = frame[index], frame[index + 1]
            index += 2
            if index + length > len(frame):
                raise SmokeError(f"record {line_number} has a truncated TLV value")
            has_fire_alarm |= tag == 0x01 and length == 1
            index += length
        if not has_fire_alarm:
            raise SmokeError(f"record {line_number} does not carry the synthetic fire-alarm TLV")
        records.append({"source": source, "device_eui": device_eui.upper(), "frame": frame_hex.upper()})

    if len(records) != 2 or {row["source"] for row in records} != {"http", "mqtt"}:
        raise SmokeError("NDJSON must contain exactly one HTTP and one MQTT record")
    if len({row["device_eui"] for row in records}) != 2:
        raise SmokeError("HTTP and MQTT records must use separate synthetic devices")
    return records


def local_port(requested: int | None) -> int:
    if requested is not None:
        if not 1024 <= requested <= 65535:
            raise SmokeError("ports must be in the range 1024..65535")
        return requested
    return free_port()


def wait_for_port(process: subprocess.Popen[bytes], port: int, timeout: float, label: str) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise SmokeError(f"local {label} process exited before readiness")
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                return
        except OSError:
            time.sleep(0.1)
    raise SmokeError(f"local {label} readiness timed out")


def wait_for_app(process: subprocess.Popen[bytes], api: Api, timeout: float) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise SmokeError("owned local application exited before readiness")
        try:
            health = api.call("GET", "/health", expected=200)
            if health.get("storage_mode") == "h2-dev":
                return
        except (AssertionError, urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(0.25)
    raise SmokeError("owned local application readiness timed out")


def stop(process: subprocess.Popen[bytes] | None) -> None:
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def post_http_ingress(port: int, token: str, body: dict[str, str]) -> dict:
    url = f"http://127.0.0.1:{port}/api/notify/v1/{token}/turmass/telemetry"
    request = urllib.request.Request(url, data=json.dumps(body, separators=(",", ":")).encode("utf-8"),
                                    headers={"Content-Type": "application/json", "Accept": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            result = json.load(response)
    except urllib.error.HTTPError as error:
        raise SmokeError(f"authenticated HTTP ingress returned status {error.code}") from None
    except (urllib.error.URLError, TimeoutError, ConnectionError):
        raise SmokeError("authenticated HTTP ingress did not respond") from None
    if (not isinstance(result, dict) or result.get("state") != "NORMALIZED" or result.get("events") != 1
            or result.get("failure_code") is not None or result.get("duplicate") is not False):
        raise SmokeError("HTTP ingress did not normalize exactly one non-duplicate event")
    return result


def publish_mqtt(binary: str, port: int, body: dict[str, str], env: dict[str, str]) -> None:
    try:
        result = subprocess.run([binary, "-h", "127.0.0.1", "-p", str(port), "-q", "1", "-t", GATEWAY_TOPIC,
                                 "-m", json.dumps(body, separators=(",", ":"))],
                                env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                timeout=15, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise SmokeError("local MQTT publish did not complete") from None
    if result.returncode != 0:
        raise SmokeError("local MQTT publisher returned a non-zero status")


def wait_for_mqtt(api: Api, token: str, timeout: float) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        status = api.call("GET", "/management/legacy-ingest/mqtt", token=token)
        if status.get("status") == "CONNECTED" and status.get("readOnly") is True:
            return
        time.sleep(0.2)
    raise SmokeError("application did not connect to the loopback MQTT broker in read-only mode")


def find_message(api: Api, token: str, tenant: str, device_eui: str, ingress: str, timeout: float) -> dict:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        page = api.call("GET", f"/management/legacy-ingest/messages?page=1&size=20&platform=turmass&keyword={device_eui}",
                        token=token, tenant=tenant)
        match = next((row for row in page.get("items", []) if row.get("ingress") == ingress), None)
        if match is not None:
            if match.get("state") != "NORMALIZED" or match.get("failure_code") is not None or match.get("event_count") != 1:
                raise SmokeError(f"{ingress} did not produce one normalized event")
            return match
        time.sleep(0.2)
    raise SmokeError(f"{ingress} message was not observable through the authorized query")


def verify_observations(api: Api, token: str, tenant: str, records: list[dict[str, str]],
                        ingress_by_source: dict[str, str], device_ids: dict[str, str], timeout: float) -> tuple[int, int]:
    event_total = 0
    alarm_total = 0
    for record in records:
        device_eui = record["device_eui"]
        message = find_message(api, token, tenant, device_eui, ingress_by_source[record["source"]], timeout)
        detail = api.call("GET", f"/management/legacy-ingest/messages/{message['id']}", token=token, tenant=tenant)
        events = detail.get("events", [])
        if len(events) != 1 or events[0].get("kind") != "ALARM" or events[0].get("method") != "thing.event.alarms":
            raise SmokeError(f"{ingress_by_source[record['source']]} message detail did not expose the normalized alarm event")

        device_id = message.get("device_id")
        if device_id != device_ids[device_eui]:
            raise SmokeError(f"{ingress_by_source[record['source']]} message did not bind to its synthetic device")
        device_events = api.call("GET", f"/management/devices/{device_id}/events?page=1&size=10", token=token, tenant=tenant)
        observed_events = device_events.get("items", [])
        if device_events.get("total") != 1 or len(observed_events) != 1 or observed_events[0].get("kind") != "ALARM":
            raise SmokeError(f"{ingress_by_source[record['source']]} device event was not observable")
        event_total += len(observed_events)

        alarms = api.call("GET", f"/management/devices/{device_id}/alarms?page=1&size=10", token=token, tenant=tenant)
        rows = alarms.get("items", [])
        if len(rows) != 1 or rows[0].get("response_state") != "UNHANDLED":
            raise SmokeError(f"{ingress_by_source[record['source']]} alarm was not observable as unhandled")
        alarm_total += len(rows)
    return event_total, alarm_total


def run(args: argparse.Namespace) -> None:
    require_linux()
    records = load_records(args.ndjson or FIXTURE)
    mosquitto = shutil.which("mosquitto")
    publisher = shutil.which("mosquitto_pub")
    if not mosquitto or not publisher:
        raise SmokeError("Linux runtime requires mosquitto and mosquitto_pub on PATH")
    jar = ROOT / "backend/target/fire-platform-backend-0.1.0-SNAPSHOT.jar"
    java = java_executable()
    if not jar.is_file():
        raise SmokeError("backend JAR is missing; build it with the repository's approved Linux workflow first")

    app_port = local_port(args.app_port)
    mqtt_port = local_port(args.mqtt_port)
    if mqtt_port == app_port:
        if args.mqtt_port is None:
            while mqtt_port == app_port:
                mqtt_port = free_port()
        elif args.app_port is None:
            while app_port == mqtt_port:
                app_port = free_port()
        else:
            raise SmokeError("application and MQTT ports must be different")

    with tempfile.TemporaryDirectory(prefix="omp-dual-source-") as temporary:
        run_dir = Path(temporary)
        temp = run_dir / "tmp"
        temp.mkdir(mode=0o700)
        env = fixture_environment(temp)
        username = "smoke-" + uuid.uuid4().hex[:12]
        initial_password = secrets.token_urlsafe(24)
        ingest_token = secrets.token_urlsafe(32)
        env.update({
            "APP_PORT": str(app_port),
            "APP_BIND_ADDRESS": "127.0.0.1",
            "APP_PROFILE": "test",
            "APP_STORAGE": "h2-dev",
            "APP_ROLES": "all",
            "APP_BOOTSTRAP_USERNAME": username,
            "APP_BOOTSTRAP_PASSWORD": initial_password,
            "APP_DB_URL": f"jdbc:h2:file:{(run_dir / 'fire').as_posix()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "APP_DB_USERNAME": "sa",
            "APP_DB_PASSWORD": "",
            "APP_LEGACY_INGEST_TOKEN": ingest_token,
            "FIRE_LEGACY_MQTT_ENABLED": "true",
            "FIRE_LEGACY_MQTT_URL": f"tcp://127.0.0.1:{mqtt_port}",
            "FIRE_LEGACY_MQTT_TOPICS": "gateway/+/uplink",
            "FIRE_LEGACY_MQTT_CLIENT_ID": "omp-smoke-" + uuid.uuid4().hex[:16],
            "FIRE_LEGACY_MQTT_READ_ONLY": "true",
        })

        broker_config = run_dir / "mosquitto.conf"
        broker_config.write_text(f"listener {mqtt_port} 127.0.0.1\nallow_anonymous true\npersistence false\n", encoding="utf-8")
        app_log = (run_dir / "application.log").open("wb")
        broker_log = (run_dir / "mqtt.log").open("wb")
        broker = app = None
        try:
            broker = subprocess.Popen([mosquitto, "-c", str(broker_config)], env=env,
                                      stdin=subprocess.DEVNULL, stdout=broker_log, stderr=subprocess.STDOUT)
            wait_for_port(broker, mqtt_port, args.timeout, "MQTT broker")
            app = subprocess.Popen([java, "-Djava.io.tmpdir=" + str(temp), "-jar", str(jar)], cwd=ROOT, env=env,
                                   stdin=subprocess.DEVNULL, stdout=app_log, stderr=subprocess.STDOUT)
            api = Api(app_port)
            wait_for_app(app, api, args.timeout)
            root, _ = api.ready(username, initial_password, "management")
            token = root["access_token"]

            unit = api.call("POST", "/management/units", {"name": "双源回放合成单位"}, token=token)
            tenant = unit["id"]
            place = api.call("POST", "/management/places", {"name": "双源回放合成位置"}, token=token, tenant=tenant)
            product = api.call("POST", "/management/products", {
                "name": "双源回放合成产品", "manufacturer": "OMP_SYNTHETIC", "model": "OMP-SMOKE-TURMASS",
                "description": "本机隔离回放；非真实厂商或设备配置",
            }, token=token)
            version = api.call("POST", f"/management/products/{product['id']}/versions", {}, token=token)
            schema = {
                "format": "JSON_POINTER_V1", "event_pointer": "/event",
                "event_mappings": [{"raw_value": "FIRE", "event_type": "FIRE"}, {"raw_value": "RECOVERY", "event_type": "RECOVERY"}],
                "battery": {"pointer": "/battery", "scale": 1, "offset": 0, "min": 0, "max": 100},
            }
            version = api.call("PUT", f"/management/product-versions/{version['id']}", {
                "expected_revision": version["revision"], "schema": schema,
                "capabilities": {"fire": "SUPPORTED", "recovery": "SUPPORTED", "battery": "UNSUPPORTED"},
                "battery_low_threshold": None,
            }, token=token)
            samples = [
                {"name": "synthetic-fire", "case_kind": "FIRE", "payload": "{\"event\":\"FIRE\"}", "expected_event": "FIRE", "expected_measurements": []},
                {"name": "synthetic-recovery", "case_kind": "RECOVERY", "payload": "{\"event\":\"RECOVERY\"}", "expected_event": "RECOVERY", "expected_measurements": []},
                {"name": "synthetic-unknown", "case_kind": "UNKNOWN", "payload": "{\"event\":\"UNMAPPED\"}", "expected_event": "UNKNOWN", "expected_measurements": []},
                {"name": "synthetic-malformed", "case_kind": "MALFORMED", "payload": "not-json", "expected_event": "UNKNOWN", "expected_measurements": []},
            ]
            tested = api.call("POST", f"/management/product-versions/{version['id']}/test", {
                "expected_revision": version["revision"], "samples": samples,
            }, token=token)
            if tested.get("passed") is not True:
                raise SmokeError("isolated synthetic product contract did not pass its parser gate")
            published = api.call("POST", f"/management/product-versions/{version['id']}/publish", {
                "expected_revision": tested["revision"],
            }, token=token)
            if published.get("status") != "PUBLISHED":
                raise SmokeError("isolated synthetic product version was not published")

            device_ids: dict[str, str] = {}
            for record in records:
                device = api.call("POST", "/management/devices", {
                    "name": "双源回放合成设备", "device_eui": record["device_eui"], "address": "本机隔离合成位置",
                    "place_id": place["id"], "product_version_id": published["id"],
                }, token=token, tenant=tenant)
                device_ids[record["device_eui"]] = device["id"]

            wait_for_mqtt(api, token, args.timeout)
            http_record = next(row for row in records if row["source"] == "http")
            mqtt_record = next(row for row in records if row["source"] == "mqtt")
            # Fixture frames intentionally have no device clock. Supply the gateway's
            # report time after enrollment; ownership must still reject undated frames.
            report_time = datetime.now(timezone.utc).isoformat()
            http_result = post_http_ingress(app_port, ingest_token, {
                "addr": http_record["device_eui"], "data": http_record["frame"], "tmietmp": report_time,
            })
            publish_mqtt(publisher, mqtt_port, {
                "addr": mqtt_record["device_eui"], "data": mqtt_record["frame"], "tmietmp": report_time,
            }, env)

            ingress_by_source = {"http": "HTTP_NOTIFY", "mqtt": "MQTT_GATEWAY_UPLINK"}
            http_message = find_message(api, token, tenant, http_record["device_eui"], ingress_by_source["http"], args.timeout)
            if http_result.get("id") != http_message.get("id"):
                raise SmokeError("HTTP ingress response and authorized message query disagree")

            event_total, alarm_total = verify_observations(api, token, tenant, records, ingress_by_source, device_ids, args.timeout)
            if event_total != 2 or alarm_total != 2:
                raise SmokeError("dual-source event/alarm counts did not match the two synthetic inputs")
            print(f"LOCAL_ONLY app=127.0.0.1:{app_port} mqtt=127.0.0.1:{mqtt_port} storage=h2-dev")
            print("HTTP_NOTIFY/turmass: NORMALIZED events=1")
            print("MQTT_GATEWAY_UPLINK/turmass: NORMALIZED events=1")
            print("AUTHORIZED DEVICE EVENT QUERIES: 2 ALARM")
            print("AUTHORIZED DEVICE ALARM QUERIES: 2 UNHANDLED")
            print("DUAL_SOURCE_REPLAY_DONE")
        finally:
            stop(app)
            stop(broker)
            app_log.close()
            broker_log.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndjson", type=Path, help="optional local sanitized replay input; otherwise use the bundled synthetic fixture")
    parser.add_argument("--app-port", type=int, help="HTTP port bound only to 127.0.0.1; defaults to an available local port")
    parser.add_argument("--mqtt-port", type=int, help="MQTT port bound only to 127.0.0.1; defaults to an available local port")
    parser.add_argument("--timeout", type=float, default=90, help="readiness/query timeout in seconds (default: 90)")
    args = parser.parse_args()
    if not 1 <= args.timeout <= 300:
        parser.error("--timeout must be between 1 and 300 seconds")
    try:
        run(args)
    except (SmokeError, OSError, AssertionError, ValueError, RuntimeError) as error:
        safe_message = str(error).replace(os.getcwd(), "[WORKSPACE]")
        print(f"DUAL_SOURCE_REPLAY_FAILED: {type(error).__name__}: {safe_message}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
