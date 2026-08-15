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
    SOCIAL,
    /**
     * Taken when the player changes a drifting carriage — leaving their marks on a community
     * build that will travel on to somebody else's train. Cued by the server (the registry of
     * drifting carriages is server-side); see {@code net.SnapshotCue#BUILDING}.
     */
    BUILDING,
    /**
     * Taken once the player is confirmed inside a Train Dimension — a hallway portal's room
     * body, not the corridor through it. Cued by the server; see {@code net.SnapshotCue#THRESHOLD}.
     */
    THRESHOLD
}
