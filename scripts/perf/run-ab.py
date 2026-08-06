#!/usr/bin/env python3
"""Drive the sub-level group-packing A/B on a dev dedicated server, over RCON.

Answers: does packing more carriages into each Sable sub-level buy server tick
time? `groupSize` is a live config knob, so flipping it and respawning the train
is a zero-code proxy for "N groups per sub-level" — this script alternates the
arms on a timer so the log can be parsed into a paired A/B.

Design notes that matter:

  * **Alternating, not blocked.** Consecutive segments share terrain and JIT
    state, so a paired delta cancels drift that pooled medians cannot.
  * **Parked train.** `speed 0` stops forward chunk generation, which otherwise
    swamps the physics signal it is trying to measure.
  * **Pinned carriage window.** `carriages <n>` stops the window auto-sizing off
    render distance; pick an `n` divisible by every arm so no arm pays
    group-rounding overshoot.
  * **`spawn` needs a player source** (`getPlayerOrException`), so it is issued
    through `execute as <player>` rather than straight from the RCON console.

Two JVMs must already be running (`run/` is gitignored, so `setup` writes the
server config):

    python3 scripts/perf/run-ab.py setup
    ./gradlew runServer                             # terminal 1
    ./gradlew runClient -PjoinServer=127.0.0.1:25571  # terminal 2, the rider
    python3 scripts/perf/run-ab.py run

Then analyse — note it is debug.log, NOT latest.log: the [mspt]/[freeze] lines
are DEBUG, and latest.log is INFO-only.

    python3 scripts/perf/parse-dt-perf-log.py run/logs/debug.log
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from rcon import Rcon, RconError  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]
RUN_DIR = REPO_ROOT / "run"

DEFAULT_RCON_PORT = 25581
DEFAULT_SERVER_PORT = 25571
DEFAULT_RCON_PASSWORD = "dtbench"

# Non-default ports on purpose: a sibling worktree's dev server sits on 25565 and
# would otherwise lose the bind race.
BENCH_SERVER_PROPERTIES = {
    "level-type": "dungeontrain\\:dungeon_train",
    "level-seed": "dtbench",
    "level-name": "bench",
    "server-port": str(DEFAULT_SERVER_PORT),
    "enable-rcon": "true",
    "rcon.port": str(DEFAULT_RCON_PORT),
    "rcon.password": DEFAULT_RCON_PASSWORD,
    "online-mode": "false",
    "enforce-secure-profile": "false",
    "view-distance": "20",
    # The 60 s tick watchdog will happily kill the server during a worldgen
    # burst; a benchmark would rather have the slow tick in the data.
    "max-tick-time": "-1",
    "sync-chunk-writes": "false",
    "spawn-protection": "0",
}

ORIGIN_RE = re.compile(r"at BlockPos\{x=(-?\d+), y=(-?\d+), z=(-?\d+)\}")
RESIDENT_RE = re.compile(r"resident=(\d+) active=(\d+) frozen=(\d+)")
# "There are 1 of a max of 8 players online: Dev"
LIST_RE = re.compile(r"players online:\s*(.+)$")


def write_server_config() -> None:
    """Write run/eula.txt and merge the bench keys into run/server.properties."""
    RUN_DIR.mkdir(parents=True, exist_ok=True)
    (RUN_DIR / "eula.txt").write_text("eula=true\n", encoding="utf-8")

    path = RUN_DIR / "server.properties"
    existing: dict[str, str] = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip() and not line.startswith("#") and "=" in line:
                key, _, value = line.partition("=")
                existing[key] = value

    merged = {**existing, **BENCH_SERVER_PROPERTIES}
    body = "\n".join(f"{k}={v}" for k, v in sorted(merged.items()))
    path.write_text(f"# Written by scripts/perf/run-ab.py\n{body}\n", encoding="utf-8")
    print(f"wrote {path}\nwrote {RUN_DIR / 'eula.txt'}")
    print(f"\nnow start the two JVMs:\n  ./gradlew runServer"
          f"\n  ./gradlew runClient -PjoinServer=127.0.0.1:{DEFAULT_SERVER_PORT}")


def connect(host: str, port: int, password: str, wait_s: float) -> Rcon:
    """Retry until the server's RCON listener is up (it binds late in startup)."""
    deadline = time.monotonic() + wait_s
    last: Exception | None = None
    while time.monotonic() < deadline:
        try:
            rcon = Rcon(host, port, password)
            rcon.connect()
            return rcon
        except (OSError, RconError) as exc:
            last = exc
            time.sleep(2.0)
    raise SystemExit(f"no RCON on {host}:{port} after {wait_s:.0f}s — is the server up? ({last})")


def wait_for_player(rcon: Rcon, wait_s: float) -> str:
    """Block until the rider client has joined; return its name.

    The train is culled outright on a player-less server (generation AND sustain
    are gated on `players.isEmpty()`), so sampling before the rider lands would
    measure an empty world.
    """
    deadline = time.monotonic() + wait_s
    while time.monotonic() < deadline:
        match = LIST_RE.search(rcon.command("list"))
        if match and match.group(1).strip():
            return match.group(1).split(",")[0].strip()
        time.sleep(2.0)
    raise SystemExit(f"no player joined within {wait_s:.0f}s — is the rider client running "
                     f"with -PjoinServer=127.0.0.1:{DEFAULT_SERVER_PORT}?")


def resident_count(rcon: Rcon) -> int | None:
    match = RESIDENT_RE.search(rcon.command("dungeontrain debug physicsfreeze status"))
    return int(match.group(1)) if match else None


def wait_until_filled(rcon: Rcon, wait_s: float, expected: int,
                      settle_polls: int = 6) -> int | None:
    """Poll the resident sub-level count until it stops moving.

    The appender adds groups over several ticks, so sampling straight after a
    respawn would average a growing train against a settled one.

    Two guards, both learned the hard way — a naive "3 equal polls" check
    returned `resident=3` on a train that went on to settle at 14, because the
    appender pauses between groups waiting for Sable to register each new
    sub-level, and a pause reads exactly like a plateau. So: require more
    consecutive stable polls, AND require the count to be in the expected
    neighbourhood before believing them.
    """
    floor = max(1, int(expected * 0.8))
    deadline = time.monotonic() + wait_s
    stable = 0
    previous = None
    while time.monotonic() < deadline:
        current = resident_count(rcon)
        if current is not None and current == previous and current >= floor:
            stable += 1
            if stable >= settle_polls:
                return current
        else:
            stable = 0
        previous = current
        time.sleep(2.0)
    print(f"\n    WARNING: resident count never settled near {expected} "
          f"(last={previous}) — the parser's stable-only filter will still "
          f"discard the fill phase.")
    return previous


def run_arm(rcon: Rcon, player: str, arm: int, carriages: int, dwell_s: float,
            fill_wait_s: float, teleport: bool) -> dict:
    """Set the arm, respawn the train, settle, then dwell while the log samples."""
    print(f"  groupSize={arm}: ", end="", flush=True)
    rcon.command(f"dungeontrain debug groupsize {arm}")
    reply = rcon.command(f"execute as {player} at {player} run dungeontrain spawn {carriages}")

    origin = ORIGIN_RE.search(reply)
    if origin and teleport:
        x, y, z = (int(g) for g in origin.groups())
        # Onto the train, so the sustained window is centred the same way in
        # every arm. y+2 lands inside the carriage rather than on its roof.
        rcon.command(f"tp {player} {x} {y + 2} {z}")
    elif not origin:
        print(f"\n    WARNING: no spawn origin in reply: {reply.strip()!r}")

    resident = wait_until_filled(rcon, fill_wait_s, expected=carriages // arm)
    print(f"resident={resident} sub-levels, dwelling {dwell_s:.0f}s", flush=True)
    time.sleep(dwell_s)
    return {"groupSize": arm, "resident": resident, "spawnReply": reply.strip()}


MOD_CONFIG = REPO_ROOT / "run" / "config" / "dungeontrain-server.toml"
WORLD_DIR = RUN_DIR / "bench"
ARCHIVE_DIR = RUN_DIR / "bench-archive"
AB_LOG_DIR = RUN_DIR / "logs" / "ab"


def patch_mod_config(**values) -> None:
    """Set `key = value` lines in the mod's server TOML.

    This config is GLOBAL (`run/config/dungeontrain-server.toml`), not per-world
    — which is what makes the fresh-world matrix possible: the arm can be pinned
    before the server boots, so the world-creation train is already correct and
    no in-world respawn (at a different position, on different terrain) is
    needed to apply it.
    """
    if not MOD_CONFIG.exists():
        raise SystemExit(f"{MOD_CONFIG} missing — boot the server once to generate it.")
    text = MOD_CONFIG.read_text(encoding="utf-8")
    for key, value in values.items():
        pattern = re.compile(rf"^(\s*){key}\s*=\s*.*$", re.MULTILINE)
        if not pattern.search(text):
            raise SystemExit(f"key {key!r} not found in {MOD_CONFIG}")
        text = pattern.sub(rf"\g<1>{key} = {value}", text, count=1)
    MOD_CONFIG.write_text(text, encoding="utf-8")


def reset_world(label: str) -> None:
    """Move the bench world aside so the next boot regenerates it from the seed.

    Moved, never deleted: `rm -rf` is deny-listed in this repo on purpose, and a
    benchmark script is not the place to make an exception. The archive is yours
    to clear out afterwards.
    """
    if WORLD_DIR.exists():
        ARCHIVE_DIR.mkdir(parents=True, exist_ok=True)
        shutil.move(str(WORLD_DIR), str(ARCHIVE_DIR / f"{label}-{int(time.time())}"))


def launch(command: list[str], log_path: Path) -> subprocess.Popen:
    """Start a Gradle run with its own process group, streaming to `log_path`.

    Own process group so teardown can signal the whole tree: killing the Gradle
    wrapper alone leaves the forked JVM running, and the client JVM in
    particular survives a server `stop` sitting on its disconnect screen.
    """
    log_path.parent.mkdir(parents=True, exist_ok=True)
    handle = log_path.open("w", encoding="utf-8")
    env = {**os.environ}
    env.setdefault("JAVA_HOME", str(Path.home() / ".gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2"
                                    / "jdk-21.0.11+10/Contents/Home"))
    return subprocess.Popen(command, cwd=REPO_ROOT, stdout=handle,
                            stderr=subprocess.STDOUT, start_new_session=True, env=env)


def terminate(process: subprocess.Popen | None, grace_s: float = 25.0) -> None:
    if process is None or process.poll() is not None:
        return
    try:
        os.killpg(os.getpgid(process.pid), signal.SIGTERM)
    except ProcessLookupError:
        return
    deadline = time.monotonic() + grace_s
    while time.monotonic() < deadline and process.poll() is None:
        time.sleep(1.0)
    if process.poll() is None:
        try:
            os.killpg(os.getpgid(process.pid), signal.SIGKILL)
        except ProcessLookupError:
            pass


def run_one_arm(args: argparse.Namespace, rep: int, arm: int) -> dict:
    """One complete cycle: fresh same-seed world, boot, join, settle, sample."""
    label = f"rep{rep}-arm{arm}"
    print(f"\n=== {label}: fresh world, groupSize={arm} ===", flush=True)

    reset_world(label)
    patch_mod_config(groupSize=arm, numCarriages=args.carriages, speed="0.0")

    server = client = None
    try:
        server = launch(["./gradlew", "runServer"], AB_LOG_DIR / f"{label}-server.log")
        rcon = connect(args.host, args.port, args.password, args.wait)
        with rcon:
            client = launch(["./gradlew", "runClient",
                             f"-PjoinServer=127.0.0.1:{DEFAULT_SERVER_PORT}"],
                            AB_LOG_DIR / f"{label}-client.log")
            player = wait_for_player(rcon, args.wait)
            print(f"  rider {player} joined", flush=True)

            # The world-creation train already carries this arm's groupSize, but
            # respawn anyway so the carriage COUNT is pinned identically in every
            # cycle rather than depending on what world-gen happened to place.
            reply = rcon.command(f"execute as {player} at {player} run dungeontrain spawn {args.carriages}")
            origin = ORIGIN_RE.search(reply)
            if origin and not args.no_tp:
                x, y, z = (int(g) for g in origin.groups())
                rcon.command(f"tp {player} {x} {y + 2} {z}")

            resident = wait_until_filled(rcon, args.fill_wait, expected=args.carriages // arm)
            print(f"  settled at {resident} sub-levels, dwelling {args.dwell:.0f}s", flush=True)
            time.sleep(args.dwell)

            # The train is culled outright the moment the rider drops, so an arm
            # that lost its client mid-dwell measured an emptying world, not this
            # arm. Check rather than trust — it has already happened once.
            still_online = LIST_RE.search(rcon.command("list"))
            rider_held = bool(still_online and player in still_online.group(1))
            final_resident = resident_count(rcon)
            if not rider_held:
                print(f"  INVALID: rider left during the dwell", flush=True)

            rcon.command("stop")
        return {"rep": rep, "groupSize": arm, "resident": resident,
                "finalResident": final_resident, "riderHeld": rider_held,
                "valid": rider_held and final_resident == resident,
                "serverLog": str((AB_LOG_DIR / f"{label}-server.log").relative_to(REPO_ROOT))}
    finally:
        terminate(client)
        terminate(server)


def run_matrix(args: argparse.Namespace) -> int:
    """Fresh same-seed world per arm — the only way the arms see identical
    terrain, an identical spawn position, and identical carriage content.

    Respawning arms inside one world (the cheaper design) walks the train
    forward, so each arm sits on different terrain and draws different
    carriage variants — both deterministic on position, and both confounded
    with the thing under test.
    """
    arms = [int(a) for a in args.arms.split(",")]
    bad = [a for a in arms if args.carriages % a]
    if bad:
        raise SystemExit(f"--carriages {args.carriages} is not divisible by arm(s) {bad}.")

    results = []
    for rep in range(1, args.reps + 1):
        for arm in arms:
            results.append(run_one_arm(args, rep, arm))

    manifest = {"mode": "matrix", "carriages": args.carriages, "arms": arms,
                "reps": args.reps, "dwellSeconds": args.dwell,
                "seed": BENCH_SERVER_PROPERTIES["level-seed"], "results": results}
    path = AB_LOG_DIR / "matrix-manifest.json"
    path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")

    valid = [r for r in results if r["valid"]]
    print(f"\n{len(valid)}/{len(results)} arms valid — manifest at {path}")
    print("analyse with:\n  python3 scripts/perf/parse-dt-perf-log.py "
          f"{AB_LOG_DIR.relative_to(REPO_ROOT)}/rep*-arm*-server.log")
    return 0 if valid else 1


def run(args: argparse.Namespace) -> int:
    arms = [int(a) for a in args.arms.split(",")]
    bad = [a for a in arms if args.carriages % a]
    if bad:
        raise SystemExit(f"--carriages {args.carriages} is not divisible by arm(s) {bad} — "
                         "the arms would pay different group-rounding overshoot and "
                         "the comparison would not be matched.")

    rcon = connect(args.host, args.port, args.password, args.wait)
    with rcon:
        player = wait_for_player(rcon, args.wait)
        print(f"rider: {player}")

        # Pin the window off render distance, and park so chunk gen stops.
        print(rcon.command(f"dungeontrain carriages {args.carriages}").strip())
        print(rcon.command("dungeontrain speed 0").strip())

        events = []
        started = time.time()
        for rep in range(1, args.reps + 1):
            print(f"rep {rep}/{args.reps}")
            for arm in arms:
                event = run_arm(rcon, player, arm, args.carriages, args.dwell,
                                args.fill_wait, not args.no_tp)
                events.append({"rep": rep, **event})

        manifest = {
            "carriages": args.carriages,
            "arms": arms,
            "reps": args.reps,
            "dwellSeconds": args.dwell,
            "startedEpoch": started,
            "finishedEpoch": time.time(),
            "events": events,
        }
        path = RUN_DIR / "logs" / "ab-manifest.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")

    print(f"\ndone — manifest at {path}\nanalyse with:\n"
          f"  python3 scripts/perf/parse-dt-perf-log.py run/logs/debug.log")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="mode", required=True)

    sub.add_parser("setup", help="write run/eula.txt + run/server.properties for the bench")

    for name, help_text in (
        ("matrix", "fresh same-seed world per arm — starts and stops both JVMs itself"),
        ("run", "drive the arms inside ONE already-running world (faster, less controlled)"),
    ):
        mode = sub.add_parser(name, help=help_text)
        mode.add_argument("--host", default="127.0.0.1")
        mode.add_argument("--port", type=int, default=DEFAULT_RCON_PORT)
        mode.add_argument("--password", default=DEFAULT_RCON_PASSWORD)
        mode.add_argument("--arms", default="3,6", help="groupSize values, comma-separated")
        mode.add_argument("--carriages", type=int, default=36,
                          help="pinned window size; must divide by every arm")
        mode.add_argument("--reps", type=int, default=3)
        mode.add_argument("--dwell", type=float, default=90.0, help="seconds sampled per arm")
        mode.add_argument("--fill-wait", type=float, default=120.0,
                          help="seconds to wait for the train to finish filling after a respawn")
        mode.add_argument("--wait", type=float, default=600.0,
                          help="seconds to wait for the server and the rider to come up")
        mode.add_argument("--no-tp", action="store_true",
                          help="skip teleporting the rider onto the train after each respawn")

    args = parser.parse_args(argv)
    if args.mode == "setup":
        write_server_config()
        return 0
    if args.mode == "matrix":
        return run_matrix(args)
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
