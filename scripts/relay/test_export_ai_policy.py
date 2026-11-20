#!/usr/bin/env python3
"""Unit tests for export-ai-policy.py (the in-game AI Policy -> the website's policy.json).

Runnable directly or by pytest. Runs in CI via build.yml's ai-policy step.
"""
import importlib.util
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("export_ai_policy", os.path.join(HERE, "export-ai-policy.py"))
ex = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ex)

P = ex.PREFIX
CONTENT = '''
    private static final List<Section> SECTIONS = List.of(
            Section.list("gui.dungeontrain.ai_policy.not_ai.header", ACCENT_NOT_AI, List.of(
                    new Bullet("gui.dungeontrain.ai_policy.not_ai.6", Items.NAME_TAG),
                    new Bullet("gui.dungeontrain.ai_policy.not_ai.1", Items.WRITTEN_BOOK))),
            Section.list("gui.dungeontrain.ai_policy.used.header", ACCENT_USED, List.of(
                    // a comment with "quotes" in it
                    new Bullet("gui.dungeontrain.ai_policy.used.3", Items.PAPER))),
            Section.paragraph("gui.dungeontrain.ai_policy.which.header", ACCENT_WHICH,
                    "gui.dungeontrain.ai_policy.which.body"));

    private static final List<Explainer> EXPLAINERS = List.of(
            new Explainer("gui.dungeontrain.ai_policy.explainers.names.title",
                    "gui.dungeontrain.ai_policy.explainers.names.desc",
                    "https://dungeon.training/blog/names-without-ai/", Items.NAME_TAG));
'''
SCREEN = 'private static final String UPDATES_URL =\n            "https://discord.example/updates";'
LANG = {
    P + "title": "AI Policy", P + "subtitle": "Sub.", P + "intro.1": "One.", P + "intro.2": "Two 100%%.",
    P + "not_ai.header": "Not AI", P + "not_ai.1": "Books.",
    P + "not_ai.6": "The naming system: names are generated from a program, not AI.",
    P + "used.header": "Used", P + "used.3": "Change logs (see %s).", P + "used.3.link": "#minor-updates",
    P + "which.header": "Which AI", P + "which.body": "Anthropic.",
    P + "explainers.header": "Explainers", P + "explainers.names.title": "Names",
    P + "explainers.names.desc": "How.", P + "closing": "Thanks.", P + "signature": "Brennan Hatton",
}


class ExportTest(unittest.TestCase):
    def test_order_kinds_and_text(self):
        out = ex.build(CONTENT, SCREEN, LANG)
        self.assertEqual([s["header"] for s in out["sections"]], ["Not AI", "Used", "Which AI"])
        self.assertEqual([s["kind"] for s in out["sections"]], ["list", "list", "paragraph"])
        # Bullets keep AiPolicyContent's order, not the key numbers.
        self.assertEqual(out["sections"][0]["items"][1], [{"text": "Books."}])
        self.assertEqual(out["sections"][2]["body"], [{"text": "Anthropic."}])
        self.assertEqual(out["intro"][1], [{"text": "Two 100%."}])
        self.assertEqual(out["explainers"], [{"title": "Names", "desc": "How.",
                                              "url": "https://dungeon.training/blog/names-without-ai/"}])

    def test_placeholder_becomes_link(self):
        used3 = ex.build(CONTENT, SCREEN, LANG)["sections"][1]["items"][0]
        self.assertEqual(used3, [{"text": "Change logs (see "},
                                 {"text": "#minor-updates", "href": "https://discord.example/updates"},
                                 {"text": ")."}])

    def test_web_only_link(self):
        line = ex.build(CONTENT, SCREEN, LANG)["sections"][0]["items"][0]
        self.assertEqual(line[1], {"text": "from a program",
                                   "href": "https://dungeon.training/blog/names-without-ai/"})
        self.assertEqual("".join(s["text"] for s in line), LANG[P + "not_ai.6"])

    def test_missing_key_fails(self):
        lang = dict(LANG)
        del lang[P + "not_ai.1"]
        with self.assertRaises(ex.ExportError):
            ex.build(CONTENT, SCREEN, lang)

    def test_lost_link_phrase_fails(self):
        lang = dict(LANG, **{P + "not_ai.6": "Names come from code."})
        with self.assertRaises(ex.ExportError):
            ex.build(CONTENT, SCREEN, lang)

    def test_real_files_export(self):
        out = ex.export()
        self.assertGreaterEqual(len(out["sections"]), 3)
        self.assertTrue(out["explainers"])


if __name__ == "__main__":
    unittest.main()
