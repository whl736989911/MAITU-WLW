"""Finite credential-free Linux loopback TCP observation; no app/service starts.
Only each fresh client's receive buffer/MSS differs. Never reads client data,
changes sysctl, needs root, or claims a WebSocket/application close was observed.
"""
from contextlib import contextmanager
import fcntl
import json
import os
from pathlib import Path
import socket
import struct
import sys
import termios
import time


@contextmanager
def pair(maxseg):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        listener.settimeout(3)
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as client:
            client.settimeout(3)
            client.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024)
            if maxseg is not None:
                client.setsockopt(socket.IPPROTO_TCP, socket.TCP_MAXSEG, maxseg)
            client.connect(listener.getsockname())
            server, address = listener.accept()
            with server:
                assert address[0] == "127.0.0.1"
                yield client, server


def snapshot(client, server):
    return {"client_receive_buffer": client.getsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF),
            "client_maxseg": client.getsockopt(socket.IPPROTO_TCP, socket.TCP_MAXSEG),
            "server_maxseg": server.getsockopt(socket.IPPROTO_TCP, socket.TCP_MAXSEG),
            "server_send_buffer": server.getsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF),
            "server_queued_bytes": struct.unpack("I", fcntl.ioctl(server.fileno(), termios.TIOCOUTQ, struct.pack("I", 0)))[0]}


def burst(maxseg):
    with pair(maxseg) as (client, server):
        initial = snapshot(client, server)
        server.setblocking(False)
        started = time.monotonic()
        sent, blocked, cap = 0, False, 4 * 1024 * 1024
        while sent < cap and time.monotonic() - started < 2:
            try:
                sent += server.send(b"x" * min(4096, cap - sent))
            except BlockingIOError:
                blocked = True
                break
        return {"requested_maxseg": maxseg, "initial": initial, "final": snapshot(client, server),
                "bytes_accepted_before_first_eagain": sent, "eagain_observed": blocked,
                "elapsed_seconds": round(time.monotonic() - started, 6), "byte_cap": cap, "deadline_seconds": 2}


def paced(maxseg):
    with pair(maxseg) as (client, server):
        initial = snapshot(client, server)
        server.settimeout(0.1)
        started = time.monotonic()
        sent, timed_out = 0, False
        for index in range(160):  # 512 bytes/50ms, at most eight seconds/80KiB.
            remaining = started + index * 0.05 - time.monotonic()
            if remaining > 0:
                time.sleep(remaining)
            try:
                server.sendall(b"x" * 512)
                sent += 512
            except socket.timeout:
                timed_out = True
                break
        return {"requested_maxseg": maxseg, "initial": initial, "final": snapshot(client, server),
                "fully_sent_bytes": sent, "send_timeout_observed": timed_out,
                "elapsed_seconds": round(time.monotonic() - started, 6), "send_timeout_seconds": 0.1,
                "payload_bytes": 512, "cadence_seconds": 0.05, "max_samples": 160}


def main():
    if sys.platform != "linux" or os.geteuid() == 0:
        raise SystemExit("Run this finite socket probe as a non-root Linux user")
    results = {"status": "observed", "uid": os.geteuid(),
               "tcp_wmem_read_only": Path("/proc/sys/net/ipv4/tcp_wmem").read_text().strip(),
               "burst": [burst(None), burst(536)], "paced": [paced(None), paced(536)],
               "boundary": "Only synthetic unread loopback sockets. No application/PG/JVM/browser/credential, sysctl change, or claimed WebSocket close. Each owned socket closes after its measurement."}
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
