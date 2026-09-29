#!/usr/bin/env python3
"""Unit tests for analyze.py's [mspt] parser.

The line has grown fields four times (physMs in 2026-09, the freeze attribution in 0.1003.1, the
collider batching in 0.1018, the GC/chunk/entity counters after it), and player logs of every
vintage are still being read. The parser keys by name so each vintage parses; these fixtures pin that.

Run: python3 -m pytest scripts/perf/test_analyze.py
"""
import analyze

PREFIX = "[29Sep2026 10:00:00.000] [Server thread/DEBUG] [games.brennan.dungeontrain.jitter/]: "

OLD = PREFIX + ("[mspt] dim=minecraft:overworld avgTickMs=31.40 carriages=12 near=4 trains=1 "
                "physMs=18.20 substeps=2 blockChanges=0")
FREEZE = OLD + (" activeTracked=4 activeEntity=0 activeSettling=0 frozen=8 maxBodyLag=3.5 "
                "reparks=1 reanchors=0")
BATCH = FREEZE + " colliderRebuilds=2 batchedBlockChanges=310 blockChangeMs=4.20"
CURRENT = BATCH + (" gcMs=42 gcN=3 heapUsedMb=2811 heapMaxMb=4096 chunkWaitMs=12.75 chunkWaits=5 "
                    "chunksLoaded=1893 pendingChunkTasks=7 entities=412 onCarriages=96 tickMaxMs=88.10")


def test_non_mspt_lines_are_ignored():
    assert analyze.parse_mspt(PREFIX + "[despawn] swept pIdx=3 entities=4") is None
    assert analyze.parse_mspt("") is None


def test_oldest_line_parses_without_new_fields():
    m = analyze.parse_mspt(OLD)
    assert m["dim"] == "minecraft:overworld"
    assert m["avgTickMs"] == 31.40
    assert m["carriages"] == 12 and m["near"] == 4
    assert "gcMs" not in m and "frozen" not in m


def test_freeze_fields_line():
    m = analyze.parse_mspt(FREEZE)
    assert m["frozen"] == 8
    assert m["maxBodyLag"] == 3.5
    assert "tickMaxMs" not in m


def test_current_line_carries_every_load_field():
    m = analyze.parse_mspt(CURRENT)
    for k in analyze.LOAD_FIELDS:
        assert k in m, k
    assert m["gcMs"] == 42 and m["gcN"] == 3
    assert m["heapMaxMb"] == 4096
    assert m["chunkWaitMs"] == 12.75 and m["chunkWaits"] == 5
    assert m["pendingChunkTasks"] == 7
    assert m["entities"] == 412 and m["onCarriages"] == 96
    assert m["tickMaxMs"] == 88.10
    # Earlier fields keep their values — the new ones are appended, not interleaved.
    assert m["avgTickMs"] == 31.40 and m["reanchors"] == 0
    assert m["batchedBlockChanges"] == 310 and m["blockChangeMs"] == 4.20


def test_prefix_text_is_not_mistaken_for_fields():
    m = analyze.parse_mspt("thread=Server foo=1 " + CURRENT)
    assert "thread" not in m and "foo" not in m
