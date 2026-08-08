#!/usr/bin/env python3
"""Compare the two arms produced by run-ab.sh.

Reads the `[lodperf]` line each arm emits (one per measurement window) plus the `[lod]` tier
readout, and prints a side-by-side table.

Deliberately refuses to report a comparison when an arm produced no measurement window, rather
than printing a table with a blank in it — a missing arm means the run did not happen, and that
should read as a failure, not as a result.
"""
import re
import sys
from pathlib import Path

LODPERF = re.compile(
    r"\[lodperf\] lod=(?P<lod>\w+) frames=(?P<frames>\d+) dropped=(?P<dropped>\d+) "
    r"windowMs=(?P<window>\d+) p50=(?P<p50>[\d.]+) p95=(?P<p95>[\d.]+) p99=(?P<p99>[\d.]+) "
    r"max=(?P<max>[\d.]+) meanFps=(?P<fps>[\d.]+) near=(?P<near>\d+) far=(?P<far>\d+) "
    r"baked=(?P<baked>\d+)"
)
LOD_STATE = re.compile(
    r"\[lod\] tracked=(?P<tracked>\d+) near=(?P<near>\d+) far=(?P<far>\d+) "
    r"candidates=(?P<cand>\d+) blocked=(?P<blocked>\d+) baked=(?P<baked>\d+)"
)


def parse_arm(path: Path):
    if not path.exists():
        return None
    windows, tiers = [], []
    for line in path.read_text(errors="replace").splitlines():
        m = LODPERF.search(line)
        if m:
            windows.append(m.groupdict())
        m2 = LOD_STATE.search(line)
        if m2:
            tiers.append(m2.groupdict())
    if not windows:
        return None
    # The last full window is the measurement one; earlier windows cover join/teleport.
    return {"window": windows[-1], "all_windows": windows, "tiers": tiers}


def fmt_row(label, off, on):
    return f"  {label:<22} {off:>12} {on:>12}"


def main() -> int:
    out_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "build/lodperf")
    off = parse_arm(out_dir / "client-off.log")
    on = parse_arm(out_dir / "client-on.log")

    missing = [n for n, a in (("off", off), ("on", on)) if a is None]
    if missing:
        print(f"INCOMPLETE: no [lodperf] measurement window for arm(s): {', '.join(missing)}")
        print("The run did not produce a comparable result. Check the client logs in", out_dir)
        return 1

    w_off, w_on = off["window"], on["window"]

    print("Far-carriage render LOD — A/B (client frametime, ms; lower is better)")
    print()
    print(f"  {'':<22} {'LOD OFF':>12} {'LOD ON':>12}")
    print(fmt_row("frames sampled", w_off["frames"], w_on["frames"]))
    print(fmt_row("window (ms)", w_off["window"], w_on["window"]))
    print(fmt_row("p50", w_off["p50"], w_on["p50"]))
    print(fmt_row("p95", w_off["p95"], w_on["p95"]))
    print(fmt_row("p99", w_off["p99"], w_on["p99"]))
    print(fmt_row("worst frame", w_off["max"], w_on["max"]))
    print(fmt_row("mean fps", w_off["fps"], w_on["fps"]))
    print()
    print(fmt_row("carriages near", w_off["near"], w_on["near"]))
    print(fmt_row("carriages far", w_off["far"], w_on["far"]))
    print(fmt_row("baked meshes", w_off["baked"], w_on["baked"]))

    for name, arm in (("off", off), ("on", on)):
        if int(arm["window"]["dropped"]) > 0:
            print(f"\n  WARNING: arm {name} dropped {arm['window']['dropped']} samples "
                  f"(ring overflow) — percentiles are biased toward the start of the window.")

    # Sanity gates. A result that passes the arithmetic but fails these is not a real measurement.
    print()
    if int(w_off["far"]) != 0 or int(w_off["baked"]) != 0:
        print("  INVALID: the OFF arm demoted carriages. The toggle did not take effect.")
        return 1
    if int(w_on["far"]) == 0:
        print("  INVALID: the ON arm demoted nothing, so the two arms rendered identically.")
        print("  Any difference below is noise, not the LOD.")
        return 1

    d50 = float(w_off["p50"]) - float(w_on["p50"])
    d99 = float(w_off["p99"]) - float(w_on["p99"])
    print(f"  Delta p50: {d50:+.2f} ms   Delta p99: {d99:+.2f} ms   "
          f"({w_on['far']} of {int(w_on['far']) + int(w_on['near'])} carriages baked)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
