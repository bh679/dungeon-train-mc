#!/usr/bin/env python3
"""Reproduce the collapsed-count merge (#2541) and show the post-merge refresh mends it.

Two branches cut from the same commit each add one lang key and run
``stamp-provenance.py --sync``, which moves every generated count by the same +1. Squash-
merged one after the other, git keeps that identical edit once: main holds two new keys but
counts one higher, and ``check-provenance.py`` fails. The script then runs the exact command
version-bump.yml runs after every merge (``--refresh``) and asserts:

  * check-provenance.py passes again;
  * the refresh touched only the generated files (credits, contributors, manifests);
  * no sidecar moved, so every author/reviewer, and with them human credit, is unchanged.

Both merge orders are exercised. Everything happens in a throwaway clone of HEAD (the full
tree, narrative data included: a partial copy would drop book entries from the manifests),
so the working checkout is never touched. Commit first: uncommitted changes are not cloned.

Usage:
  python3 scripts/localization/repro-collapsed-counts.py [--keep]
"""
import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
ASSETS = "src/main/resources/assets/dungeontrain"
LANG_DIR = f"{ASSETS}/lang"
SIDECARS = "localization/provenance"
GENERATED = (f"{ASSETS}/localization_credits/", f"{ASSETS}/translation_contributors.json",
             f"{ASSETS}/localization_provenance/")
AI_AUTHOR = "Opus 5.5 (Claude)"
# One anchor per branch, far apart in the lang files: real PRs add keys in different places,
# so the lang files and sidecars merge cleanly and only the count lines collide.
BRANCHES = {
    "repro/a": ("gui.dungeontrain.repro_collapse_a", 200),
    "repro/b": ("gui.dungeontrain.repro_collapse_b", 3000),
}


def sh(cwd: Path, *cmd: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, cwd=cwd, check=check, capture_output=True, text=True)


def git(cwd: Path, *args: str, check: bool = True) -> str:
    return sh(cwd, "git", *args, check=check).stdout.strip()


def check_provenance(clone: Path) -> subprocess.CompletedProcess:
    return sh(clone, sys.executable, "scripts/localization/check-provenance.py", check=False)


def add_key(clone: Path, key: str, anchor_line: int) -> None:
    """Insert ``key`` after line ``anchor_line`` of every dungeontrain lang file."""
    for path in sorted((clone / LANG_DIR).glob("*.json")):
        lines = path.read_text(encoding="utf-8").split("\n")
        at = min(anchor_line, len(lines) - 3)
        while not lines[at].rstrip().endswith(","):  # land between two entries
            at += 1
        lines.insert(at + 1, f'  "{key}": "Repro collapse {path.stem}",')
        path.write_text("\n".join(lines), encoding="utf-8")


def make_branch(clone: Path, base: str, name: str, key: str, anchor: int) -> None:
    git(clone, "checkout", "-q", "-B", name, base)
    add_key(clone, key, anchor)
    sh(clone, sys.executable, "scripts/localization/stamp-provenance.py",
       "--sync", "--author", AI_AUTHOR)
    proc = check_provenance(clone)
    assert proc.returncode == 0, f"{name} should pass on its own:\n{proc.stdout}{proc.stderr}"
    git(clone, "add", "-A")
    git(clone, "commit", "-qm", f"add {key}")


def sidecar_credit(clone: Path) -> dict:
    """Every (locale, key) -> (author, reviewer) in the dungeontrain sidecars."""
    out = {}
    for path in sorted((clone / SIDECARS).glob("*.json")):
        for key, entry in json.loads(path.read_text(encoding="utf-8")).items():
            out[(path.stem, key)] = (entry["author"], entry["reviewer"])
    return out


def total_keys(clone: Path, locale: str = "de_de") -> int:
    path = clone / ASSETS / "localization_credits" / f"{locale}.json"
    return json.loads(path.read_text(encoding="utf-8"))["total_keys"]


def run_order(clone: Path, base: str, order: list[str]) -> None:
    print(f"\n== merge order: {' then '.join(order)}")
    git(clone, "checkout", "-q", "-B", "main", base)
    before = total_keys(clone)
    for name in order:
        merged = sh(clone, "git", "merge", "--squash", name, check=False)
        assert merged.returncode == 0, f"squash of {name} conflicted:\n{merged.stdout}"
        git(clone, "commit", "-qm", f"squash {name}")

    after = total_keys(clone)
    proc = check_provenance(clone)
    print(f"   after both merges: total_keys {before} -> {after} (two keys added)")
    assert after == before + 1, "expected the identical +1 edits to collapse into one"
    assert proc.returncode != 0, "check-provenance should fail on the collapsed counts"
    print("   check-provenance.py: FAIL (reproduced the collapse)")

    credit_before = sidecar_credit(clone)
    # Exactly what version-bump.yml (and build.yml's main-only step) runs.
    sh(clone, sys.executable, "scripts/localization/stamp-provenance.py", "--refresh")

    proc = check_provenance(clone)
    assert proc.returncode == 0, f"still failing after --refresh:\n{proc.stdout}{proc.stderr}"
    assert total_keys(clone) == before + 2
    changed = (git(clone, "diff", "--name-only").splitlines()
               + git(clone, "ls-files", "--others", "--exclude-standard").splitlines())
    stray = [c for c in changed if not c.startswith(GENERATED)]
    assert not stray, f"--refresh touched non-generated files: {stray}"
    assert sidecar_credit(clone) == credit_before, "--refresh moved an author or reviewer"
    print(f"   after --refresh: total_keys {total_keys(clone)}; check-provenance.py: OK")
    print(f"   {len(changed)} generated file(s) rewritten, 0 sidecars touched, "
          f"{len(credit_before)} author/reviewer stamps unchanged")
    git(clone, "checkout", "-q", "--", ".")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--keep", action="store_true", help="leave the temp clone behind")
    args = parser.parse_args(argv)

    tmp = Path(tempfile.mkdtemp(prefix="repro-collapsed-counts-"))
    clone = tmp / "repo"
    try:
        sh(REPO, "git", "clone", "-q", "--no-local", "--branch",
           git(REPO, "rev-parse", "--abbrev-ref", "HEAD"), str(REPO), str(clone))
        git(clone, "config", "user.name", "repro")
        git(clone, "config", "user.email", "repro@example.invalid")
        base = git(clone, "rev-parse", "HEAD")
        proc = check_provenance(clone)
        assert proc.returncode == 0, f"base must be green first:\n{proc.stdout}{proc.stderr}"
        for name, (key, anchor) in BRANCHES.items():
            make_branch(clone, base, name, key, anchor)
        run_order(clone, base, ["repro/a", "repro/b"])
        run_order(clone, base, ["repro/b", "repro/a"])
    except AssertionError as exc:
        print(f"\nREPRO FAILED: {exc}", file=sys.stderr)
        return 1
    finally:
        if args.keep:
            print(f"\nclone kept at {clone}")
        else:
            shutil.rmtree(tmp, ignore_errors=True)
    print("\nOK: both orders collapse the counts on merge, and the post-merge refresh mends them.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
