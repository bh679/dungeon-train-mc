#!/usr/bin/env python3
"""Compare the two arms produced by run-ab.sh.

Computes true pooled percentiles over the raw per-frame dump, sliced to exactly the measurement
window each arm recorded. Deliberately does NOT use the sampler's own aggregate `[lodperf]` line
as the result: that window is anchored to the client's first rendered frame, so its boundaries do
not line up with the measurement period and can straddle the join and teleport.

Refuses to print a comparison when an arm is missing or when the two arms did not actually render
differently — a table with a hole in it, or one comparing two identical configurations, reads as a
result when it is not one.
"""
import re
import sys
from pathlib import Path

LOD_STATE = re.compile(
    r"\[lod\] tracked=(?P<tracked>\d+) near=(?P<near>\d+) far=(?P<far>\d+) "
    r"candidates=(?P<cand>\d+) blocked=(?P<blocked>\d+) baked=(?P<baked>\d+)"
)


def load_arm(out_dir: Path, arm: str):
    csv_path = out_dir / f"frames-{arm}.csv"
    win_path = out_dir / f"window-{arm}.txt"
    log_path = out_dir / f"client-{arm}.log"

    if not csv_path.exists():
        return None, f"no frame dump ({csv_path.name})"
    if not win_path.exists():
        return None, f"no measurement window ({win_path.name})"

    start_ms, end_ms = (int(x) for x in win_path.read_text().split())

    frames = []
    lod_flags = set()
    throttled_frames = 0
    with csv_path.open() as fh:
        next(fh, None)  # header
        for line in fh:
            parts = line.strip().split(",")
            if len(parts) != 4:
                continue
            try:
                ts, nanos, lod, throttled = (int(p) for p in parts)
            except ValueError:
                continue
            if start_ms <= ts <= end_ms:
                frames.append(nanos)
                lod_flags.add(lod)
                throttled_frames += throttled

    if not frames:
        return None, "no frames inside the measurement window"

    # Tier split observed during the window, from the [lod] readout.
    near = far = baked = 0
    if log_path.exists():
        for line in log_path.read_text(errors="replace").splitlines():
            m = LOD_STATE.search(line)
            if m:
                near, far, baked = int(m["near"]), int(m["far"]), int(m["baked"])

    frames.sort()
    return {
        "frames": frames,
        "n": len(frames),
        "window_s": (end_ms - start_ms) / 1000.0,
        "lod_flags": lod_flags,
        "throttled": throttled_frames,
        "near": near,
        "far": far,
        "baked": baked,
    }, None


def pct(sorted_vals, p):
    idx = max(0, min(len(sorted_vals) - 1, int(len(sorted_vals) * p / 100.0 + 0.5) - 1))
    return sorted_vals[idx] / 1e6


def row(label, a, b, fmt="{:.2f}"):
    return f"  {label:<20} {fmt.format(a):>12} {fmt.format(b):>12}"


def main() -> int:
    out_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "build/lodperf")
    off, off_err = load_arm(out_dir, "off")
    on, on_err = load_arm(out_dir, "on")

    if off is None or on is None:
        print("INCOMPLETE — no comparable result.")
        if off is None:
            print(f"  arm off: {off_err}")
        if on is None:
            print(f"  arm on:  {on_err}")
        print(f"  Logs in {out_dir}")
        return 1

    print("Far-carriage render LOD — A/B")
    print("Client frametime over the measurement hold, milliseconds (lower is better).")
    print()
    print(f"  {'':<20} {'LOD OFF':>12} {'LOD ON':>12}")
    print(row("frames sampled", off["n"], on["n"], "{:.0f}"))
    print(row("window (s)", off["window_s"], on["window_s"], "{:.1f}"))
    print(row("p50", pct(off["frames"], 50), pct(on["frames"], 50)))
    print(row("p95", pct(off["frames"], 95), pct(on["frames"], 95)))
    print(row("p99", pct(off["frames"], 99), pct(on["frames"], 99)))
    print(row("worst frame", off["frames"][-1] / 1e6, on["frames"][-1] / 1e6))
    print(row("mean fps", off["n"] / off["window_s"], on["n"] / on["window_s"], "{:.1f}"))
    print()
    print(row("carriages near", off["near"], on["near"], "{:.0f}"))
    print(row("carriages far", off["far"], on["far"], "{:.0f}"))
    print(row("baked meshes", off["baked"], on["baked"], "{:.0f}"))
    print()

    # Validity gates. These are the difference between a measurement and a coincidence.
    #
    # The throttle gate comes first because it is the one that produced a confident-looking wrong
    # answer: DT caps the client to 30fps when the window is unfocused, so an unattended run can
    # report a large "regression" that is entirely the cap, with both tails pinned to it.
    for name, arm in (("off", off), ("on", on)):
        if arm["throttled"] > 0:
            share = 100.0 * arm["throttled"] / arm["n"]
            print(f"  INVALID: arm {name} had {arm['throttled']} of {arm['n']} measured frames "
                  f"({share:.0f}%) capped by the idle framerate throttle.")
            print("  Frametimes are the cap, not the renderer. Disable the throttle for the run.")
            return 1

    if off["lod_flags"] != {0}:
        print("  INVALID: the OFF arm reported the LOD active. The launch toggle did not take.")
        return 1
    if on["lod_flags"] != {1}:
        print("  INVALID: the ON arm reported the LOD inactive. The launch toggle did not take.")
        return 1
    if on["far"] == 0:
        print("  INVALID: the ON arm never demoted a carriage, so both arms rendered identically.")
        print("  Any difference above is noise, not the LOD.")
        return 1

    d50 = pct(off["frames"], 50) - pct(on["frames"], 50)
    d95 = pct(off["frames"], 95) - pct(on["frames"], 95)
    d99 = pct(off["frames"], 99) - pct(on["frames"], 99)
    print(f"  Delta (off - on):  p50 {d50:+.2f} ms   p95 {d95:+.2f} ms   p99 {d99:+.2f} ms")
    print(f"  Positive = LOD is faster. {on['far']} of "
          f"{on['far'] + on['near']} carriages were baked in the ON arm.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
