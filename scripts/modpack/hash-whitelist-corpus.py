#!/usr/bin/env python3
"""The false-positive / false-negative corpus for the hash-verified whitelist. Hand-run before a
release that touches the file check (Gate 2 bar: zero false positives, zero misses).

For the most-covered approved mods it takes real published jars (several versions each, from the
``~/.cache/dungeontrain-modpack-jars`` cache and Modrinth), fingerprints them exactly as the client
does (sha1, CurseForge murmur2, package fingerprint — the Python twins of the Java), POSTs them to
a relay's ``/approved-mods/verify`` and expects every one to come back **approved or plausible**.
Then, for each, it builds an **impostor**: another mod's jar with its ``mods.toml`` rewritten to
claim this id, and expects **suspect**. It also checks the client-side rule offline, straight from
the baked ``approved_mods.json`` (hashes + knownPackages), since an offline boot judges with that.

    python3 scripts/modpack/hash-whitelist-corpus.py --relay http://127.0.0.1:3317/localdevcap [--mods 40] [--versions 3]

Prints one row per jar and a summary. Exit 1 on any false positive or miss.
"""
import argparse
import hashlib
import io
import json
import re
import sys
import urllib.request
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from importlib import import_module  # noqa: E402

cmi = import_module("check-mod-ids")
cff = import_module("cf_fingerprint")
pull = import_module("pull-approved-hashes")

REPO_ROOT = Path(__file__).resolve().parents[2]
BAKED = REPO_ROOT / "build" / "generated" / "sources" / "approvedMods" / "assets" / "dungeontrain" / "cheat" / "approved_mods.json"
EXEMPT = {"minecraft", "neoforge", "forge", "dungeontrain"}
NOT_FREE_PLAY = ("approved", "plausible", "unknown")


def fingerprint(data, file_name):
    return {
        "sha1": hashlib.sha1(data).hexdigest(),
        "murmur2": cff.fingerprint(data),
        "size": len(data),
        "fileName": file_name,
        "modIds": cmi.jar_mod_ids(data),
        "packages": cmi.jar_packages(data),
    }


def client_tier(fp, hashes, known):
    """The client's UnapprovedModIntegrity.tierOf, in Python."""
    if fp["sha1"] in hashes:
        return "approved"
    if not fp["modIds"]:
        return "suspect"
    jar = set(fp["packages"])
    unknown = False
    for mid in fp["modIds"]:
        if mid in EXEMPT:
            continue
        k = known.get(mid)
        if k is None:
            unknown = True
            continue
        mismatch = bool(jar) if len(k) == 0 else jar.isdisjoint(k)
        if mismatch:
            return "suspect"
    return "unknown" if unknown else "plausible"


def impostor(data, claim_id):
    """A copy of `data` whose mods.toml declares `claim_id` instead — the renamed-cheat-jar attack."""
    out = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data)) as src, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
        for item in src.infolist():
            if item.filename.startswith("META-INF/jarjar/"):
                continue  # keep the impostor a single jar claiming one id
            payload = src.read(item.filename)
            if item.filename in cmi.TOML_NAMES:
                text = payload.decode("utf-8", "replace")
                text = re.sub(r'(modId\s*=\s*["\'])([^"\']+)(["\'])', lambda m: m.group(1) + claim_id + m.group(3), text, count=1)
                payload = text.encode("utf-8")
            dst.writestr(item, payload)
    return out.getvalue()


def relay_verify(base, files):
    body = json.dumps({"dt": "0.0.0", "files": files}).encode()
    req = urllib.request.Request(base.rstrip("/") + "/approved-mods/verify", data=body,
                                 headers={"Content-Type": "application/json", "User-Agent": "dt-corpus"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.load(r)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--relay", help="verify base URL, e.g. http://127.0.0.1:3317/localdevcap (omit for offline only)")
    ap.add_argument("--mods", type=int, default=40)
    ap.add_argument("--versions", type=int, default=3)
    args = ap.parse_args(argv)

    baked = json.loads(BAKED.read_text(encoding="utf-8"))
    hashes = set(baked.get("hashes", []))
    known = dict(baked.get("knownPackages", {}))
    catalogue = json.loads(pull.OUTPUT.read_text(encoding="utf-8"))["byMod"]
    ids = [i for i, rec in sorted(catalogue.items(), key=lambda kv: -len(kv[1]["sha1"]))
           if rec["platform"] == "modrinth"][: args.mods]

    honest = []
    for mid in ids:
        rec = catalogue[mid]
        for v in pull.modrinth_versions(rec["project"])[: args.versions]:
            f = next((x for x in v.get("files", []) if x.get("primary")), (v.get("files") or [None])[0])
            if not f:
                continue
            data = pull.cached(f"mr-{f['hashes']['sha1']}", f["url"])
            if data is not None:
                honest.append((mid, f["filename"], data))
    # Impostors: each mod's newest jar re-labelled as the NEXT mod in the list.
    impostors = []
    seen = set()
    for mid, name, data in honest:
        if mid in seen:
            continue
        seen.add(mid)
        claim = ids[(ids.index(mid) + 1) % len(ids)]
        impostors.append((claim, f"impostor-{name}", impostor(data, claim)))

    batch = [(kind, mid, name, fingerprint(data, name))
             for kind, items in (("honest", honest), ("impostor", impostors)) for mid, name, data in items]
    relay = {}
    if args.relay:
        for i in range(0, len(batch), 100):
            for f in relay_verify(args.relay, [b[3] for b in batch[i:i + 100]]).get("files", []):
                relay[f["sha1"]] = f["status"] + "/" + f.get("reason", "")
    rows = []
    fails = 0
    for kind, mid, name, fp in batch:
        offline = client_tier(fp, hashes, known)
        online = relay.get(fp["sha1"], "-")
        want_ok = kind == "honest"
        ok_off = (offline in NOT_FREE_PLAY) == want_ok
        ok_on = (not args.relay) or ((online.split("/")[0] in NOT_FREE_PLAY) == want_ok)
        bad = not (ok_off and ok_on)
        fails += bad
        rows.append((kind, mid, name[:48], offline, online, "FAIL" if bad else "ok"))
    header = ("kind", "mod", "file", "offline", "relay", "")
    w = [max(len(str(r[i])) for r in rows + [header]) for i in range(6)]
    for r in [header] + rows:
        print("  ".join(str(c).ljust(w[i]) for i, c in enumerate(r)))
    print(f"\n{len(honest)} honest jars, {len(impostors)} impostors, {fails} failure(s)")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
