#!/usr/bin/env python3
"""Compare the two arms of a Dungeon Train server-tick A/B from the servers' own [mspt] lines.

Usage: analyze.py [outDir]

Reads <arm>.server.log and <arm>.mark (the line number where the measured window opened) for
A_off and B_on, then reports avgTickMs alongside the controls that say whether the comparison is
usable at all. The validity gates are not decoration — three separate runs during development
produced confident-looking deltas that were entirely artifacts.
"""
import re
import statistics as st
import sys
from pathlib import Path

OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).parent / "out"

MSPT = re.compile(r"\[mspt\].*avgTickMs=([\d.]+) carriages=(\d+) near=(\d+)")
DESP = re.compile(r"\[despawn\] dim=.*snapshotted=(\d+) entitiesHeld=(\d+)")
SWEEP = re.compile(r"\[despawn\] (swept|restored) pIdx=(-?\d+) entities=(\d+)")
TELE = re.compile(r"Teleported \w+ to ([\d.]+)")

MIN_SAMPLES = 40      # a 120s window logs ~60; far fewer means the train left and [mspt] stopped
MAX_DRIFT = 40.0      # blocks; a player riding the train drifts ~240 over the window


def load(arm):
    log = OUT / f"{arm}.server.log"
    mark_file = OUT / f"{arm}.mark"
    if not log.exists() or not mark_file.exists():
        return None
    text = log.read_text(errors="replace")
    mark = int(mark_file.read_text().strip())
    window = text.splitlines()[mark - 1:]

    mspt, carr, near, held = [], [], [], []
    sweeps = restores = 0
    for ln in window:
        m = MSPT.search(ln)
        if m:
            mspt.append(float(m.group(1)))
            carr.append(int(m.group(2)))
            near.append(int(m.group(3)))
        d = DESP.search(ln)
        if d:
            held.append(int(d.group(2)))
        s = SWEEP.search(ln)
        if s:
            if s.group(1) == "swept":
                sweeps += 1
            else:
                restores += 1

    probes = [float(x) for x in TELE.findall(text)]
    drift = probes[-1] - probes[-2] if len(probes) >= 2 else float("nan")
    return dict(arm=arm, n=len(mspt), mspt=mspt, carr=carr, near=near, held=held,
                sweeps=sweeps, restores=restores, drift=drift)


def pct(v, p):
    if not v:
        return float("nan")
    v = sorted(v)
    k = (len(v) - 1) * p / 100.0
    lo, hi = int(k), min(int(k) + 1, len(v) - 1)
    return v[lo] + (v[hi] - v[lo]) * (k - lo)


def verdict(r):
    """Why an arm can be unusable, in the order the failures actually showed up in practice."""
    if r["n"] == 0:
        return False, "NO SAMPLES — the window captured nothing"
    if r["drift"] >= MAX_DRIFT:
        return False, f"STILL RIDING ({r['drift']:.0f} blocks) — the tp-off-train step did not take"
    if r["n"] < MIN_SAMPLES:
        return False, f"ONLY {r['n']} SAMPLES — the train left the player mid-window"
    return True, "valid"


def show(r):
    print(f"{r['arm']}:")
    m = r["mspt"]
    if not m:
        print("  NO SAMPLES\n")
        return
    print(f"  samples        {r['n']}")
    print(f"  avgTickMs mean {st.mean(m):7.2f}   median {st.median(m):7.2f}")
    print(f"            p95  {pct(m, 95):7.2f}   min    {min(m):7.2f}   max {max(m):7.2f}")
    # Spread is the headline for the perf-test ENVIRONMENT itself: on a noise-terrain world a
    # single window spanned 13-73ms, which is what made small deltas unreadable. Flat should be tight.
    print(f"            spread {max(m) - min(m):6.2f}  (tight spread == a usable environment)")
    print(f"  carriages      mean {st.mean(r['carr']):.1f}  range {min(r['carr'])}-{max(r['carr'])}")
    print(f"  near           mean {st.mean(r['near']):.1f}")
    if r["held"]:
        print(f"  entitiesHeld   mean {st.mean(r['held']):.1f}  max {max(r['held'])}")
    print(f"  sweeps {r['sweeps']}  restores {r['restores']}")
    ok, why = verdict(r)
    print(f"  drift {r['drift']:.1f} blocks -> {why}")
    print()


a, b = load("A_off"), load("B_on")
if a is None or b is None:
    print("missing logs — expected A_off/B_on .server.log and .mark in", OUT)
    raise SystemExit(1)

show(a)
show(b)

ok_a, _ = verdict(a)
ok_b, _ = verdict(b)
if not (ok_a and ok_b):
    print("ONE OR BOTH ARMS INVALID — do not read the delta below as a result.")

ma, mb = st.mean(a["mspt"]), st.mean(b["mspt"])
ca, cb = st.mean(a["carr"]), st.mean(b["carr"])
print(f"delta mean avgTickMs  {mb - ma:+.2f} ms  ({(mb - ma) / ma * 100:+.1f}%)")
print(f"delta p95             {pct(b['mspt'], 95) - pct(a['mspt'], 95):+.2f} ms")
print(f"carriage control      A={ca:.1f} B={cb:.1f}  ({abs(cb - ca) / ca * 100:.1f}% apart)")
if abs(cb - ca) / ca > 0.05:
    print("  !! >5% apart — the arms are not load-matched; the delta is not attributable")
print(f"per-carriage ms       A={ma / ca:.3f}  B={mb / cb:.3f}  ({(mb / cb - ma / ca) / (ma / ca) * 100:+.1f}%)")

# Sanity floor: if the ON arm never held anything, there was nothing for the feature to do and a
# near-zero delta says nothing about whether it helps.
if b["held"] and max(b["held"]) < 5:
    print(f"\nNOTE: arm B held at most {max(b['held'])} entities. The feature was barely loaded —")
    print("a small delta here is not evidence either way. Populate harder before concluding.")
