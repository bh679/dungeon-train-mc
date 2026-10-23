#!/usr/bin/env python3
"""Decode a player's items from Minecraft 1.21.1 NBT into a restore snapshot.

Inputs (any mix):
  --level   level.dat            single-player host: Data.Player
  --player  playerdata/<uuid>.dat  a multiplayer / LAN guest
  --ecp     <uuid>.dat           EnderChestPersistence outside store (gzip NBT,
                                 root keys = chest slots, e.g. "survival", "creative")
  --backup  dungeontrain-backup-*.zip  DungeonBackup archive; its
                                 enderchestpersistence/<uuid>.dat is read like --ecp

Output: a JSON snapshot (schemaVersion 1.0) on --out, plus a readable list on stdout.
Every stack keeps its full NBT as SNBT ("nbt"), so a restore round-trips components,
enchantments, names and contents exactly; "id"/"count"/"summary" are for humans only.

Requires nbtlib (pip install nbtlib).
"""
import argparse
import datetime
import gzip
import io
import json
import sys
import zipfile

import nbtlib

ARMOR_SLOTS = {100: "feet", 101: "legs", 102: "chest", 103: "head"}
OFFHAND_SLOT = -106


def load_nbt_bytes(data):
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    return nbtlib.File.parse(io.BytesIO(data), byteorder="big")


def load_nbt(path):
    with open(path, "rb") as fh:
        return load_nbt_bytes(fh.read())


def summarise(stack):
    """One readable line: enchantments, custom name, stored contents count."""
    comps = stack.get("components", {})
    parts = []
    name = comps.get("minecraft:custom_name")
    if name is not None:
        parts.append(f"name={str(name)}")
    for key in ("minecraft:enchantments", "minecraft:stored_enchantments"):
        ench = comps.get(key)
        if ench is not None:
            levels = ench.get("levels", ench)
            parts.append(", ".join(f"{k} {int(v)}" for k, v in levels.items()))
    dmg = comps.get("minecraft:damage")
    if dmg is not None:
        parts.append(f"damage={int(dmg)}")
    container = comps.get("minecraft:container")
    if container is not None:
        parts.append(f"holds {len(container)} stack(s)")
    other = [k for k in comps.keys() if k not in (
        "minecraft:custom_name", "minecraft:enchantments", "minecraft:stored_enchantments",
        "minecraft:damage", "minecraft:container", "minecraft:repair_cost")]
    if other:
        parts.append("+" + ",".join(sorted(other)))
    return "; ".join(parts)


def entry(stack, slot):
    clean = nbtlib.Compound({k: v for k, v in stack.items() if k != "Slot"})
    return {
        "slot": slot,
        "id": str(clean.get("id", "?")),
        "count": int(clean.get("count", clean.get("Count", 1))),
        "summary": summarise(clean),
        "nbt": clean.snbt(),
    }


def split_player(player):
    inventory, armor, offhand = [], [], []
    for stack in player.get("Inventory", []):
        slot = int(stack.get("Slot", -1))
        if 0 <= slot <= 35:
            inventory.append(entry(stack, slot))
        elif slot in ARMOR_SLOTS:
            armor.append({**entry(stack, slot), "piece": ARMOR_SLOTS[slot]})
        elif slot == OFFHAND_SLOT:
            offhand.append(entry(stack, slot))
        else:
            print(f"warning: unknown inventory slot {slot} ({stack.get('id')})", file=sys.stderr)
    ender = [entry(s, int(s.get("Slot", -1))) for s in player.get("EnderItems", [])]
    return inventory, armor, offhand, ender


def ecp_slots(root):
    return {key: [entry(s, int(s.get("Slot", -1))) for s in items] for key, items in root.items()}


def ecp_from_backup(path, uuid):
    with zipfile.ZipFile(path) as zf:
        names = [n for n in zf.namelist() if n.endswith(f"{uuid}.dat") and "enderchestpersistence" in n]
        if not names:
            sys.exit(f"{path}: no enderchestpersistence/{uuid}.dat inside")
        return load_nbt_bytes(zf.read(names[0])), names[0]


def print_list(title, items):
    print(f"\n== {title} ({len(items)}) ==")
    for it in sorted(items, key=lambda i: i["slot"]):
        extra = f"  [{it['summary']}]" if it["summary"] else ""
        print(f"  slot {it['slot']:>4}  {it['count']:>3} x {it['id']}{extra}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--uuid", required=True)
    ap.add_argument("--name", default="")
    ap.add_argument("--level")
    ap.add_argument("--player")
    ap.add_argument("--ecp")
    ap.add_argument("--backup")
    ap.add_argument("--ecp-slot", default="survival",
                    help="which ECP chest slot becomes enderChest in the snapshot (default survival)")
    ap.add_argument("--note", default="")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    snapshot = {
        "schemaVersion": "1.0",
        "player": {"uuid": args.uuid, "name": args.name},
        "createdAt": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "source": {},
        "note": args.note,
        "inventory": [], "armor": [], "offhand": [], "enderChest": [],
        "worldEnderChest": [], "ecpSlots": {},
    }

    if args.level or args.player:
        if args.level:
            player = load_nbt(args.level)["Data"]["Player"]
            snapshot["source"]["level"] = args.level
        else:
            player = load_nbt(args.player)
            snapshot["source"]["playerdata"] = args.player
        inv, armor, off, ender = split_player(player)
        snapshot.update(inventory=inv, armor=armor, offhand=off, worldEnderChest=ender)

    ecp_root = None
    if args.ecp:
        ecp_root = load_nbt(args.ecp)
        snapshot["source"]["ecp"] = args.ecp
    elif args.backup:
        ecp_root, inner = ecp_from_backup(args.backup, args.uuid)
        snapshot["source"]["ecp"] = f"{args.backup}!{inner}"
    if ecp_root is not None:
        snapshot["ecpSlots"] = ecp_slots(ecp_root)
        snapshot["enderChest"] = snapshot["ecpSlots"].get(args.ecp_slot, [])

    with open(args.out, "w") as fh:
        json.dump(snapshot, fh, indent=2)

    print(f"Snapshot for {args.name or args.uuid} -> {args.out}")
    print_list("inventory", snapshot["inventory"])
    print_list("armor", snapshot["armor"])
    print_list("offhand", snapshot["offhand"])
    print_list(f"ender chest (ECP '{args.ecp_slot}')", snapshot["enderChest"])
    for key, items in snapshot["ecpSlots"].items():
        if key != args.ecp_slot:
            print_list(f"ECP slot '{key}' (not restored)", items)
    if snapshot["worldEnderChest"]:
        print_list("world EnderItems (live chest at save time, not restored)", snapshot["worldEnderChest"])


if __name__ == "__main__":
    main()
