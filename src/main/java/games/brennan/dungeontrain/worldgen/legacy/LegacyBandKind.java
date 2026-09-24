package games.brennan.dungeontrain.worldgen.legacy;

import java.util.Locale;

/**
 * The old Minecraft world generators that each get a <b>legacy band</b> — a stretch of the repeating
 * {@link games.brennan.dungeontrain.worldgen.WorldGenCycle} (after the stacks band) where the terrain is
 * produced by a port of that version's generator instead of vanilla's.
 *
 * <p>Declaration order is cycle order: the bands run newest-to-oldest, "back in time", so a generator
 * added later is inserted at its era's position, not appended. The full order once every era ships:
 * Large Biomes → Amplified → Beta 1.7.3 → Far Lands → Caves of Chaos → Skylands → Alpha 1.1.2 → Infdev →
 * Indev floating → Classic → Void. Large Biomes and Amplified are <em>modern presets</em> rather than ports — vanilla's own
 * router with the preset flag flipped ({@link games.brennan.dungeontrain.worldgen.legacy.preset.PresetTerrain}); they
 * run vanilla's surface, carvers and decoration ({@link #isPreset}). Large Biomes is built but <em>not shipped</em>:
 * off by default and absent from {@code CycleLayout#DEFAULT_ORDER} (opt in with {@code legacyLargeBiomesEnabled}
 * plus a {@code legacy:large_biomes=…} era in {@code worldgenCycleOrder}). (Caves of Chaos is a Beta-family
 * oddity like the Far Lands, so it rides with them rather than in its Release-era slot.)</p>
 */
public enum LegacyBandKind {
    /** Vanilla's Large Biomes preset — the same terrain with every climate region four times as wide. */
    LARGE_BIOMES(false),
    /** Vanilla's Amplified preset — the overworld's relief stretched into towering peaks and cliffs. */
    AMPLIFIED(false),
    /** Beta 1.7.3 — climate-driven terrain, sand/gravel beaches, overhangs, Beta caves and decoration. */
    BETA(false),
    /**
     * The Far Lands — Beta's terrain out past ±12,550,824 blocks, where the limit noise's 32-bit floor
     * saturates and the land breaks into walls, tunnels and a canyon.
     */
    FAR_LANDS(false),
    /**
     * Caves of Chaos — the 1.8–1.12 "Customized" preset on the Beta pipeline: a 256-block column of cavernous
     * stone and towering overhangs over open void — no sea, no bedrock ({@code BetaTerrain.Profile#CAVES_OF_CHAOS}).
     */
    CAVES_OF_CHAOS(true),
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

    /**
     * True for a modern-preset band (Large Biomes, Amplified): terrain from vanilla's router with a preset
     * flag, so vanilla's surface, carvers and decoration run in its chunks instead of an old generator's.
     */
    public boolean isPreset() {
        return this == LARGE_BIOMES || this == AMPLIFIED;
    }

    /** Lower-cased config / command token ({@code beta}, {@code far_lands}, {@code caves_of_chaos}). */
    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Camel-cased config-key stem ({@code Beta}, {@code FarLands}, {@code CavesOfChaos}). */
    public String configStem() {
        StringBuilder out = new StringBuilder();
        for (String part : token().split("_")) {
            if (part.isEmpty()) continue;
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
