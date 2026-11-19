# Dual-source Turmass smoke

Run on Linux with Java 21, `mosquitto`, `mosquitto_pub`, and the existing backend JAR at `backend/target/fire-platform-backend-0.1.0-SNAPSHOT.jar`:

```sh
bash scripts/package.sh
python3 scripts/smoke/dual-source-replay.py
```

The runner starts Mosquitto and the application bound only to `127.0.0.1`, uses a fresh temporary H2 database and an ephemeral authenticated bootstrap account, then removes the temporary database and stops both processes. The synthetic fixture sends one authenticated HTTP `turmass` report and one Turmass MQTT uplink, then uses logged-in management reads to verify each source message, normalized `ALARM` event, device event, and unhandled alarm. No old/remote database, external broker, real device, or external notification target is used.

The runner supplies a synthetic gateway report time after device enrollment so zero-clock fixture frames pass the normal ownership check. It does not change the application’s rejection of undated data at an ownership boundary.

Ports can be allocated by the runner or supplied explicitly when management assigns free local ports:

```sh
python3 scripts/smoke/dual-source-replay.py --app-port 18081 --mqtt-port 18884
```

To replay a manager-provided sanitized NDJSON file, pass it explicitly:

```sh
python3 scripts/smoke/dual-source-replay.py --ndjson /path/to/sanitized.ndjson
```

The file must contain exactly two one-line JSON records, one `http` and one `mqtt`, with only `source`, `device_eui`, and `frame` fields. `device_eui` must be an eight-character hexadecimal synthetic Turmass terminal ID embedded in a valid Java 7E fire-alarm frame. The runner never discovers `.local-ingest`; it rejects any explicit input whose resolved path is under `.local-ingest`.

Expected output (ports vary):

```text
LOCAL_ONLY app=127.0.0.1:<port> mqtt=127.0.0.1:<port> storage=h2-dev
HTTP_NOTIFY/turmass: NORMALIZED events=1
MQTT_GATEWAY_UPLINK/turmass: NORMALIZED events=1
AUTHORIZED DEVICE EVENT QUERIES: 2 ALARM
AUTHORIZED DEVICE ALARM QUERIES: 2 UNHANDLED
DUAL_SOURCE_REPLAY_DONE
```
