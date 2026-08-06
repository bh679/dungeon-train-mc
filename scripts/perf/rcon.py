#!/usr/bin/env python3
"""Minimal Source RCON client — stdlib sockets only, no dependencies.

Exists because NeoGradle's `runServer` does not wire a usable console FIFO: a
command piped to its stdin never reaches the server, and a piped `stop` leaves
the Gradle wrapper waiting forever. RCON is the only reliable way to drive a
dev dedicated server from a script.

Protocol: https://developer.valvesoftware.com/wiki/Source_RCON_Protocol

Usage as a library:
    from rcon import Rcon
    with Rcon("127.0.0.1", 25581, "password") as r:
        print(r.command("list"))

Usage as a CLI (handy for one-off pokes):
    python3 scripts/perf/rcon.py --port 25581 --password pw "list"
"""
from __future__ import annotations

import argparse
import socket
import struct
import sys

SERVERDATA_AUTH = 3
SERVERDATA_AUTH_RESPONSE = 2
SERVERDATA_EXECCOMMAND = 2
SERVERDATA_RESPONSE_VALUE = 0

# A failed auth comes back with this sentinel id regardless of what we sent.
AUTH_FAILED_ID = -1


class RconError(RuntimeError):
    pass


class Rcon:
    """One RCON connection. Use as a context manager so the socket always closes."""

    def __init__(self, host: str, port: int, password: str, timeout: float = 10.0):
        self.host = host
        self.port = port
        self.password = password
        self.timeout = timeout
        self._sock: socket.socket | None = None
        self._next_id = 1

    def __enter__(self) -> "Rcon":
        self.connect()
        return self

    def __exit__(self, *_exc) -> None:
        self.close()

    def connect(self) -> None:
        self._sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        request_id = self._send(SERVERDATA_AUTH, self.password)
        # The server answers auth with an empty RESPONSE_VALUE followed by the
        # AUTH_RESPONSE proper; some builds send only the latter, so skip any
        # RESPONSE_VALUE frames until the auth verdict arrives.
        while True:
            response_id, kind, _body = self._recv()
            if kind == SERVERDATA_AUTH_RESPONSE:
                break
        if response_id == AUTH_FAILED_ID or response_id != request_id:
            raise RconError("RCON authentication failed — wrong rcon.password?")

    def close(self) -> None:
        if self._sock is not None:
            self._sock.close()
            self._sock = None

    def command(self, text: str) -> str:
        """Run one command, returning the server's reply text (often empty)."""
        if self._sock is None:
            raise RconError("not connected")
        request_id = self._send(SERVERDATA_EXECCOMMAND, text)
        response_id, _kind, body = self._recv()
        if response_id != request_id:
            raise RconError(f"RCON id mismatch: sent {request_id}, got {response_id}")
        return body

    def _send(self, kind: int, body: str) -> int:
        request_id = self._next_id
        self._next_id += 1
        payload = struct.pack("<ii", request_id, kind) + body.encode("utf-8") + b"\x00\x00"
        self._sock.sendall(struct.pack("<i", len(payload)) + payload)
        return request_id

    def _recv(self) -> tuple[int, int, str]:
        length = struct.unpack("<i", self._read_exactly(4))[0]
        payload = self._read_exactly(length)
        request_id, kind = struct.unpack("<ii", payload[:8])
        return request_id, kind, payload[8:-2].decode("utf-8", errors="replace")

    def _read_exactly(self, count: int) -> bytes:
        chunks = []
        remaining = count
        while remaining > 0:
            chunk = self._sock.recv(remaining)
            if not chunk:
                raise RconError("connection closed mid-packet — did the server stop?")
            chunks.append(chunk)
            remaining -= len(chunk)
        return b"".join(chunks)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("command", nargs="+", help="command(s) to run in order")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25581)
    parser.add_argument("--password", required=True)
    args = parser.parse_args(argv)

    try:
        with Rcon(args.host, args.port, args.password) as rcon:
            for text in args.command:
                print(rcon.command(text))
    except (OSError, RconError) as exc:
        print(f"rcon: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
