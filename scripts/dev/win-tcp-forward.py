#!/usr/bin/env python3
"""Windows 侧最小 TCP 转发：127.0.0.1:<listen> -> WSL 后端，避免管理员端口代理。

用法: python win-tcp-forward.py <listen_port> <target_host> <target_port>
"""
import socket
import sys
import threading

BUFFER = 32 * 1024


def pipe(source, target):
    try:
        while True:
            data = source.recv(BUFFER)
            if not data:
                break
            target.sendall(data)
    except OSError:
        pass
    finally:
        for sock in (source, target):
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            sock.close()


def handle(client, host, port):
    try:
        upstream = socket.create_connection((host, port), timeout=10)
    except OSError:
        client.close()
        return
    threading.Thread(target=pipe, args=(client, upstream), daemon=True).start()
    threading.Thread(target=pipe, args=(upstream, client), daemon=True).start()


def main():
    listen_port = int(sys.argv[1])
    host = sys.argv[2]
    port = int(sys.argv[3])
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", listen_port))
    server.listen(128)
    print(f"forwarding 127.0.0.1:{listen_port} -> {host}:{port}", flush=True)
    while True:
        client, _ = server.accept()
        threading.Thread(target=handle, args=(client, host, port), daemon=True).start()


if __name__ == "__main__":
    main()
