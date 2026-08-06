#!/usr/bin/env python3
"""Unit tests for parse-dt-perf-log.py — the server-perf A/B log parser.

The log-line fixtures below are copied verbatim from a real dev-client log
(`run/logs/debug-3.log.gz`, 2026-07-14) with only the numbers changed, so a
format drift in the mod's DEBUG lines fails here rather than silently producing
an empty analysis.

Run: python3 scripts/perf/test_parse_dt_perf_log.py   (or via pytest)
"""
import importlib.util
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "parse-dt-perf-log.py")


def _load_module():
    """Import the script despite its hyphenated (non-importable) filename."""
    spec = importlib.util.spec_from_file_location("parse_dt_perf_log", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    # Register before exec: @dataclass resolves annotations via sys.modules, and
    # an unregistered module makes that lookup fail.
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


P = _load_module()

PREFIX = "[14July2026 19:{:02d}:{:02d}.000] [Server thread/DEBUG] [games.brennan.dungeontrain.jitter/]: "
INFO_PREFIX = ("[14July2026 19:01:54.967] [Server thread/INFO] "
               "[games.brennan.dungeontrain.train.TrainAssembler/]: [DungeonTrain] ")


def spawning_train(group_size, train="66974f37-2537-4afe-a892-5e58a927f55d"):
    return (f"{INFO_PREFIX}Spawning train trainId={train} seedAnchor=0 "
            f"(initialPIdx=0, targetCount=36) groupSize={group_size} halfPadLen=5 "
            f"subLevelStride=37 dims=9x7x7 velocity=( 0.000e0  0.000e0  0.000e0) "
            f"origin=BlockPos{{x=0, y=78, z=0}} — appender will extend\n")


def spawned_group(group_size, total=57, clear=0, place=10, assemble=16, contents=30):
    return (f"{INFO_PREFIX}Spawned group anchorPIdx=3 groupSize={group_size} "
            f"enclosed=[fancywood,cracked,windowed] pads=back+front "
            f"trainId=66974f37 ship id=-7097608349232050428 "
            f"shipyardOrigin=BlockPos{{x=20481014, y=173, z=20483077}} blocks=840 "
            f"timing(ms): clear={clear} place={place} assemble={assemble} "
            f"contents={contents} total={total}\n")


def mspt(minute, second, ms, sub_levels):
    return (PREFIX.format(minute, second) + f"[mspt] dim=minecraft:overworld "
            f"avgTickMs={ms} carriages={sub_levels} near=3 trains=1\n")


def freeze(minute, second, resident, active, frozen):
    return (PREFIX.format(minute, second) + f"[freeze] dim=minecraft:overworld "
            f"resident={resident} active={active} frozen={frozen}\n")


def gen_timing(minute, second, chunks=4):
    return (PREFIX.format(minute, second) + f"[gen.timing] dim=minecraft:overworld "
            f"chunksFulled={chunks} dtGenMs=12.00 perChunkDtMs=3.000 | totals df=1.00\n")


def arm(group_size, count, ms, sub_levels, start_second=0, gen_windows=0):
    """One segment: a spawn, its group placements, then `count` sample windows.

    Companion lines are emitted BEFORE the [mspt] they belong to, matching the
    handler's fixed order in TrainTickEvents (gen.timing :259, freeze :292,
    mspt :323). Getting this backwards silently drops every join.
    """
    lines = [spawning_train(group_size)]
    lines += [spawned_group(group_size) for _ in range(36 // group_size)]
    for i in range(count):
        second = start_second + i
        if i < gen_windows:
            lines.append(gen_timing(2, second))
        lines.append(freeze(2, second, sub_levels, 4, sub_levels - 4))
        lines.append(mspt(2, second, ms, sub_levels))
    return lines


def test_segments_split_on_spawn_and_label_by_group_size():
    segments = P.parse(arm(3, 5, 14.0, 12) + arm(6, 5, 11.0, 6))
    assert len(segments) == 2, f"expected 2 segments, got {len(segments)}"
    assert [s.group_size for s in segments] == [3, 6], [s.group_size for s in segments]
    assert all(len(s.windows) == 5 for s in segments), [len(s.windows) for s in segments]


def test_lines_before_first_spawn_are_discarded():
    noise = [mspt(2, 0, 99.0, 3), freeze(2, 0, 3, 3, 0)]
    segments = P.parse(noise + arm(3, 4, 14.0, 12))
    assert len(segments) == 1, f"expected 1 segment, got {len(segments)}"
    assert len(segments[0].windows) == 4, len(segments[0].windows)


def test_freeze_and_gen_join_onto_same_timestamp_window():
    segments = P.parse(arm(3, 3, 14.0, 12, gen_windows=1))
    windows = segments[0].windows
    assert windows[0].active == 4, windows[0].active
    assert windows[0].frozen == 8, windows[0].frozen
    assert windows[0].gen_active, "window with a [gen.timing] line should read gen_active"
    assert not windows[1].gen_active, "window without [gen.timing] should be gen-quiet"


def test_companions_join_even_when_timestamps_skew():
    """Regression: the three lines can straddle a millisecond, so they must not
    be paired on the timestamp (seen live: freeze .331 vs mspt .332)."""
    lines = [
        spawning_train(3),
        (PREFIX.format(2, 9).replace(".000]", ".331]")
         + "[freeze] dim=minecraft:overworld resident=16 active=13 frozen=3\n"),
        (PREFIX.format(2, 9).replace(".000]", ".332]")
         + "[mspt] dim=minecraft:overworld avgTickMs=38.12 carriages=16 near=4 trains=1\n"),
    ]
    window = P.parse(lines)[0].windows[0]
    assert window.active == 13, f"freeze did not join across the ms skew: {window.active}"
    assert window.frozen == 3, window.frozen


def test_companion_lines_do_not_leak_across_a_respawn():
    """A freeze with no following [mspt] must not attach to the next arm's first
    window — otherwise arm B inherits arm A's body counts."""
    lines = (arm(3, 2, 14.0, 12)
             + [freeze(2, 30, 12, 4, 8)]   # trailing, no [mspt] follows
             + arm(6, 2, 11.0, 6))
    segments = P.parse(lines)
    first_b_window = segments[1].windows[0]
    assert first_b_window.active == 4, first_b_window.active
    assert first_b_window.frozen == 2, first_b_window.frozen


def test_retain_drops_warmup_and_gen_active_windows():
    segments = P.parse(arm(3, 10, 14.0, 12, gen_windows=4))
    # warmup=2 drops the first two (two of which are gen-active); of the
    # remaining eight, two are still gen-active and get dropped as well.
    kept = P.retain(segments, warmup=2, include_gen=False)
    assert len(kept) == 6, f"expected 6 retained, got {len(kept)}"
    kept_with_gen = P.retain(segments, warmup=2, include_gen=True)
    assert len(kept_with_gen) == 8, f"expected 8 retained, got {len(kept_with_gen)}"


def test_paired_deltas_pairs_each_test_segment_with_the_base_before_it():
    lines = (arm(3, 4, 14.0, 12) + arm(6, 4, 11.0, 6)
             + arm(3, 4, 15.0, 12) + arm(6, 4, 11.5, 6))
    segments = P.parse(lines)
    kept = P.retain(segments, warmup=0, include_gen=False)
    deltas = P.paired_deltas(3, 6, segments, kept)
    assert deltas == [-3.0, -3.5], deltas


def test_percentile_is_nearest_rank_and_survives_empty_input():
    assert P.percentile([1.0, 2.0, 3.0, 4.0, 5.0], 0.0) == 1.0
    assert P.percentile([1.0, 2.0, 3.0, 4.0, 5.0], 1.0) == 5.0
    assert P.percentile([1.0, 2.0, 3.0, 4.0, 5.0], 0.5) == 3.0
    assert P.percentile([], 0.5) != P.percentile([], 0.5), "empty input should be NaN"


def test_spawn_costs_are_captured_per_arm():
    segments = P.parse(arm(3, 2, 14.0, 12) + arm(6, 2, 11.0, 6))
    assert len(segments[0].spawns) == 12, len(segments[0].spawns)
    assert len(segments[1].spawns) == 6, len(segments[1].spawns)
    assert segments[0].spawns[0].total == 57, segments[0].spawns[0].total


def test_cli_reports_the_ab_delta():
    lines = arm(3, 15, 14.0, 12) + arm(6, 15, 11.0, 6)
    with tempfile.NamedTemporaryFile("w", suffix=".log", delete=False) as handle:
        handle.writelines(lines)
        path = handle.name
    try:
        result = subprocess.run([sys.executable, SCRIPT, path],
                                capture_output=True, text=True)
        assert result.returncode == 0, f"exit {result.returncode}: {result.stderr}"
        assert "A/B: groupSize 3 -> 6" in result.stdout, result.stdout
        assert "-3.00 ms" in result.stdout, result.stdout
        assert "Spawn cost per group" in result.stdout, result.stdout
    finally:
        os.unlink(path)


def test_cli_fails_cleanly_on_a_log_with_no_train():
    with tempfile.NamedTemporaryFile("w", suffix=".log", delete=False) as handle:
        handle.write("[14July2026 19:00:00.000] [main/INFO] [mod/]: nothing to see\n")
        path = handle.name
    try:
        result = subprocess.run([sys.executable, SCRIPT, path],
                                capture_output=True, text=True)
        assert result.returncode == 1, f"expected exit 1, got {result.returncode}"
        assert "no `Spawning train` line" in result.stderr, result.stderr
    finally:
        os.unlink(path)


def _main():
    funcs = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    failures = 0
    for fn in funcs:
        try:
            fn()
            print(f"ok   {fn.__name__}")
        except AssertionError as exc:
            failures += 1
            print(f"FAIL {fn.__name__}: {exc}")
    print(f"\n{len(funcs) - failures}/{len(funcs)} passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(_main())
