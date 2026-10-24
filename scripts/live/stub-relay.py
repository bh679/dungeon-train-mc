#!/usr/bin/env python3
"""Local stand-in for the dp-relay Live Feed, for measuring stream delay (glass to glass) in dev.

Point a dev client at it with DUNGEONTRAIN_RELAY_BASE_URL=http://127.0.0.1:<port>/dev and wear the
Live Eye. The stub answers what the streamer's client needs (claim, presign, stop), stores the PUT
segments, and serves them back the way the live bucket's edge would: the playlist is held for the
max-age it was uploaded with (2 s, or 1 s for a Quicker Streams session), segments never change.

    python3 -I scripts/live/stub-relay.py --port 8787 --out <dir> [--quick] [--relay-public <dp-relay>/public/dungeontrain]

--quick makes every claim a Quicker Streams one (--quick-seconds, default 3: segmentSeconds 3,
listSize 20, keyframeSeconds 3), as the relay does while the reward is on.

--second-streamer NAME plays the Two Streams reward: every status carries a `second` block (a second
player NAME live on the relay's `second` slot, at a playlist nobody uploads to), and a claim made
while the first session is live answers `channel: "second"` instead of taking it over. Each status
GET logs its `slot=` query so the viewer's second-slot flag can be checked. Measurements:

- Website: open http://127.0.0.1:<port>/harness.html (needs --relay-public). It plays the stream
  with the relay's own live-player.js hls.js settings and posts `now - hls.playingDate` once a second.
- In-game TV: run the TV's own ffmpeg command against the playlist (the stub logs each GET with its
  User-Agent); a TV shows segment N's first frame as it fetches it, so delay(N) = GET(N) - PDT(N).
- GET /report: per-segment upload lateness (PUT time - PDT - segment length), TV and web delay stats,
  and the newest segments' sizes (an adaptive-quality step shows as smaller segments).

--throttle-kbps N caps how fast PUT bodies are read, to play a streamer whose upload can't keep up;
GET /ctl?throttle=N changes it while running (0 = no cap).

The segments' wall-clock start (PDT) comes from the encoder's #EXT-X-PROGRAM-DATE-TIME tags.
Dev tool only; never exposed beyond loopback.
"""

import argparse
import json
import os
import re
import statistics
import threading
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

SEG_RE = re.compile(r"^seg\d{5}\.ts$")
PDT_RE = re.compile(r"^#EXT-X-PROGRAM-DATE-TIME:(.+)$")
LOCK = threading.Lock()

STATE = {
    "session": None, "segment_seconds": 10, "list_size": 6,
    "puts": {},        # (session, name) -> first PUT time (s)
    "pdt": {},         # (session, segname) -> PDT epoch s
    "gets": [],        # (t, session, name, ua)
    "web": [],         # (t, delay_s)
    "presigns": [],    # t
    "files": {},       # (session, name) -> (bytes, headers)
    "edge": {},        # (session, name) -> (served_at, bytes)
    "throttle_kbps": 0,  # PUT bodies are read no faster than this (0 = no cap)
}

THROTTLE_CHUNK = 16 * 1024


def read_throttled(rfile, n):
    """Reads n bytes, sleeping between chunks so the rate stays under STATE["throttle_kbps"]."""
    start = time.time()
    chunks, got = [], 0
    while got < n:
        chunk = rfile.read(min(THROTTLE_CHUNK, n - got))
        if not chunk:
            break
        chunks.append(chunk)
        got += len(chunk)
        kbps = STATE["throttle_kbps"]
        if kbps > 0:
            due = got * 8 / (kbps * 1000)
            ahead = due - (time.time() - start)
            if ahead > 0:
                time.sleep(ahead)
    return b"".join(chunks)


def parse_pdt(text, session):
    pending = None
    for line in text.splitlines():
        line = line.strip()
        m = PDT_RE.match(line)
        if m:
            try:
                pending = datetime.fromisoformat(m.group(1).replace("Z", "+00:00")).timestamp()
            except ValueError:
                pending = None
        elif SEG_RE.match(line):
            if pending is not None:
                STATE["pdt"].setdefault((session, line), pending)
            pending = None


def pct(values, p):
    if not values:
        return None
    s = sorted(values)
    return round(s[min(len(s) - 1, int(round(p / 100 * (len(s) - 1))))], 2)


def summary(values):
    return {"n": len(values), "median": pct(values, 50), "p90": pct(values, 90), "min": pct(values, 0), "max": pct(values, 100)}


def report():
    with LOCK:
        seg = STATE["segment_seconds"]
        late = []
        for (session, name), t in STATE["puts"].items():
            pdt = STATE["pdt"].get((session, name))
            if SEG_RE.match(name) and pdt is not None:
                late.append(t - pdt - seg)
        tv = []
        for (t, session, name, ua) in STATE["gets"]:
            if SEG_RE.match(name) and "Lavf" in ua:
                pdt = STATE["pdt"].get((session, name))
                if pdt is not None:
                    tv.append(t - pdt)
        web = [d for (_, d) in STATE["web"]]
        span = (STATE["presigns"][-1] - STATE["presigns"][0]) / 60 if len(STATE["presigns"]) > 1 else 0
        return {
            "segmentSeconds": seg, "listSize": STATE["list_size"], "session": STATE["session"],
            "uploadLatenessS": summary(late),
            "tvDelayS": summary(tv),
            "webDelayS": summary(web),
            "presignsPerMin": round(len(STATE["presigns"]) / span, 1) if span else None,
            "throttleKbps": STATE["throttle_kbps"],
            "recentSegments": recent_segments(seg),
        }


def recent_segments(seg, count=30):
    """The newest segments of the current session: size, and how late each landed."""
    session = STATE["session"]
    rows = []
    for (s, name), (data, _) in STATE["files"].items():
        if s != session or not SEG_RE.match(name):
            continue
        pdt = STATE["pdt"].get((s, name))
        put = STATE["puts"].get((s, name))
        late = round(put - pdt - seg, 1) if pdt is not None and put is not None else None
        rows.append({"name": name, "kb": len(data) // 1024, "lateS": late})
    rows.sort(key=lambda r: r["name"])
    return rows[-count:]


class Handler(BaseHTTPRequestHandler):
    server_version = "dt-stub-relay"

    def log_message(self, fmt, *args):
        pass

    def _send(self, code, body=b"", ctype="application/json", extra=None):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self):
        n = int(self.headers.get("Content-Length") or 0)
        try:
            return json.loads(self.rfile.read(n) or b"{}")
        except ValueError:
            return {}

    def do_OPTIONS(self):
        self._send(204, b"", extra={"Access-Control-Allow-Methods": "GET, POST, PUT", "Access-Control-Allow-Headers": "*"})

    def do_POST(self):
        path = urlparse(self.path).path
        port = self.server.server_address[1]
        if path.endswith("/live/claim"):
            session = "%x-stub" % int(time.time() * 1000)
            with LOCK:
                channel = "second" if ARGS.second_streamer and STATE["session"] else "main"
                if channel == "main":
                    STATE["session"] = session
            out = {"ok": True, "channel": channel, "session": session, "token": "tok-" + session,
                   "playlistUrl": "http://127.0.0.1:%d/files/%s/live.m3u8" % (port, session)}
            if ARGS.quick:
                seg = ARGS.quick_seconds
                out.update(segmentSeconds=seg, listSize=max(6, -(-60 // seg)))
                if seg % 2:
                    out["keyframeSeconds"] = seg if seg <= 4 else 1
            print("[stub] claim -> session %s (%s)" % (session, "%d s quick" % ARGS.quick_seconds if ARGS.quick else "default cadence"), flush=True)
            return self._send(200, out)
        if path.endswith("/live/presign"):
            body = self._json()
            session = STATE["session"]
            urls = {}
            max_age = 1 if ARGS.quick and ARGS.quick_seconds <= 4 else 2
            for f in body.get("files", []):
                name = f.get("name") if isinstance(f, dict) else f
                kind = "playlist" if name == "live.m3u8" else "segment"
                headers = {"content-type": "application/vnd.apple.mpegurl" if kind == "playlist" else "video/MP2T",
                           "cache-control": "public, max-age=%d" % max_age if kind == "playlist" else "public, max-age=86400, immutable"}
                urls[name] = {"url": "http://127.0.0.1:%d/files/%s/%s" % (port, session, name), "headers": headers,
                              "expiresAt": int(time.time() * 1000) + 120000}
            with LOCK:
                STATE["presigns"].append(time.time())
            return self._send(200, {"ok": True, "session": session, "urls": urls, "viewers": {"total": 1, "game": 1}})
        if path.endswith("/live/stop"):
            print("[stub] stop %s" % self._json().get("reason"), flush=True)
            return self._send(200, {"ok": True})
        if path == "/measure":
            body = self._json()
            d = body.get("delayS")
            if isinstance(d, (int, float)) and 0 <= d < 600:
                with LOCK:
                    STATE["web"].append((time.time(), float(d)))
            return self._send(204)
        return self._send(404, {"error": "unknown_route"})

    def do_PUT(self):
        parts = urlparse(self.path).path.strip("/").split("/")
        # /files/<session>/<name> is the stub's own layout; /main/<session>/<name> is the bucket's, so a
        # relay pointed at this stub as its LIVE_PUBLIC_BASE finds the same files.
        if len(parts) != 3 or parts[0] not in ("files", "main"):
            return self._send(404)
        _, session, name = parts
        if not (SEG_RE.match(name) or name == "live.m3u8"):
            return self._send(400)
        n = int(self.headers.get("Content-Length") or 0)
        data = read_throttled(self.rfile, n)
        now = time.time()
        headers = {"Content-Type": self.headers.get("Content-Type", ""), "Cache-Control": self.headers.get("Cache-Control", "")}
        with LOCK:
            STATE["files"][(session, name)] = (data, headers)
            STATE["puts"].setdefault((session, name), now)
            if name == "live.m3u8":
                parse_pdt(data.decode("utf-8", "replace"), session)
        os.makedirs(os.path.join(ARGS.out, session), exist_ok=True)
        with open(os.path.join(ARGS.out, session, name), "wb") as fh:
            fh.write(data)
        return self._send(200)

    def do_HEAD(self):
        # A relay's readiness check HEADs the first segment on the public base; _send drops the body.
        self.do_GET()

    def do_GET(self):
        path = urlparse(self.path).path
        if path.endswith("/live/status") or path.endswith("/live/public-status"):
            s = STATE["session"]
            query = urlparse(self.path).query
            if ARGS.second_streamer:
                print("[stub] status slot=%s (%s)" % ("second" if "slot=second" in query else "main", query[:120]), flush=True)
            port = self.server.server_address[1]
            second = None
            if ARGS.second_streamer:
                second = {"live": True, "session": "second-stub", "streamer": ARGS.second_streamer,
                          "playlistUrl": "http://127.0.0.1:%d/files/second-stub/live.m3u8" % port,
                          "segmentSeconds": STATE["segment_seconds"]}
            if not s:
                # Nobody on the first slot: the second streamer, if any, is the primary (as the relay promotes it).
                if second:
                    return self._send(200, {"ok": True, "channel": "second", "viewers": 1, "cap": 100, "slot": True, **second})
                return self._send(200, {"ok": True, "live": False, "viewers": 0, "cap": 100, "slot": True})
            out = {"ok": True, "channel": "main", "live": True, "session": s, "streamer": "Dev",
                   "playlistUrl": "http://127.0.0.1:%d/files/%s/live.m3u8" % (port, s),
                   "segmentSeconds": STATE["segment_seconds"], "viewers": 1, "cap": 100, "slot": True}
            if second:
                out["second"] = second
            return self._send(200, out)
        if path == "/report":
            return self._send(200, report())
        if path == "/ctl":
            m = re.search(r"(?:^|&)throttle=(\d+)", urlparse(self.path).query)
            if m:
                STATE["throttle_kbps"] = int(m.group(1))
                print("[stub] upload throttle now %d kbit/s" % STATE["throttle_kbps"], flush=True)
            return self._send(200, {"throttleKbps": STATE["throttle_kbps"]})
        if path == "/harness.html":
            return self._send(200, HARNESS.encode(), "text/html")
        if path.startswith("/public/") and ARGS.relay_public:
            rel = os.path.normpath(path[len("/public/"):])
            full = os.path.join(ARGS.relay_public, rel)
            if rel.startswith("..") or not os.path.isfile(full):
                return self._send(404)
            with open(full, "rb") as fh:
                return self._send(200, fh.read(), "application/javascript")
        parts = path.strip("/").split("/")
        if len(parts) == 3 and parts[0] in ("files", "main"):
            key = (parts[1], parts[2])
            now = time.time()
            with LOCK:
                stored = STATE["files"].get(key)
                if stored is None:
                    return self._send(404)
                data, headers = stored
                m = re.search(r"max-age=(\d+)", headers.get("Cache-Control", ""))
                max_age = int(m.group(1)) if m else 0
                # The edge: hold what was served for max-age, as Cloudflare would in front of R2.
                edge = STATE["edge"].get(key)
                if parts[2] == "live.m3u8" and edge and now - edge[0] < max_age:
                    data = edge[1]
                else:
                    STATE["edge"][key] = (now, data)
                STATE["gets"].append((now, parts[1], parts[2], self.headers.get("User-Agent", "")))
            return self._send(200, data, headers.get("Content-Type") or "application/octet-stream",
                              {"Cache-Control": headers.get("Cache-Control", "")})
        return self._send(404, {"error": "unknown_route"})


HARNESS = """<!doctype html><meta charset=utf-8><title>Live delay</title>
<body style="font:14px system-ui;background:#111;color:#eee"><video id=v muted autoplay playsinline style="width:640px"></video>
<pre id=o></pre>
<script src="/public/vendor/hls.light.min.js"></script><script src="/public/live-player.js"></script>
<script>
(async function () {
  const o = document.getElementById('o');
  const status = await (await fetch('/dev/live/status')).json();
  const seg = DtLivePlayer.segmentSecondsOf(status);
  const cfg = DtLivePlayer.hlsConfig(seg);
  const hls = new Hls(cfg);
  hls.loadSource(status.playlistUrl);
  hls.attachMedia(document.getElementById('v'));
  setInterval(() => {
    const d = hls.playingDate ? (Date.now() - hls.playingDate.getTime()) / 1000 : null;
    o.textContent = 'segment ' + seg + ' s, liveSyncDurationCount ' + cfg.liveSyncDurationCount + '\\ndelay ' + (d == null ? '-' : d.toFixed(2)) + ' s';
    if (d != null) fetch('/measure', { method: 'POST', body: JSON.stringify({ delayS: d }) });
  }, 1000);
})();
</script>"""


def main():
    global ARGS
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=8787)
    p.add_argument("--out", required=True)
    p.add_argument("--quick", action="store_true")
    p.add_argument("--quick-seconds", type=int, default=3)
    p.add_argument("--relay-public", default="")
    p.add_argument("--second-streamer", default="", help="Two Streams: a second player of this name is live on the second slot")
    p.add_argument("--throttle-kbps", type=int, default=0, help="cap PUT upload speed (kbit/s); /ctl?throttle=N changes it")
    ARGS = p.parse_args()
    STATE["throttle_kbps"] = max(0, ARGS.throttle_kbps)
    STATE["segment_seconds"] = ARGS.quick_seconds if ARGS.quick else 10
    STATE["list_size"] = max(6, -(-60 // STATE["segment_seconds"]))
    os.makedirs(ARGS.out, exist_ok=True)
    print("[stub] listening on 127.0.0.1:%d (%s)" % (ARGS.port, "quick" if ARGS.quick else "default"), flush=True)
    ThreadingHTTPServer(("127.0.0.1", ARGS.port), Handler).serve_forever()


ARGS = None
if __name__ == "__main__":
    main()
