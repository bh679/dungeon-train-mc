#!/usr/bin/env python3
"""Unit tests for check-mod-ids.py — the modpack mod_ids guard behind the generated whitelist.

Offline only: jars are built in memory, and the real modpack.config.json is checked for presence
(the network --verify pass runs as its own CI step).

Run: python3 scripts/modpack/test_check_mod_ids.py   (or via pytest)
"""
import importlib.util
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "check-mod-ids.py")

_spec = importlib.util.spec_from_file_location("check_mod_ids", SCRIPT)
cmi = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cmi)


def jar(files):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for name, data in files.items():
            z.writestr(name, data)
    return buf.getvalue()


MODS_TOML = """
modLoader="javafml"
[[mods]] # the mod
modId="moonlight"
[[dependencies.moonlight]]
    modId="neoforge"
    type="required"
"""


class TomlTest(unittest.TestCase):
    def test_reads_mods_not_dependencies(self):
        self.assertEqual(cmi.toml_mod_ids(MODS_TOML), ["moonlight"])

    def test_several_mods_and_single_quotes(self):
        text = "[[mods]]\nmodId='a_one'\n[[mods]]\n  modId = \"b_two\"\n"
        self.assertEqual(cmi.toml_mod_ids(text), ["a_one", "b_two"])


class JarTest(unittest.TestCase):
    def test_nested_jarjar_is_included(self):
        inner = jar({"META-INF/neoforge.mods.toml": '[[mods]]\nmodId="codecui"\n'})
        outer = jar({
            "META-INF/neoforge.mods.toml": MODS_TOML,
            "META-INF/jarjar/codecui-1.0.jar": inner,
        })
        self.assertEqual(cmi.jar_mod_ids(outer), ["codecui", "moonlight"])

    def test_library_without_toml_contributes_nothing(self):
        lib = jar({"org/joml/Foo.class": b"\0"})
        outer = jar({"META-INF/neoforge.mods.toml": MODS_TOML, "META-INF/jarjar/joml.jar": lib})
        self.assertEqual(cmi.jar_mod_ids(outer), ["moonlight"])


SHA = "a" * 40


class PresenceTest(unittest.TestCase):
    def cfg(self, *mods):
        return {"sable": {"mod_ids": ["sable"], "sha1": SHA, "packages": ["dev.sable"]}, "optional_mods": list(mods)}

    def test_clean(self):
        self.assertEqual(cmi.check_presence(self.cfg({"name": "A", "mod_ids": ["appleskin"], "sha1": SHA, "packages": []})), [])

    def test_missing_fails(self):
        # mod_ids, sha1 and packages are each their own problem (the hash whitelist needs all three)
        problems = cmi.check_presence(self.cfg({"name": "A"}))
        self.assertEqual(len(problems), 1, "no mod_ids is reported once; sha1/packages follow once ids exist")
        self.assertIn("no mod_ids", problems[0])

    def test_hash_and_packages_required(self):
        problems = cmi.check_presence(self.cfg({"name": "A", "mod_ids": ["appleskin"]}))
        self.assertEqual([p.split(": ")[1].split(" ")[1] for p in problems], ["sha1", "packages"])

    def test_slug_fails(self):
        problems = cmi.check_presence(self.cfg({"name": "A", "mod_ids": ["simple-voice-chat"], "sha1": SHA, "packages": []}))
        self.assertIn("slug", problems[0])

    def test_whitelist_false_needs_nothing(self):
        self.assertEqual(cmi.check_presence(self.cfg({"name": "A", "whitelist": False})), [])


class FillTest(unittest.TestCase):
    def test_inserts_after_slug_and_skips_opt_out(self):
        cfg = {"sable": {"mod_ids": ["sable"], "sha1": SHA, "packages": ["dev.sable"]}, "optional_mods": [
            {"name": "M", "slug": "selene", "project_id": 1},
            {"name": "X", "slug": "x", "whitelist": False},
        ]}
        cmi.actual_ids = lambda label, entry, cache: ["codecui", "moonlight"]
        cmi.actual_sha1 = lambda entry, cache: SHA
        cmi.actual_packages = lambda entry, cache: ["net.mehvahdjukaar"]
        filled = cmi.fill(cfg, None)
        self.assertEqual(len(filled), 1)
        self.assertEqual(list(cfg["optional_mods"][0]), ["name", "slug", "mod_ids", "sha1", "packages", "project_id"])
        self.assertNotIn("mod_ids", cfg["optional_mods"][1])


class PackageFingerprintTest(unittest.TestCase):
    """The Python twin of cheat/PackageFingerprint.java — the same fixtures PackageFingerprintTest pins."""

    def test_keys(self):
        cases = {
            "mezz/jei/common/Foo.class": "mezz.jei", "Foo.class": None, "net/Baz.class": None,
            "org/apache/commons/X.class": None, "com/github/alice/mod/Y.class": "com.github.alice",
            "io/github/bob/Z.class": "io.github.bob", "com/google/gson/G.class": None,
            "net/minecraft/client/C.class": None, "kotlin/Unit.class": None, "META-INF/jarjar/inner.jar": None,
            "assets/x/y.json": None, "dev/alpha/A.class": "dev.alpha", "org/violetmoon/quark/Q.class": "org.violetmoon",
        }
        for name, want in cases.items():
            self.assertEqual(cmi.package_of(name), want, name)

    def test_jar_packages_include_embedded_and_cap(self):
        inner = jar({"net/xray/Cheat.class": b"\0", "org/apache/Shaded.class": b"\0"})
        outer = jar({"mezz/jei/A.class": b"\0", "dev/alpha/C.class": b"\0", "META-INF/jarjar/inner.jar": inner})
        self.assertEqual(cmi.jar_packages(outer), ["dev.alpha", "mezz.jei", "net.xray"])
        many = jar({f"p{i:03d}/q/C.class": b"\0" for i in range(cmi.PKG_MAX + 20)})
        caps = cmi.jar_packages(many)
        self.assertEqual(len(caps), cmi.PKG_MAX)
        self.assertEqual(caps[0], "p000.q")


class CliTest(unittest.TestCase):
    def test_real_config_has_mod_ids(self):
        r = subprocess.run([sys.executable, SCRIPT], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_cli_fails_on_missing(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump({"sable": {"mod_ids": ["sable"]}, "optional_mods": [{"name": "A"}]}, f)
        r = subprocess.run([sys.executable, SCRIPT, "--config", f.name], capture_output=True, text=True)
        os.unlink(f.name)
        self.assertEqual(r.returncode, 1)
        self.assertIn("no mod_ids", r.stderr)


if __name__ == "__main__":
    unittest.main()
