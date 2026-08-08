#!/usr/bin/env python3
"""Minimal Source-RCON client, used by run-ab.sh to drive the headless test server.

RCON rather than the server's stdin because a Gradle `runServer` task does not forward the
invoking shell's stdin to the forked JVM — there is no console to type into. RCON is the
supported remote-command path and needs no extra dependency beyond the stdlib.

Usage:
    rcon.py <host> <port> <password> "<command>"

Exits non-zero if the connection or auth fails, so the harness stops rather than silently
running an arm whose commands never landed.
"""
import socket
import struct
import sys

SERVERDATA_AUTH = 3
SERVERDATA_EXECCOMMAND = 2


def _pack(req_id: int, req_type: int, body: str) -> bytes:
    payload = struct.pack("<ii", req_id, req_type) + body.encode("utf8") + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def _read_exact(sock: socket.socket, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("RCON connection closed mid-packet")
        buf += chunk
    return buf


def _read_packet(sock: socket.socket):
    (length,) = struct.unpack("<i", _read_exact(sock, 4))
    payload = _read_exact(sock, length)
    req_id, req_type = struct.unpack("<ii", payload[:8])
    return req_id, req_type, payload[8:-2].decode("utf8", errors="replace")


def command(host: str, port: int, password: str, cmd: str, timeout: float = 10.0) -> str:
    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.settimeout(timeout)
        sock.sendall(_pack(1, SERVERDATA_AUTH, password))
        req_id, _, _ = _read_packet(sock)
        if req_id == -1:
            raise PermissionError("RCON auth failed (wrong password)")
        sock.sendall(_pack(2, SERVERDATA_EXECCOMMAND, cmd))
        _, _, body = _read_packet(sock)
        return body


def main() -> int:
    if len(sys.argv) != 5:
        print(__doc__, file=sys.stderr)
        return 2
    host, port, password, cmd = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
    try:
        print(command(host, port, password, cmd))
    except Exception as exc:  # noqa: BLE001 — the harness only needs the message
        print(f"rcon error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
