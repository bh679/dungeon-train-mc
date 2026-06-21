#!/usr/bin/env python3
"""Unit tests for build-mrpack.py — Modrinth modpack index rendering.

Tests the *pure* functions directly (no network): ``compute_env``, ``build_index``,
``load_config``, and ``resolve_files`` with an injected fake ``fetch``. The module is loaded
by path because its filename has a hyphen. Also renders the real shipped config to guard the
Modrinth pins. Hand-rolled runner (no pytest needed) so it runs on a bare CI runner.

Run: python3 scripts/modpack/test_build_mrpack.py   (or via pytest)
"""
import importlib.util
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(HERE))


def _load_module():
    spec = importlib.util.spec_from_file_location(
        "build_mrpack", os.path.join(HERE, "build-mrpack.py")
    )
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


bm = _load_module()

GRADLE_PROPS = {"minecraft_version": "1.21.1", "neo_version": "21.1.228", "mod_version": "0.293.0"}
CONFIG = {
    "name": "Dungeon Train",
    "author": "Brennan Hatton",
    "dt_project_id": 1527512,
    "dt_modrinth_project": "dungeon-train",
    "sable": {"modrinth_project": "sable", "modrinth_version": "6PfAtJN1"},
}


# ---------------------------------------------------------------- compute_env

def test_env_bundled_both_sides_required():
    assert bm.compute_env(required=True, client_side="required", server_side="required") == {
        "client": "required", "server": "required",
    }


def test_env_optin_both_sides_optional():
    assert bm.compute_env(required=False, client_side="optional", server_side="optional") == {
        "client": "optional", "server": "optional",
    }


def test_env_server_unsupported_is_respected():
    # A client-only mod (e.g. AmbientSounds) skips dedicated servers.
    assert bm.compute_env(required=True, client_side="required", server_side="unsupported") == {
        "client": "required", "server": "unsupported",
    }


def test_env_client_unsupported_is_ignored():
    # Lithostitched declares client_side=unsupported but is needed by the integrated server in
    # single-player; the pack keeps it present client-side (mirrors the CurseForge pack).
    assert bm.compute_env(required=True, client_side="unsupported", server_side="required") == {
        "client": "required", "server": "required",
    }


def test_env_optin_client_only_mod():
    # Mouse Tweaks: opt-in + client-only -> client optional, server unsupported.
    assert bm.compute_env(required=False, client_side="required", server_side="unsupported") == {
        "client": "optional", "server": "unsupported",
    }


# ---------------------------------------------------------------- build_index

def _resolved(name, filename, *, required, client="required", server="required",
              url=None, sha1="a" * 40, sha512="b" * 128, size=123):
    return {
        "name": name, "required": required, "filename": filename,
        "url": url or f"https://cdn.modrinth.com/data/X/versions/Y/{filename}",
        "sha1": sha1, "sha512": sha512, "size": size,
        "client_side": client, "server_side": server,
    }


def test_build_index_happy_path():
    resolved = [
        _resolved("Dungeon Train", "dungeontrain-0.293.0.jar", required=True),
        _resolved("Sable", "sable-neoforge-1.21.1-2.0.2.jar", required=True),
        _resolved("Mouse Tweaks", "MouseTweaks.jar", required=False, server="unsupported"),
    ]
    idx = bm.build_index(resolved_files=resolved, version="v0.293.0", config=CONFIG,
                         gradle_props=GRADLE_PROPS)
    assert idx["formatVersion"] == 1
    assert idx["game"] == "minecraft"
    assert idx["versionId"] == "0.293.0"  # leading "v" stripped
    assert idx["name"] == "Dungeon Train"
    assert idx["dependencies"] == {"minecraft": "1.21.1", "neoforge": "21.1.228"}
    assert len(idx["files"]) == 3
    f0 = idx["files"][0]
    assert f0["path"] == "mods/dungeontrain-0.293.0.jar"
    assert f0["hashes"] == {"sha1": "a" * 40, "sha512": "b" * 128}
    assert f0["downloads"] == ["https://cdn.modrinth.com/data/X/versions/Y/dungeontrain-0.293.0.jar"]
    assert f0["fileSize"] == 123
    assert f0["env"] == {"client": "required", "server": "required"}
    # opt-in client-only mod
    assert idx["files"][2]["env"] == {"client": "optional", "server": "unsupported"}


def test_build_index_version_without_v_unchanged():
    idx = bm.build_index(resolved_files=[_resolved("DT", "dt.jar", required=True)],
                         version="1.0.0", config=CONFIG, gradle_props=GRADLE_PROPS)
    assert idx["versionId"] == "1.0.0"


def test_build_index_summary_only_when_present():
    idx = bm.build_index(resolved_files=[_resolved("DT", "dt.jar", required=True)],
                         version="1.0.0", config=CONFIG, gradle_props=GRADLE_PROPS)
    assert "summary" not in idx
    cfg = {**CONFIG, "summary": "A roguelite adventure."}
    idx2 = bm.build_index(resolved_files=[_resolved("DT", "dt.jar", required=True)],
                          version="1.0.0", config=cfg, gradle_props=GRADLE_PROPS)
    assert idx2["summary"] == "A roguelite adventure."


def test_build_index_empty_rejected():
    try:
        bm.build_index(resolved_files=[], version="1.0.0", config=CONFIG, gradle_props=GRADLE_PROPS)
    except ValueError as e:
        assert "empty" in str(e).lower()
    else:
        raise AssertionError("expected ValueError on empty resolved_files")


def test_build_index_duplicate_path_rejected():
    dup = [_resolved("A", "same.jar", required=True), _resolved("B", "same.jar", required=True)]
    try:
        bm.build_index(resolved_files=dup, version="1.0.0", config=CONFIG, gradle_props=GRADLE_PROPS)
    except ValueError as e:
        assert "duplicate" in str(e).lower()
    else:
        raise AssertionError("expected ValueError on duplicate path")


def test_build_index_missing_neo_version_rejected():
    try:
        bm.build_index(resolved_files=[_resolved("DT", "dt.jar", required=True)], version="1.0.0",
                       config=CONFIG, gradle_props={"minecraft_version": "1.21.1"})
    except ValueError as e:
        assert "neo_version" in str(e)
    else:
        raise AssertionError("expected ValueError on missing neo_version")


# ---------------------------------------------------------------- load_config

def _write_config(tmp, cfg):
    p = os.path.join(tmp, "modpack.config.json")
    with open(p, "w") as f:
        json.dump(cfg, f)
    from pathlib import Path
    return Path(p)


def test_load_config_valid():
    import tempfile
    tmp = tempfile.mkdtemp()
    cfg = {**CONFIG, "optional_mods": [
        {"name": "AppleSkin", "modrinth_project": "appleskin", "modrinth_version": "uAKA6Laj", "required": True},
    ]}
    loaded = bm.load_config(_write_config(tmp, cfg))
    assert loaded["sable"]["modrinth_version"] == "6PfAtJN1"


def test_load_config_missing_sable_modrinth_version_rejected():
    import tempfile
    tmp = tempfile.mkdtemp()
    cfg = {**CONFIG, "sable": {"modrinth_project": "sable"}}  # no modrinth_version
    try:
        bm.load_config(_write_config(tmp, cfg))
    except ValueError as e:
        assert "modrinth_version" in str(e)
    else:
        raise AssertionError("expected ValueError on missing sable modrinth_version")


def test_load_config_missing_optional_modrinth_project_rejected():
    import tempfile
    tmp = tempfile.mkdtemp()
    cfg = {**CONFIG, "optional_mods": [{"name": "X", "modrinth_version": "abc", "required": True}]}
    try:
        bm.load_config(_write_config(tmp, cfg))
    except ValueError as e:
        assert "modrinth_project" in str(e)
    else:
        raise AssertionError("expected ValueError on missing optional modrinth_project")


def test_load_config_non_boolean_required_rejected():
    import tempfile
    tmp = tempfile.mkdtemp()
    cfg = {**CONFIG, "optional_mods": [
        {"name": "X", "modrinth_project": "x", "modrinth_version": "v", "required": "yes"},
    ]}
    try:
        bm.load_config(_write_config(tmp, cfg))
    except ValueError as e:
        assert "required" in str(e) and "boolean" in str(e)
    else:
        raise AssertionError("expected ValueError on non-boolean required")


# ---------------------------------------------------------------- resolve_files (fake fetch)

def _fake_fetch():
    """Return a fetch(url) that serves canned /version/{id} and /project/{id} responses."""
    versions = {
        "DTVER": {"project_id": "DTPROJ", "files": [
            {"primary": True, "filename": "dungeontrain-0.293.0.jar",
             "url": "https://cdn.modrinth.com/data/DTPROJ/versions/DTVER/dungeontrain-0.293.0.jar",
             "hashes": {"sha1": "1" * 40, "sha512": "2" * 128}, "size": 8000000}]},
        "6PfAtJN1": {"project_id": "T9PomCSv", "files": [
            {"primary": True, "filename": "sable-neoforge-1.21.1-2.0.2.jar",
             "url": "https://cdn.modrinth.com/data/T9PomCSv/versions/6PfAtJN1/sable.jar",
             "hashes": {"sha1": "3" * 40, "sha512": "4" * 128}, "size": 12000000}]},
        "MTVER": {"project_id": "MTPROJ", "files": [
            {"primary": False, "filename": "ignored.jar", "url": "x",
             "hashes": {"sha1": "z" * 40, "sha512": "z" * 128}, "size": 1},
            {"primary": True, "filename": "MouseTweaks.jar",
             "url": "https://cdn.modrinth.com/data/MTPROJ/versions/MTVER/MouseTweaks.jar",
             "hashes": {"sha1": "5" * 40, "sha512": "6" * 128}, "size": 70000}]},
    }
    projects = {
        "DTPROJ": {"client_side": "required", "server_side": "required"},
        "T9PomCSv": {"client_side": "required", "server_side": "required"},
        "MTPROJ": {"client_side": "required", "server_side": "unsupported"},
    }

    def fetch(url):
        if "/version/" in url:
            return versions[url.rsplit("/", 1)[1]]
        if "/project/" in url:
            return projects[url.rsplit("/", 1)[1]]
        raise AssertionError(f"unexpected url {url}")

    return fetch


def test_resolve_files_forces_dt_and_sable_required_and_reads_metadata():
    cfg = {**CONFIG, "optional_mods": [
        {"name": "Mouse Tweaks", "modrinth_project": "mouse-tweaks", "modrinth_version": "MTVER", "required": False},
    ]}
    resolved = bm.resolve_files(cfg, "DTVER", fetch=_fake_fetch())
    assert [r["name"] for r in resolved] == ["Dungeon Train", "Sable", "Mouse Tweaks"]
    assert resolved[0]["required"] is True   # DT forced
    assert resolved[1]["required"] is True   # Sable forced
    assert resolved[2]["required"] is False  # opt-in flag honoured
    # primary file picked over the non-primary one
    assert resolved[2]["filename"] == "MouseTweaks.jar"
    assert resolved[2]["sha512"] == "6" * 128
    assert resolved[2]["server_side"] == "unsupported"
    # end-to-end: feed resolved into build_index, opt-in client-only -> client optional/server unsupported
    idx = bm.build_index(resolved_files=resolved, version="0.293.0", config=cfg, gradle_props=GRADLE_PROPS)
    mt = idx["files"][2]
    assert mt["env"] == {"client": "optional", "server": "unsupported"}
    assert mt["path"] == "mods/MouseTweaks.jar"


def test_resolve_version_missing_hash_rejected():
    def fetch(url):
        if "/version/" in url:
            return {"project_id": "P", "files": [
                {"primary": True, "filename": "x.jar", "url": "u",
                 "hashes": {"sha1": "1" * 40}, "size": 1}]}  # no sha512
        return {"client_side": "required", "server_side": "required"}
    try:
        bm.resolve_version("ANY", fetch=fetch)
    except ValueError as e:
        assert "sha512" in str(e)
    else:
        raise AssertionError("expected ValueError on missing sha512")


# ---------------------------------------------------------------- real shipped config

def test_real_config_every_mod_has_modrinth_pins():
    """Guard: the real config carries Modrinth pins on sable + every optional mod.

    Mirrors the CI `--check-config` guard so a forgotten pin (which would silently drop a mod
    from the Modrinth pack) fails the unit suite too.
    """
    real = os.path.join(REPO_ROOT, "modpack", "modpack.config.json")
    cfg = json.loads(open(real).read())
    assert cfg["sable"].get("modrinth_project") and cfg["sable"].get("modrinth_version")
    for opt in cfg["optional_mods"]:
        assert opt.get("modrinth_project"), f"{opt.get('name')} missing modrinth_project"
        assert opt.get("modrinth_version"), f"{opt.get('name')} missing modrinth_version"
    # Hard pins mirrored from the CurseForge pack.
    sable = cfg["sable"]
    assert sable["modrinth_version"] == "6PfAtJN1", sable  # Sable 2.0.2+mc1.21.1
    by_slug = {o["slug"]: o for o in cfg["optional_mods"]}
    assert by_slug["jade"]["modrinth_version"] == "yd8FKCmx"            # Jade 15.10.5 (Sable compat)
    assert by_slug["distant-horizons"]["modrinth_version"] == "75PXmyqH"  # DH 2.4.3-b (2.x only)
    # the two slugs whose Modrinth project differs from the CurseForge slug
    assert by_slug["ferritecore"]["modrinth_project"] == "ferrite-core"
    assert by_slug["distant-horizons"]["modrinth_project"] == "distanthorizons"


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
