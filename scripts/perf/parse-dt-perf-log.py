#!/usr/bin/env python3
"""Parse a Dungeon Train dev log into per-arm server-performance segments.

Built for the sub-level group-packing A/B (does packing more carriages per Sable
sub-level buy us tick time?), but it is generic over any experiment whose arms are
separated by a `/dungeontrain spawn` — the train's `groupSize` at each respawn is
what labels the arm, so no manual bookkeeping is needed while running the test.

Reads the DEBUG lines the mod already ships (run protocol and worked example in
`docs/perf/sublevel-group-packing-2026-08.md`):

  [mspt] dim=... avgTickMs=... carriages=... near=... trains=...
  [freeze] dim=... resident=... active=... frozen=...
  [gen.timing] dim=... chunksFulled=... ...
  [DungeonTrain] Spawning train ... groupSize=N ...
  [DungeonTrain] Spawned group ... groupSize=N ... timing(ms): ... total=N

Note `[mspt] carriages=` counts SUB-LEVELS (one per carriage group), not visual
carriages — it is `Trains.Carriage` entries, one per `spawnGroup`. This script
reports that column as `subLevels` to avoid the log's misnomer leaking into the
analysis.

Two filters keep the sample honest, both on by default:
  * the first `--warmup` windows after each respawn are dropped, because
    `avgTickMs` comes from `getAverageTickTimeNanos()` — a 100-tick rolling mean
    that lags a step change by roughly five windows;
  * windows carrying a `[gen.timing]` line are dropped, because that line only
    fires when chunks reached FULL in the window — i.e. chunk generation was
    running, which dwarfs the physics signal we are trying to read.

Usage:
  python3 scripts/perf/parse-dt-perf-log.py run/logs/latest.log
  python3 scripts/perf/parse-dt-perf-log.py run/logs/*.log.gz --csv windows.csv
  python3 scripts/perf/parse-dt-perf-log.py latest.log --warmup 5 --include-gen

Read-only. Exits 1 when no usable window survived the filters.
"""
from __future__ import annotations

import argparse
import csv
import gzip
import re
import statistics
import sys
from dataclasses import dataclass, field
from pathlib import Path

# --- log line grammar -------------------------------------------------------
# The timestamp is the leading bracket group, e.g. "[14July2026 19:02:03.134]".
# It is only used to pair the [mspt] / [freeze] / [gen.timing] lines emitted on
# the same tick, so it is treated as an opaque key — never parsed as a date.
TS_RE = re.compile(r"^\[([^\]]+)\]")

MSPT_RE = re.compile(
    r"\[mspt\] dim=(?P<dim>\S+) avgTickMs=(?P<mspt>[-\d.]+) "
    r"carriages=(?P<sublevels>\d+) near=(?P<near>\d+) trains=(?P<trains>\d+)"
)
FREEZE_RE = re.compile(
    r"\[freeze\] dim=\S+ resident=(?P<resident>\d+) "
    r"active=(?P<active>\d+) frozen=(?P<frozen>\d+)"
)
GEN_RE = re.compile(r"\[gen\.timing\] dim=\S+ chunksFulled=(?P<chunks>\d+)")
SPAWNING_TRAIN_RE = re.compile(
    r"Spawning train trainId=(?P<train>\S+) .*?groupSize=(?P<group>\d+)"
)
SPAWNED_GROUP_RE = re.compile(
    r"Spawned group anchorPIdx=(?P<anchor>-?\d+) groupSize=(?P<group>\d+) .*?"
    r"timing\(ms\): clear=(?P<clear>\d+) place=(?P<place>\d+) "
    r"assemble=(?P<assemble>\d+) contents=(?P<contents>\d+) total=(?P<total>\d+)"
)

DEFAULT_WARMUP_WINDOWS = 10
MIN_WINDOWS_FOR_STATS = 5


@dataclass(frozen=True)
class Window:
    """One 40-tick `[mspt]` sample, enriched with same-tick freeze/gen lines."""

    segment: int
    group_size: int
    ts: str
    mspt: float
    sub_levels: int
    near: int
    active: int | None = None
    frozen: int | None = None
    gen_chunks: int = 0

    @property
    def gen_active(self) -> bool:
        return self.gen_chunks > 0

    @property
    def carriages(self) -> int:
        """Visual carriages aboard = sub-levels × group size.

        The arms do NOT carry equal carriage counts even with the window pinned:
        the appender rounds outward to group boundaries around the player, so a
        bigger group overshoots further. Comparing raw MSPT between arms would
        credit or penalise that difference as if it were the effect under test.
        """
        return self.sub_levels * self.group_size


@dataclass(frozen=True)
class SpawnCost:
    """One `Spawned group` placement, in ms."""

    segment: int
    group_size: int
    clear: int
    place: int
    assemble: int
    contents: int
    total: int


@dataclass
class Segment:
    """Everything logged between one `/dungeontrain spawn` and the next."""

    index: int
    group_size: int
    train_id: str
    windows: list[Window] = field(default_factory=list)
    spawns: list[SpawnCost] = field(default_factory=list)


def read_lines(path: Path) -> list[str]:
    """Read a plain or gzipped log. Undecodable bytes are replaced, not fatal —
    Minecraft logs routinely carry mod output in mixed encodings."""
    opener = gzip.open if path.suffix == ".gz" else open
    try:
        with opener(path, "rt", encoding="utf-8", errors="replace") as handle:
            return handle.readlines()
    except OSError as exc:
        raise SystemExit(f"cannot read {path}: {exc}") from exc


def parse(lines: list[str]) -> list[Segment]:
    """Split a log into segments, one per `/dungeontrain spawn`.

    Lines before the first spawn are discarded: there is no arm label for them,
    and they are world-load noise rather than a measured condition.
    """
    segments: list[Segment] = []
    current: Segment | None = None
    # All three lines are emitted from the same handler in a fixed order —
    # [gen.timing] (TrainTickEvents:259), then [freeze] (:292), then [mspt]
    # (:323) — so a window's companions always PRECEDE it. Buffer them and
    # consume on the next [mspt]. Pairing on the timestamp instead would be
    # wrong: the three lines can straddle a millisecond boundary.
    pending: dict = {}

    for line in lines:
        spawning = SPAWNING_TRAIN_RE.search(line)
        if spawning:
            current = Segment(
                index=len(segments) + 1,
                group_size=int(spawning.group("group")),
                train_id=spawning.group("train"),
            )
            segments.append(current)
            pending = {}
            continue

        if current is None:
            continue

        mspt = MSPT_RE.search(line)
        if mspt:
            ts_match = TS_RE.match(line)
            current.windows.append(Window(
                segment=current.index,
                group_size=current.group_size,
                ts=ts_match.group(1) if ts_match else "",
                mspt=float(mspt.group("mspt")),
                sub_levels=int(mspt.group("sublevels")),
                near=int(mspt.group("near")),
                **pending,
            ))
            pending = {}
            continue

        freeze = FREEZE_RE.search(line)
        if freeze:
            pending["active"] = int(freeze.group("active"))
            pending["frozen"] = int(freeze.group("frozen"))
            continue

        gen = GEN_RE.search(line)
        if gen:
            pending["gen_chunks"] = int(gen.group("chunks"))
            continue

        spawned = SPAWNED_GROUP_RE.search(line)
        if spawned:
            current.spawns.append(SpawnCost(
                segment=current.index,
                group_size=int(spawned.group("group")),
                clear=int(spawned.group("clear")),
                place=int(spawned.group("place")),
                assemble=int(spawned.group("assemble")),
                contents=int(spawned.group("contents")),
                total=int(spawned.group("total")),
            ))

    return segments


def settled_sub_levels(segment: Segment) -> int | None:
    """The sub-level count this segment spent most of its windows at.

    A respawned train fills over tens of seconds, so a segment's early windows
    sit at a smaller body count than its steady state. Comparing arms means
    comparing settled trains, so the modal count defines "settled" and anything
    else is fill phase.
    """
    counts = [w.sub_levels for w in segment.windows]
    return statistics.mode(counts) if counts else None


def retain(segments: list[Segment], warmup: int, include_gen: bool,
           stable_only: bool = True) -> list[Window]:
    """Apply the warmup, gen-quiet and settled-count filters."""
    kept: list[Window] = []
    for segment in segments:
        settled = settled_sub_levels(segment) if stable_only else None
        for window in segment.windows[warmup:]:
            if window.gen_active and not include_gen:
                continue
            if settled is not None and window.sub_levels != settled:
                continue
            kept.append(window)
    return kept


def percentile(values: list[float], fraction: float) -> float:
    """Nearest-rank percentile. Small samples make interpolation false precision."""
    if not values:
        return float("nan")
    ordered = sorted(values)
    rank = max(0, min(len(ordered) - 1, round(fraction * (len(ordered) - 1))))
    return ordered[rank]


def median_or_nan(values: list[float]) -> float:
    return statistics.median(values) if values else float("nan")


def report_segments(segments: list[Segment], kept: list[Window]) -> None:
    print("=== Segments (chronological; one per /dungeontrain spawn) ===")
    print(f"{'#':>3} {'arm':>5} {'kept':>5} {'subLvl':>7} {'MSPT med':>9} "
          f"{'p15':>7} {'p85':>7} {'active':>7} {'frozen':>7}")
    for segment in segments:
        windows = [w for w in kept if w.segment == segment.index]
        if not windows:
            print(f"{segment.index:>3} {segment.group_size:>5} {0:>5}   (no window survived the filters)")
            continue
        msp = [w.mspt for w in windows]
        active = [w.active for w in windows if w.active is not None]
        frozen = [w.frozen for w in windows if w.frozen is not None]
        flag = "" if len(windows) >= MIN_WINDOWS_FOR_STATS else "  ⚠ thin sample"
        print(f"{segment.index:>3} {segment.group_size:>5} {len(windows):>5} "
              f"{median_or_nan([w.sub_levels for w in windows]):>7.0f} "
              f"{median_or_nan(msp):>9.2f} {percentile(msp, 0.15):>7.2f} "
              f"{percentile(msp, 0.85):>7.2f} "
              f"{median_or_nan(active):>7.1f} {median_or_nan(frozen):>7.1f}{flag}")
    print()


def report_arms(kept: list[Window]) -> dict[int, list[Window]]:
    """Pool windows by arm (groupSize) and print the aggregate table."""
    arms: dict[int, list[Window]] = {}
    for window in kept:
        arms.setdefault(window.group_size, []).append(window)

    print("=== Arm aggregate (pooled windows) ===")
    print(f"{'arm':>5} {'segs':>5} {'windows':>8} {'subLvl':>7} {'carr':>6} {'MSPT med':>9} "
          f"{'p15':>7} {'p85':>7} {'ms/carr':>8} {'ms/subLvl':>10} {'active':>7}")
    for arm in sorted(arms):
        windows = arms[arm]
        msp = [w.mspt for w in windows]
        active = [w.active for w in windows if w.active is not None]
        med = median_or_nan(msp)
        sub_levels = median_or_nan([w.sub_levels for w in windows])
        carriages = median_or_nan([w.carriages for w in windows])
        print(f"{arm:>5} {len({w.segment for w in windows}):>5} {len(windows):>8} "
              f"{sub_levels:>7.0f} {carriages:>6.0f} "
              f"{med:>9.2f} {percentile(msp, 0.15):>7.2f} {percentile(msp, 0.85):>7.2f} "
              f"{med / carriages:>8.3f} {med / sub_levels:>10.3f} "
              f"{median_or_nan(active):>7.1f}")
    print()
    return arms


def report_ab(arms: dict[int, list[Window]], segments: list[Segment], kept: list[Window]) -> None:
    """Print the A/B delta between the two arms, plus the per-rep paired deltas.

    Pairing matters more than the pooled medians: consecutive segments share
    terrain and JIT state, so a paired delta cancels drift the pooled figure
    cannot.
    """
    if len(arms) != 2:
        print(f"=== A/B skipped: found {len(arms)} arm(s), need exactly 2 ===\n")
        return

    base_arm, test_arm = sorted(arms)
    base = median_or_nan([w.mspt for w in arms[base_arm]])
    test = median_or_nan([w.mspt for w in arms[test_arm]])
    base_lv = median_or_nan([w.sub_levels for w in arms[base_arm]])
    test_lv = median_or_nan([w.sub_levels for w in arms[test_arm]])

    base_carr = median_or_nan([w.carriages for w in arms[base_arm]])
    test_carr = median_or_nan([w.carriages for w in arms[test_arm]])

    print(f"=== A/B: groupSize {base_arm} -> {test_arm} ===")
    print(f"sub-levels    {base_lv:.0f} -> {test_lv:.0f}"
          f"   ({pct(base_lv, test_lv)})")
    print(f"carriages     {base_carr:.0f} -> {test_carr:.0f}"
          f"   ({pct(base_carr, test_carr)})"
          f"{'   ⚠ arms not load-matched — read ms/carriage' if abs(test_carr - base_carr) > 0.02 * base_carr else ''}")
    print(f"median MSPT   {base:.2f} -> {test:.2f} ms"
          f"   ({test - base:+.2f} ms, {pct(base, test)})")
    print(f"ms/carriage   {base / base_carr:.3f} -> {test / test_carr:.3f}"
          f"   ({pct(base / base_carr, test / test_carr)})")

    paired = paired_deltas(base_arm, test_arm, segments, kept)
    if paired:
        rendered = ", ".join(f"{d:+.2f}" for d in paired)
        print(f"paired by rep {rendered} ms   (median {median_or_nan(paired):+.2f} ms)")
    print()


def paired_deltas(base_arm: int, test_arm: int, segments: list[Segment],
                  kept: list[Window]) -> list[float]:
    """Median MSPT of each test-arm segment minus the base-arm segment before it."""
    medians: dict[int, float] = {}
    for segment in segments:
        windows = [w.mspt for w in kept if w.segment == segment.index]
        if windows:
            medians[segment.index] = statistics.median(windows)

    deltas: list[float] = []
    previous_base: float | None = None
    for segment in segments:
        if segment.index not in medians:
            continue
        if segment.group_size == base_arm:
            previous_base = medians[segment.index]
        elif segment.group_size == test_arm and previous_base is not None:
            deltas.append(medians[segment.index] - previous_base)
            previous_base = None
    return deltas


def pct(before: float, after: float) -> str:
    if not before:
        return "n/a"
    return f"{(after - before) / before * 100:+.1f}%"


def report_spawn_cost(segments: list[Segment]) -> None:
    """Per-group placement cost by arm — the main cost risk of bigger groups.

    A bigger group SHOULD cost proportionally more per spawn; what matters is
    whether the per-carriage figure holds, and how big the single-tick spike gets.
    """
    by_arm: dict[int, list[SpawnCost]] = {}
    for segment in segments:
        for spawn in segment.spawns:
            by_arm.setdefault(spawn.group_size, []).append(spawn)
    if not by_arm:
        return

    print("=== Spawn cost per group (ms) ===")
    print(f"{'arm':>5} {'groups':>7} {'total med':>10} {'per carriage':>13} "
          f"{'clear':>7} {'place':>7} {'assemble':>9} {'contents':>9} {'total p95':>10}")
    for arm in sorted(by_arm):
        spawns = by_arm[arm]
        totals = [float(s.total) for s in spawns]
        total_med = median_or_nan(totals)
        print(f"{arm:>5} {len(spawns):>7} {total_med:>10.1f} {total_med / arm:>13.1f} "
              f"{median_or_nan([float(s.clear) for s in spawns]):>7.1f} "
              f"{median_or_nan([float(s.place) for s in spawns]):>7.1f} "
              f"{median_or_nan([float(s.assemble) for s in spawns]):>9.1f} "
              f"{median_or_nan([float(s.contents) for s in spawns]):>9.1f} "
              f"{percentile(totals, 0.95):>10.1f}")
    print()


def write_csv(path: Path, windows: list[Window]) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["segment", "groupSize", "ts", "avgTickMs", "subLevels",
                         "near", "active", "frozen", "genChunks"])
        for w in windows:
            writer.writerow([w.segment, w.group_size, w.ts, f"{w.mspt:.2f}",
                             w.sub_levels, w.near, w.active, w.frozen, w.gen_chunks])
    print(f"wrote {len(windows)} windows to {path}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("logs", nargs="+", type=Path, help="log files (.log or .log.gz)")
    parser.add_argument("--warmup", type=int, default=DEFAULT_WARMUP_WINDOWS,
                        help="windows to discard after each respawn "
                             f"(default {DEFAULT_WARMUP_WINDOWS}; avgTickMs is a 100-tick rolling mean)")
    parser.add_argument("--include-gen", action="store_true",
                        help="keep windows where chunks generated (default: drop — gen swamps the signal)")
    parser.add_argument("--include-filling", action="store_true",
                        help="keep windows from the post-respawn fill phase (default: drop — only "
                             "windows at the segment's settled sub-level count are comparable)")
    parser.add_argument("--csv", type=Path, help="write every retained window to this CSV")
    args = parser.parse_args(argv)

    lines: list[str] = []
    for path in args.logs:
        lines.extend(read_lines(path))

    segments = parse(lines)
    if not segments:
        print("no `Spawning train` line found — nothing to segment. "
              "Was the log captured from a dev build with the jitter logger at DEBUG?",
              file=sys.stderr)
        return 1

    kept = retain(segments, args.warmup, args.include_gen, not args.include_filling)
    dropped = sum(len(s.windows) for s in segments) - len(kept)
    print(f"{len(segments)} segment(s), {len(kept)} window(s) retained, {dropped} dropped "
          f"(warmup={args.warmup}, gen-quiet={'off' if args.include_gen else 'on'}, "
          f"settled-only={'off' if args.include_filling else 'on'})\n")

    if not kept:
        print("every window was filtered out — lower --warmup, or pass --include-gen / "
              "--include-filling if the run was never quiet or never settled.", file=sys.stderr)
        return 1

    report_segments(segments, kept)
    arms = report_arms(kept)
    report_ab(arms, segments, kept)
    report_spawn_cost(segments)

    if args.csv:
        write_csv(args.csv, kept)
    return 0


if __name__ == "__main__":
    sys.exit(main())
