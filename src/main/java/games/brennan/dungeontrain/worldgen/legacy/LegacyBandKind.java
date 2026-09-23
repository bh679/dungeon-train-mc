package games.brennan.dungeontrain.worldgen.legacy;

import java.util.Locale;

/**
 * The old Minecraft world generators that each get a <b>legacy band</b> — a stretch of the repeating
 * {@link games.brennan.dungeontrain.worldgen.WorldGenCycle} (after the stacks band) where the terrain is
 * produced by a port of that version's generator instead of vanilla's.
 *
 * <p>Declaration order is cycle order: the bands run newest-to-oldest, "back in time", so a generator
 * added later is inserted at its era's position, not appended. The full order once every era ships:
 * Large Biomes → Amplified → Beta 1.7.3 → Far Lands → Skylands → Alpha 1.1.2 → Infdev → Indev floating →
 * Classic → Void. Large Biomes and Amplified are not built yet.</p>
 */
public enum LegacyBandKind {
    /** Beta 1.7.3 — climate-driven terrain, sand/gravel beaches, overhangs, Beta caves and decoration. */
    BETA(false),
    /**
     * The Far Lands — Beta's terrain out past ±12,550,824 blocks, where the limit noise's 32-bit floor
     * saturates and the land breaks into walls, tunnels and a canyon.
     */
    FAR_LANDS(false),
    /** Beta 1.7.3's unused Sky dimension — floating grass-and-dirt islands over open void, no sea or bedrock. */
    SKYLANDS(true),
    /**
     * Alpha 1.1.2 — pre-biome terrain, forest everywhere, Alpha beaches; the back half of the band is
     * Alpha's winter mode (frozen sea, snow over everything) — see {@code LegacyBands#isAlphaWinter}.
     */
    ALPHA(false),
    /**
     * Infdev — steps through the 20100227, 0415, 0420 and 0611 snapshots across the band (227's brick
     * pyramids and obsidian walls first, then the density terrain of the later three).
     */
    INFDEV(false),
    /** Indev's Floating level type — stacked layers of islands over void, one finite level per tile. */
    FLOATING(true),
    /** Classic 0.30 — finite 256 × 256 levels, tiled edge to edge along the band. */
    CLASSIC(false),
    /**
     * Nothing at all — an empty stretch after the oldest generator: no terrain, no floor, just the track
     * over open void. Reuses the void-below plumbing (no fill, no bedrock, fluid veto).
     */
    VOID(true);

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

    /** Lower-cased config / command token ({@code beta}, {@code far_lands}). */
    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Camel-cased config-key stem ({@code Beta}, {@code FarLands}). */
    public String configStem() {
        StringBuilder out = new StringBuilder();
        for (String part : token().split("_")) {
            if (part.isEmpty()) continue;
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
