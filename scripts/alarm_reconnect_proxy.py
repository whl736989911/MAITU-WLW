"""Owned-app HTTP/1 loopback fault fixture; never deploy this module.

Construction/import performs no IO. Only ``with NativeFaultProxy(target_port=port)``
starts listeners. Route the browser AND its API bootstrap through ``proxy.origin``;
allow that exact origin in the owned backend first. Host, Origin, framing, body,
WebSocket handshake and frames are relayed byte-for-byte, without retry/rewrite.

Give ``control_url`` and ``control_token`` ONLY to the Node driver via its stdin
context. Never include them in page config, URLs, console, screenshots or artifacts.
The browser gate must exclude the control origin. Node sends POST /drop with
Authorization: Bearer <control_token> and Content-Length: 0 (empty body), or
Content-Type: application/json, Content-Length: 2 and exactly {}. No CORS headers
are emitted; requests carrying Origin or Sec-Fetch-* are rejected. A capability
cannot identify the caller's runtime: 'Node-only' also requires the driver's gate
and secret isolation, not merely loopback or an HTTP header.

Only a first-request alert WebSocket with a verified backend 101 is faultable.
Oversized/malformed probes, prior keepalive HTTP requests and informational
responses are conservatively not faultable; their bytes still pass unchanged.
HTTP/2, TLS, HTTP CONNECT, forward-proxy routing and DNS are not implemented.
The backend is ALWAYS the caller-selected literal 127.0.0.1 and fixed port.

No payload/header/error text is logged, persisted, or exposed in stats/repr.
Credentials necessarily exist transiently in Python relay/probe/control buffers,
kernel TCP buffers and backend/browser/framework memory. This is not secure
memory erasure, protection from a debugger/core dump, or a process sandbox.
Dropping uses real TCP shutdown/close, NEVER a fabricated WebSocket close/frame;
it does not guarantee a particular Chrome close code or exact FIN/reset timing.
"""
from __future__ import annotations

import base64
import errno
import hashlib
import hmac
import json
import secrets
import selectors
import socket
import threading
import time


_HEADER_LIMIT = 16384
_BUFFER_LIMIT = 65536
_CONTROL_HEADER_LIMIT = 8192
_RELAY_LIMIT = 56
_CONTROL_LIMIT = 8  # <=64 clients / <=120 connected sockets, plus two listeners.
_ALERT_PATHS = {b"/api/management/alerts", b"/api/customer/alerts"}
_GUID = b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def _close(sock):
    if sock is None:
        return True
    try:
        sock.shutdown(socket.SHUT_RDWR)
    except OSError:
        pass  # EOF, already closed, or a not-yet-connected socket is ordinary.
    try:
        sock.close()
    except OSError:
        return False
    return sock.fileno() == -1


def _headers(raw):
    """Parse only a bounded prefix; never retain or report parser failures."""
    lines = raw.split(b"\r\n")
    fields = {}
    for line in lines[1:]:
        if not line:
            continue
        if line[:1] in (b" ", b"\t") or b":" not in line:
            return None, None
        name, value = line.split(b":", 1)
        if not name or any(c <= 32 or c >= 127 for c in name):
            return None, None
        name = name.lower()
        fields.setdefault(name, []).append(value.strip(b" \t"))
    return lines[0], fields


def _tokens(fields, name):
    return {part.strip().lower() for row in fields.get(name, []) for part in row.split(b",")}


class _Relay:
    def __init__(self, client, upstream):
        self.sockets = [client, upstream]
        self.buffers = [bytearray(), bytearray()]  # queued FOR socket 0/1
        self.eof = [False, False]
        self.write_closed = [False, False]
        self.connecting = True
        self.connect_deadline = time.monotonic() + 5.0
        self.probe_deadline = time.monotonic() + 10.0
        self.request = bytearray()
        self.response = bytearray()
        self.expected_accept = None
        self.verified = False
        self.closed = False


class _Control:
    def __init__(self, sock):
        self.socket = sock
        self.buffer = bytearray()
        self.output = bytearray()
        self.deadline = time.monotonic() + 2.0
        self.header_end = None
        self.length = None


class NativeFaultProxy:
    """Bounded context manager. Startup/shutdown belong to the calling Lead.

    Public API: origin, control_url (full /drop URL), control_token, stats().
    stats() contains integer counts and boolean lifecycle flags only. Exceptions
    have static messages. __exit__ never suppresses the caller's exception and
    raises RuntimeError if resource cleanup is uncertain. Instances are single-use;
    enter/exit must be called serially by the owning thread.
    """

    def __init__(self, target_host="127.0.0.1", target_port=None):
        if target_host != "127.0.0.1":
            raise ValueError("Fault proxy requires literal IPv4 loopback")
        if type(target_port) is not int or not 1 <= target_port <= 65535:
            raise ValueError("Fault proxy requires a fixed valid TCP port")
        self._target = ("127.0.0.1", target_port)
        self.origin = None
        self.control_url = None
        self.control_token = None
        self._used = False
        self._stop = threading.Event()
        self._thread = None
        self._selector = None
        self._listeners = []
        self._relays = set()
        self._controls = set()
        self._lock = threading.Lock()
        self._counts = dict(accepted=0, refused=0, active_relays=0,
                            active_controls=0, verified_ws_active=0,
                            verified_ws_total=0, dropped=0, relay_failures=0,
                            running=False, worker_failed=False,
                            cleanup_uncertain=False)

    def __repr__(self):
        return "<NativeFaultProxy fixture-only; credentials withheld>"

    def stats(self):
        with self._lock:
            return dict(self._counts)

    def _count(self, **changes):
        with self._lock:
            for name, amount in changes.items():
                self._counts[name] += amount

    def _flag(self, **values):
        with self._lock:
            self._counts.update(values)

    def __enter__(self):
        if self._used:
            raise RuntimeError("Fault proxy instances are single-use")
        self._used = True
        try:
            self.control_token = secrets.token_urlsafe(32)
            self._selector = selectors.DefaultSelector()
            for kind in ("relay", "control"):
                for _ in range(8):
                    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                    self._listeners.append(listener)
                    listener.bind(("127.0.0.1", 0))
                    port = listener.getsockname()[1]
                    if port != self._target[1]:
                        break
                    _close(listener)
                else:
                    raise RuntimeError("Fault proxy could not avoid its target port")
                listener.listen(16)
                listener.setblocking(False)
                self._selector.register(listener, selectors.EVENT_READ, kind)
                if kind == "relay":
                    self.origin = "http://127.0.0.1:" + str(port)
                else:
                    self.control_url = "http://127.0.0.1:" + str(port) + "/drop"
            self._flag(running=True)
            self._thread = threading.Thread(target=self._run,
                                            name="owned-native-fault-proxy", daemon=True)
            self._thread.start()
            return self
        except Exception:
            self._cleanup()
            self.control_token = None
            raise RuntimeError("Fault proxy startup failed") from None

    def __exit__(self, exc_type, exc, traceback):
        self._stop.set()
        if self._thread is not None:
            self._thread.join(5.0)
        if self._thread is not None and self._thread.is_alive():
            self._flag(cleanup_uncertain=True)
            # Best effort wake/terminate; the owner thread remains the only
            # selector mutator. Never imply that a timed-out join was cleanup.
            for listener in self._listeners:
                _close(listener)
            for relay in tuple(self._relays):
                for sock in relay.sockets:
                    _close(sock)
            for control in tuple(self._controls):
                _close(control.socket)
            self.control_token = None
            raise RuntimeError("Fault proxy cleanup uncertain") from None
        self.control_token = None
        if self.stats()["cleanup_uncertain"]:
            raise RuntimeError("Fault proxy cleanup uncertain") from None
        if self.stats()["worker_failed"]:
            raise RuntimeError("Fault proxy worker failed; runtime evidence invalid") from None
        return False

    def _interest(self, sock, events, data):
        try:
            self._selector.get_key(sock)
        except KeyError:
            if events:
                self._selector.register(sock, events, data)
        else:
            if events:
                self._selector.modify(sock, events, data)
            else:
                self._selector.unregister(sock)

    def _forget(self, sock):
        try:
            self._selector.unregister(sock)
        except (KeyError, ValueError, OSError):
            pass
        if not _close(sock):
            self._flag(cleanup_uncertain=True)

    def _close_relay(self, relay):
        if relay.closed:
            return
        relay.closed = True
        for sock in relay.sockets:
            self._forget(sock)
        for buf in relay.buffers:
            buf.clear()
        relay.request = relay.response = relay.expected_accept = None
        self._relays.discard(relay)
        self._count(active_relays=-1, verified_ws_active=-int(relay.verified))

    def _close_control(self, control):
        if control in self._controls:
            self._controls.remove(control)
            self._forget(control.socket)
            control.buffer.clear()
            control.output.clear()
            self._count(active_controls=-1)

    def _accept(self, listener, kind):
        # A bounded batch prevents an accept flood from starving drop/stop.
        for _ in range(8):
            try:
                client, _ = listener.accept()
            except BlockingIOError:
                return
            self._count(accepted=1)
            client.setblocking(False)
            if self._stop.is_set() or (kind == "relay" and len(self._relays) >= _RELAY_LIMIT) or (kind == "control" and len(self._controls) >= _CONTROL_LIMIT):
                if not _close(client):
                    self._flag(cleanup_uncertain=True)
                self._count(refused=1)
                continue
            if kind == "control":
                control = _Control(client)
                self._controls.add(control)
                self._count(active_controls=1)
                self._interest(client, selectors.EVENT_READ, control)
                continue
            upstream = None
            relay = None
            try:
                upstream = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                upstream.setblocking(False)
                relay = _Relay(client, upstream)
                self._relays.add(relay)
                self._count(active_relays=1)
                result = upstream.connect_ex(self._target)
                if result not in (0, errno.EINPROGRESS, errno.EWOULDBLOCK,
                                  errno.EALREADY, 10035, 10036, 10037):
                    raise OSError()
                relay.connecting = result != 0
                self._refresh(relay)
            except OSError:
                self._count(relay_failures=1)
                if relay is not None:
                    self._close_relay(relay)
                else:
                    client_closed = _close(client)
                    upstream_closed = _close(upstream)
                    if not client_closed or not upstream_closed:
                        self._flag(cleanup_uncertain=True)

    def _refresh(self, relay):
        for side, sock in enumerate(relay.sockets):
            if relay.eof[1 - side] and not relay.buffers[side] and not relay.write_closed[side] and not relay.connecting:
                try:
                    sock.shutdown(socket.SHUT_WR)
                except OSError:
                    pass
                relay.write_closed[side] = True
        if all(relay.eof) and not any(relay.buffers):
            self._close_relay(relay)
            return
        for side, sock in enumerate(relay.sockets):
            events = 0
            if not relay.eof[side] and len(relay.buffers[1 - side]) < _BUFFER_LIMIT and not (side == 1 and relay.connecting):
                events |= selectors.EVENT_READ
            if relay.buffers[side] or (side == 1 and relay.connecting):
                events |= selectors.EVENT_WRITE
            self._interest(sock, events, (relay, side))

    def _probe(self, relay, side, chunk):
        probe = relay.request if side == 0 else relay.response
        if probe is None:
            return
        probe.extend(chunk[:_HEADER_LIMIT - len(probe)])
        end = probe.find(b"\r\n\r\n")
        if end < 0 and len(probe) < _HEADER_LIMIT:
            return
        first, fields = _headers(bytes(probe[:end])) if end >= 0 else (None, None)
        if side == 0:
            relay.request = None
            if fields is not None:
                parts = first.split(b" ")
                if len(parts) == 3 and parts[0] == b"GET" and parts[1].split(b"?", 1)[0] in _ALERT_PATHS and parts[2] == b"HTTP/1.1" and fields.get(b"upgrade") == [b"websocket"] and b"upgrade" in _tokens(fields, b"connection"):
                    keys = fields.get(b"sec-websocket-key", [])
                    if len(keys) == 1:
                        try:
                            if len(base64.b64decode(keys[0], validate=True)) == 16:
                                relay.expected_accept = base64.b64encode(hashlib.sha1(keys[0] + _GUID).digest())
                        except ValueError:
                            pass
            if relay.expected_accept is None:
                relay.response = None
        else:
            relay.response = None
            parts = first.split(b" ", 2) if first else []
            if fields is not None and len(parts) >= 2 and parts[:2] == [b"HTTP/1.1", b"101"] and fields.get(b"upgrade") == [b"websocket"] and b"upgrade" in _tokens(fields, b"connection") and relay.expected_accept is not None and fields.get(b"sec-websocket-accept") == [relay.expected_accept]:
                relay.verified = True
                self._count(verified_ws_active=1, verified_ws_total=1)
            relay.expected_accept = None
        probe.clear()

    def _relay_io(self, relay, side, mask):
        if relay.closed:
            return
        sock = relay.sockets[side]
        try:
            if side == 1 and relay.connecting:
                if sock.getsockopt(socket.SOL_SOCKET, socket.SO_ERROR):
                    raise OSError()
                relay.connecting = False
            if mask & selectors.EVENT_WRITE and relay.buffers[side]:
                try:
                    sent = sock.send(relay.buffers[side])
                    if sent == 0:
                        raise OSError()
                    del relay.buffers[side][:sent]
                except BlockingIOError:
                    pass
            if mask & selectors.EVENT_READ and not relay.eof[side]:
                room = _BUFFER_LIMIT - len(relay.buffers[1 - side])
                if room:
                    try:
                        chunk = sock.recv(min(16384, room))
                    except BlockingIOError:
                        chunk = None
                    if chunk == b"":
                        relay.eof[side] = True
                    elif chunk:
                        self._probe(relay, side, chunk)
                        relay.buffers[1 - side].extend(chunk)
            self._refresh(relay)
        except OSError:
            self._count(relay_failures=1)
            self._close_relay(relay)

    def _reply(self, control, status, body):
        payload = json.dumps(body, separators=(",", ":")).encode("ascii")
        label = {200: "OK", 400: "Bad Request", 403: "Forbidden"}[status]
        control.output.extend(("HTTP/1.1 %d %s\r\nContent-Type: application/json\r\nContent-Length: %d\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n" % (status, label, len(payload))).encode("ascii") + payload)
        control.buffer.clear()
        self._interest(control.socket, selectors.EVENT_WRITE, control)

    def _control_io(self, control, mask):
        if control not in self._controls:
            return
        try:
            if mask & selectors.EVENT_WRITE:
                try:
                    sent = control.socket.send(control.output)
                except BlockingIOError:
                    return
                if sent == 0:
                    self._close_control(control)
                    return
                del control.output[:sent]
                if not control.output:
                    self._close_control(control)
                return
            try:
                chunk = control.socket.recv(min(4096, _CONTROL_HEADER_LIMIT + 2 - len(control.buffer)))
            except BlockingIOError:
                return
            if not chunk:
                self._close_control(control)
                return
            control.buffer.extend(chunk)
            if control.header_end is None:
                end = control.buffer.find(b"\r\n\r\n")
                if end < 0:
                    if len(control.buffer) >= _CONTROL_HEADER_LIMIT:
                        self._reply(control, 400, {"ok": False})
                    return
                if end + 4 > _CONTROL_HEADER_LIMIT:
                    self._reply(control, 400, {"ok": False})
                    return
                first, fields = _headers(bytes(control.buffer[:end]))
                if fields is None or first != b"POST /drop HTTP/1.1" or b"origin" in fields or any(name.startswith(b"sec-fetch-") for name in fields):
                    self._reply(control, 400, {"ok": False})
                    return
                auth = fields.get(b"authorization", [])
                expected = ("Bearer " + self.control_token).encode("ascii")
                if len(auth) != 1 or not hmac.compare_digest(auth[0], expected):
                    self._reply(control, 403, {"ok": False})
                    return
                lengths = fields.get(b"content-length", [])
                if b"transfer-encoding" in fields or b"expect" in fields or lengths not in ([b"0"], [b"2"]):
                    self._reply(control, 400, {"ok": False})
                    return
                control.length = int(lengths[0])
                if control.length and fields.get(b"content-type") != [b"application/json"]:
                    self._reply(control, 400, {"ok": False})
                    return
                control.header_end = end + 4
                # Discard capability-bearing headers immediately after validation.
                del control.buffer[:control.header_end]
            if len(control.buffer) < control.length:
                return
            if len(control.buffer) != control.length or (control.length and control.buffer != b"{}"):
                self._reply(control, 400, {"ok": False})
                return
            dropped = 0
            for relay in tuple(self._relays):
                if relay.verified and not relay.closed:
                    self._close_relay(relay)
                    dropped += 1
            self._count(dropped=dropped)
            self._reply(control, 200, {"ok": True, "dropped": dropped, "stats": self.stats()})
        except OSError:
            self._close_control(control)

    def _run(self):
        try:
            while not self._stop.is_set():
                for key, mask in self._selector.select(0.1):
                    if self._stop.is_set():
                        break
                    data = key.data
                    if isinstance(data, str):
                        self._accept(key.fileobj, data)
                    elif isinstance(data, _Control):
                        self._control_io(data, mask)
                    else:
                        self._relay_io(data[0], data[1], mask)
                now = time.monotonic()
                for relay in tuple(self._relays):
                    if relay.connecting and now >= relay.connect_deadline:
                        self._count(relay_failures=1)
                        self._close_relay(relay)
                    elif now >= relay.probe_deadline:
                        relay.request = relay.response = relay.expected_accept = None
                for control in tuple(self._controls):
                    if now >= control.deadline:
                        self._close_control(control)
        except Exception:
            # Never let threading's excepthook print arbitrary exception text.
            self._flag(worker_failed=True)
        finally:
            self._cleanup()

    def _cleanup(self):
        uncertain = False
        for relay in tuple(self._relays):
            try:
                self._close_relay(relay)
            except Exception:
                uncertain = True
                for sock in relay.sockets:
                    _close(sock)
        for control in tuple(self._controls):
            try:
                self._close_control(control)
            except Exception:
                uncertain = True
                _close(control.socket)
        for listener in self._listeners:
            if not _close(listener):
                uncertain = True
        if self._selector is not None:
            try:
                self._selector.close()
            except Exception:
                uncertain = True
        self._flag(running=False, cleanup_uncertain=uncertain or self.stats()["cleanup_uncertain"])
