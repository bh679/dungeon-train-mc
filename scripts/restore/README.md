# Admin inventory restore

Admin-only, never advertised. Used when Brennan has seen a bug cost a player their items and has
approved giving them back. The player gets a **one-time** restore of inventory, armour and offhand
on their next login to a run that is not Free Play.

## 1. Get the player's save data

Single-player host: `.minecraft/saves/<world>/level.dat` (the host's items are under
`Data.Player`). LAN/server guest: `<world>/playerdata/<uuid>.dat`. `level.dat_old` is the save
before that, which is useful when the newest one already lost the items.

The Ender Chest is not restored here: EnderChestPersistence keeps the survival chest in
`%USERPROFILE%\AppData\Roaming\enderchestpersistence\<uuid>.dat` (macOS:
`~/Library/Application Support/enderchestpersistence/`). A Free Play run only hides it, and any
clean run shows it again.

## 2. Decode it into a snapshot

```bash
python3 -I scripts/restore/decode-player-items.py --uuid <uuid> --name <name> \
  --level level.dat --note "why" --out snapshot.json
```

Add `--with-run` to also return the player to the depth they had reached: the snapshot gains
a `run` block (the save's world-X, its `dungeontrain:player_run_state` as SNBT, Long Way Back's
furthest carriage) and `xpLevels`. Use it when a crash or bug ended a run the player wants back,
and they are starting a new world. The script refuses a save from outside the overworld.

This prints a readable list (slot, count, id, enchantments, names) and writes a JSON snapshot
(`schemaVersion` 1.0). Each stack keeps its full NBT as SNBT under `nbt`, which is what the mod
restores from. `id`/`count`/`summary` are only for people. Needs `pip install nbtlib`.

## 3. Queue it

Use the relay explorer → player page → **Inventory restore** panel: paste the snapshot, check the
preview, and queue it (live pool, or dev for testing). Exactly one restore can be pending per player.

## What the mod does

`player/InventoryRestoreEvents` asks the relay once per login (a restore queued while the player is
online arrives at their next login), then applies it once they have been in the world 30 seconds:

- **Free Play run:** nothing is applied, and the player sees one line saying items are waiting.
  The restore stays pending. `RUN_CHEATED` is never touched.
- Each stack goes back into its own slot. Anything the player was holding in those slots is
  packed into a **Before restore** chest item, so nothing is deleted.
- The id is recorded in `dungeontrain/outbox/restores-applied.txt`, and the relay is acked, so it
  never applies twice.
- Log line: `[DungeonTrain] Inventory restore <id> applied for <name>`.
- **With a `run` block:** after the items, the player's run stats are set from the snapshot and
  they are moved onto a fresh train at its world-X (the `/dtp` move, without a command, so the
  run stays clean). Difficulty follows from the restored stats. It only happens when they are
  alone, alive, in the overworld of a train world; otherwise the whole restore waits for a later
  login. The ack carries `relocatedX`, and the log says
  `[DungeonTrain] Run restore for <name>: x=… carriage=…`.
