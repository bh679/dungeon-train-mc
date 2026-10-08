"""Which advancements count towards the Everything Burrito, and which "It's Not That Simple" resets.

Python mirror of the mod's default rule, so the editor can show it and know when an edit is an
override. The Java is the authority:

* required — ``CompletionistAdvancement.isRequiredId``
* reset    — ``StartAgainAdvancement.isWiped`` (by default: exactly the required set)

Overrides live in ``advancement_tabs.json`` (``burrito`` / ``startAgainReset``: id → bool) and are
read by both. ``test_apply.py`` checks the ``BOOK_PATHS`` parse; keep the rest in step with the Java by hand.
"""

from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
ENCHIRIDION_JAVA = REPO / "src/main/java/games/brennan/dungeontrain/advancement/EnchiridionAdvancements.java"

COMPLETIONIST = "dungeontrain:dungeon_train/completionist"
START_AGAIN = "dungeontrain:dungeon_train/start_again"
#: Never editable: the capstone, its reward, and the creative-only editor tree.
FIXED = {COMPLETIONIST, START_AGAIN}


def book_paths(java: Path = ENCHIRIDION_JAVA) -> set[str]:
    """``EnchiridionAdvancements.BOOK_PATHS``, read from the source so the two can't drift."""
    src = java.read_text()
    block = re.search(r"BOOK_PATHS\s*=\s*Set\.of\((.*?)\);", src, re.S)
    if not block:
        raise SystemExit(f"BOOK_PATHS not found in {java}")
    paths = set(re.findall(r'"([a-z0-9_/]+)"', block.group(1)))
    if "ROOT" in block.group(1):
        root = re.search(r'String ROOT\s*=\s*"([^"]+)"', src)
        if root:
            paths.add(root.group(1))
    return paths


def linked(tabs: dict) -> dict[str, str]:
    """Every advancement that follows another — tab copies and ``unlockedBy`` heads — to its source."""
    return {**tabs.get("copies", {}), **tabs.get("unlockedBy", {})}


def is_editable(adv_id: str, copies: dict[str, str]) -> bool:
    """Can the editor change this one's flags? Not vanilla, the editor tree, the capstone pair or a tab copy."""
    return (adv_id.startswith("dungeontrain:") and not adv_id.startswith("dungeontrain:editor/")
            and adv_id not in FIXED and adv_id not in copies)


def default_required(adv_id: str, copies: dict[str, str], books: set[str]) -> bool:
    if not adv_id.startswith("dungeontrain:"):
        return False
    path = adv_id.split(":", 1)[1]
    if path.startswith("editor/") or adv_id in FIXED or adv_id in copies:
        return False
    if path.startswith("secrete_menu/") or path == "dungeon_train/secrete_menu":
        return False  # BandAdvancements.isBackwards
    if path.startswith("enchiridion/") or path in books:
        return False  # EnchiridionAdvancements.isEnchiridion
    return True


def effective(adv_id: str, copies: dict[str, str], books: set[str], tabs: dict) -> tuple[bool, bool]:
    """``copies`` here means every linked advancement — see :func:`linked`."""
    """(counts towards the burrito, reset by start-again) with the tabs file's overrides applied."""
    required = default_required(adv_id, copies, books)
    if is_editable(adv_id, copies):
        required = tabs.get("burrito", {}).get(adv_id, required)
    reset = required or adv_id in FIXED
    if is_editable(adv_id, copies):
        reset = tabs.get("startAgainReset", {}).get(adv_id, reset)
    return required, reset
