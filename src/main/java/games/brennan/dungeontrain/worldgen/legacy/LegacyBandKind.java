package games.brennan.dungeontrain.worldgen.legacy;

import java.util.Locale;

/**
 * The old Minecraft world generators that each get a <b>legacy band</b> — a stretch of the repeating
 * {@link games.brennan.dungeontrain.worldgen.WorldGenCycle} (after the stacks band) where the terrain is
 * produced by a port of that version's generator instead of vanilla's.
 *
 * <p>Declaration order is cycle order: the bands run newest-to-oldest, "back in time", so a generator
 * added later is inserted at its era's position, not appended. Planned order once every era ships:
 * Large Biomes → Amplified → Beta 1.7.3 → Skylands → Alpha 1.1.2 → Infdev → Indev floating → Classic →
 * Far Lands.</p>
 */
public enum LegacyBandKind {
    /** Beta 1.7.3 — climate-driven terrain, sand/gravel beaches, overhangs, Beta caves and decoration. */
    BETA(false),
    /** Beta 1.7.3's unused Sky dimension — floating grass-and-dirt islands over open void, no sea or bedrock. */
    SKYLANDS(true);

    private final boolean voidBelow;

    LegacyBandKind(boolean voidBelow) {
        this.voidBelow = voidBelow;
    }

    /**
     * True for a generator whose world is open void under its terrain: its chunks get no stone fill below
     * the old column, no DT bedrock floor, and liquids may not spill into the empty space under the land.
     */
    public boolean voidBelow() {
        return voidBelow;
    }

    /** Lower-cased config / command token ({@code beta}). */
    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }
}
