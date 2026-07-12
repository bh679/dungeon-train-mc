package games.brennan.dungeontrain.client.snapshot;

/**
 * Context category for a ride snapshot. Drives both when a shot is taken
 * ({@link RideSnapshotDirector}) and which death-screen page it backs
 * ({@link DeathBackgroundPainter}).
 */
public enum SnapshotTag {
    /** Periodic wide "journey" shot — the baseline backdrop. */
    SCENIC,
    /** Taken while hostiles are near — backs the combat/deeds page. */
    COMBAT,
    /** Taken when the player equips new armour — backs the loadout page. */
    GEAR,
    /** Taken while reading a narrative book/lectern — a reflective shot. */
    LORE,
    /** Taken when trading with a villager or interacting with a player-mob — a social moment. */
    SOCIAL
}
