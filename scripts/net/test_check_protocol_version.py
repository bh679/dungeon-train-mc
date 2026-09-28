#!/usr/bin/env python3
"""Unit tests for check-protocol-version.py — the missed-PROTOCOL_VERSION-bump guard.

Fixture diffs exercise the pure `check()` verdict (no git): each codec idiom used under net/ must
be flagged, while handler-only edits and declaration lines must not. Two regression tests then run
the CLI over real history — the PR #1449 range that shipped a layout change without a bump must
fail, and the post-#1456 range must pass. They skip when the commits are absent (shallow clone).

Run: python3 scripts/net/test_check_protocol_version.py   (or via pytest)
"""
import importlib.util
import os
import subprocess
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "check-protocol-version.py")
REPO_ROOT = os.path.dirname(os.path.dirname(HERE))

_spec = importlib.util.spec_from_file_location("check_protocol_version", SCRIPT)
cpv = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cpv)

NET = cpv.NET_DIR
PACKET = NET + "EchoPhotoPacket.java"


def file_diff(path: str, *changed: str) -> str:
    """A minimal `git diff -U0` block for one file; each changed line carries its +/- prefix."""
    return "\n".join([
        f"diff --git a/{path} b/{path}",
        f"--- a/{path}",
        f"+++ b/{path}",
        "@@ -30,0 +31,1 @@",
        *changed,
    ]) + "\n"


BUMP = file_diff(
    cpv.REGISTRAR,
    '-    public static final String PROTOCOL_VERSION = "72";',
    '+    public static final String PROTOCOL_VERSION = "73";',
)


class WireLineDetection(unittest.TestCase):
    def assert_flagged(self, *lines: str) -> None:
        hits, bumped = cpv.check(file_diff(PACKET, *lines))
        self.assertIn(PACKET, hits, f"expected a wire hit for {lines}")
        self.assertFalse(bumped)

    def assert_clean(self, *lines: str) -> None:
        hits, _ = cpv.check(file_diff(PACKET, *lines))
        self.assertEqual(hits, {}, f"unexpected wire hit for {lines}")

    def test_composite_argument_addition_with_stream_codec(self):
        self.assert_flagged("+            UUIDUtil.STREAM_CODEC, EchoPhotoPacket::ownerId,")

    def test_composite_argument_addition_with_uppercase_bytebufcodecs(self):
        self.assert_flagged("+            ByteBufCodecs.VAR_INT, CaptureEchoPacket::slot,")

    def test_lowercase_bytebufcodecs_factories(self):
        self.assert_flagged("+            ByteBufCodecs.byteArray(1024 * 1024), DeathPhotoPacket::thumb,")
        self.assert_flagged("-        ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()), P::lost,")
        self.assert_flagged("+            ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), P::note,")

    def test_stream_codec_encode_decode(self):
        self.assert_flagged("+            ComponentSerialization.STREAM_CODEC.encode(buf, cause.detail());")

    def test_buf_and_lambda_receiver_reads(self):
        self.assert_flagged("+        boolean centre = buf.readBoolean();")
        self.assert_flagged("+                            b -> b.readUtf(32)),")

    def test_stream_codec_factories(self):
        self.assert_flagged("-        StreamCodec.unit(new CinematicDonePacket());")
        self.assert_flagged("+        StreamCodec.composite(")
        self.assert_flagged("+        StreamCodec.of(EchoPhotoPacket::write, EchoPhotoPacket::read);")

    def test_handler_only_edit_passes(self):
        self.assert_clean(
            "+        ctx.enqueueWork(() -> LifeDisqualificationClient.apply(packet.disqualified()));",
            "-            .map(c -> c == PlotCategory.WHOLE_GROUP).orElse(false);",
            "+    // Encodes the photo bytes; see STREAM_CODEC below.",
            "+        return TYPE;",
        )

    def test_declaration_line_alone_passes(self):
        self.assert_clean("+    public static final StreamCodec<FriendlyByteBuf, EchoPhotoPacket> STREAM_CODEC =")

    def test_registrar_references_are_not_packet_hits(self):
        hits, _ = cpv.check(file_diff(
            cpv.REGISTRAR,
            "+        registrar.playToClient(EchoPhotoPacket.TYPE, EchoPhotoPacket.STREAM_CODEC, EchoPhotoPacket::handle);",
        ))
        self.assertEqual(hits, {})

    def test_bump_satisfies_flagged_change(self):
        hits, bumped = cpv.check(file_diff(PACKET, "+            UUIDUtil.STREAM_CODEC, EchoPhotoPacket::ownerId,") + BUMP)
        self.assertIn(PACKET, hits)
        self.assertTrue(bumped)


def commit_exists(ref: str) -> bool:
    return subprocess.run(
        ["git", "cat-file", "-e", f"{ref}^{{commit}}"], cwd=REPO_ROOT, capture_output=True
    ).returncode == 0


def run_cli(base: str, head: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, SCRIPT, "--base", base, "--head", head],
        cwd=REPO_ROOT, capture_output=True, text=True,
    )


PR_1449 = "3c6d7ec4f"          # trailing `boolean centre` on EditorPlotActionPacket, no bump
POST_1456_BASE = "0071c9572"   # main just before #1456 landed its bump + this check


class HistoryRegression(unittest.TestCase):
    @unittest.skipUnless(commit_exists(PR_1449), "PR #1449 commit not in this clone")
    def test_pr_1449_missed_bump_fails(self):
        result = run_cli(f"{PR_1449}~1", PR_1449)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("EditorPlotActionPacket.java", result.stdout)

    @unittest.skipUnless(commit_exists(POST_1456_BASE), "base commit not in this clone")
    def test_post_1456_history_passes(self):
        result = run_cli(POST_1456_BASE, "origin/main" if commit_exists("origin/main") else "HEAD")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
