#!/usr/bin/env python3
"""Local stand-in for the dp-relay Live chat, for checking Live Translations in a dev client.

Point a dev client at it with DUNGEONTRAIN_RELAY_BASE_URL=http://127.0.0.1:<port>/dev (and the
LIVE twin) and wear a TV: the stub says a stream is on air, answers the chat poll, and plays
scripted scenes into the chat ring on request, the way the relay does while the Live
Translations reward is on. Every poll's `lang=` is logged, which is the mod's half of the deal.

    python3 -I scripts/live/stub-chat-relay.py --port 8788

    POST /scene/<name>   queue a scene: inline  (a line read with its tr already on it)
                                        late    (a line, then its translations a poll later)
                                        delete  (a translated line, then its deletion)
    GET  /log            what was asked (polls with their lang, joins, sends)

Dev tool only; never exposed beyond loopback.
"""
import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

LOCK = threading.Lock()
STATE = {"seq": 10, "steps": [], "log": []}
ZH = {"zh_cn": "苦力怕又炸了我一次"}  # the creeper got me again
DE = {"de_de": "Der Creeper hat mich schon wieder erwischt"}


def line(seq: int, content: str, tr=None, author: str = "Alice") -> dict:
    out = {"seq": seq, "ts": int(time.time() * 1000), "author": author, "uuid": None, "content": content, "source": "discord"}
    return {**out, "tr": tr} if tr else out


def scene(name: str) -> list:
    """The steps a scene plays, one per poll; each is a function of the seq it is given."""
    if name == "inline":
        return [lambda s: {"messages": [line(s, "the creeper got me again (inline)", {**ZH, **DE})]}]
    if name == "late":
        return [lambda s: {"messages": [line(s, "gg that was close (late)")]},
                lambda s: {"translations": [{"line": s - 1, "tr": {"zh_cn": "gg 好险 (late)", **DE}}]}]
    if name == "delete":
        return [lambda s: {"messages": [line(s, "this one gets deleted", ZH)]},
                lambda s: {"deleted": [s - 1]}]
    if name == "named":
        return [lambda s: {"messages": [line(s, "first line, translated later")]},
                lambda s: {"messages": [line(s, "a second line in between", author="Bob")]},
                lambda s: {"translations": [{"line": s - 2, "tr": {"zh_cn": "第一行，稍后翻译"}}]}]
    return []


class Handler(BaseHTTPRequestHandler):
    server_version = "dt-stub-chat-relay"

    def log_message(self, fmt, *args):
        pass

    def _send(self, code: int, body) -> None:
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _note(self, what: str) -> None:
        with LOCK:
            STATE["log"].append("%.1f %s" % (time.time(), what))
        print("[stub] " + what, flush=True)

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        port = self.server.server_address[1]
        if u.path.endswith("/live/status") or u.path.endswith("/live/public-status"):
            return self._send(200, {"ok": True, "live": True, "session": "stub", "streamer": "Alice", "language": "en_us",
                                    "playlistUrl": "http://127.0.0.1:%d/live.m3u8" % port, "viewers": 1, "cap": 100, "slot": True})
        if u.path.endswith("/live/chat"):
            since = q.get("since", [None])[0]
            lang = q.get("lang", [None])[0]
            with LOCK:
                page = {"ok": True, "live": True, "chat": True, "deleted": [], "reactions": [], "translations": [], "messages": []}
                if since is not None and STATE["steps"]:
                    STATE["seq"] += 1
                    page.update(STATE["steps"].pop(0)(STATE["seq"]))
                page["seq"] = STATE["seq"]
            self._note("poll since=%s lang=%s -> seq %d %s" % (since, lang, page["seq"],
                       "msgs=%d tr=%d del=%d" % (len(page["messages"]), len(page["translations"]), len(page["deleted"]))))
            return self._send(200, page)
        if u.path == "/log":
            with LOCK:
                return self._send(200, STATE["log"])
        return self._send(404, {"error": "unknown_route"})

    def do_POST(self):
        u = urlparse(self.path)
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n).decode("utf-8", "replace")
        if u.path.startswith("/scene/"):
            name = u.path.split("/")[2]
            steps = scene(name)
            with LOCK:
                STATE["steps"] = [*STATE["steps"], *steps]
            self._note("scene %s queued (%d steps)" % (name, len(steps)))
            return self._send(200, {"ok": True, "steps": len(steps)})
        if u.path.endswith("/live/chat/join"):
            self._note("join " + body)
            return self._send(200, {"ok": True})
        if u.path.endswith("/live/chat/send"):
            self._note("send " + body)
            return self._send(200, {"ok": True, "seq": 0})
        return self._send(404, {"error": "unknown_route"})


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=8788)
    args = p.parse_args()
    print("[stub] chat relay on http://127.0.0.1:%d" % args.port, flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
