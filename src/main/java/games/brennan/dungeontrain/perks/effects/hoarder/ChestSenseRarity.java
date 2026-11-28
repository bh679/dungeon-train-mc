package games.brennan.dungeontrain.perks.effects.hoarder;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Chest Sense's rarity colours (Brennan, 2026-11-28): a sensed container glows in the colour of the
 * rarest item rolled into it, by that item's per-slot chance in its pool (entry weight over the pool's
 * total weight, the odds {@code ContainerContentsRoller} picks with).
 *
 * <p>Each band runs up to its top bound, so the gaps in the brief (0.2 to 0.5, 1 to 2, 4 to 5) close
 * upward. A container with no recorded chance (a vanilla loot table, or one rolled before this) is
 * {@link Tier#UNKNOWN} and keeps the old gold.</p>
 *
 * <p>The chance is stamped at roll time ({@link #withRarestPct}) under {@code NeoForgeData}, beside the
 * rolled-items fingerprint and outside {@code Items}, so the fingerprint is unaffected.</p>
 */
public final class ChestSenseRarity {

    /** Persistent-data key (inside {@code NeoForgeData}): the rarest rolled item's chance, in percent. */
    public static final String NBT_KEY = "dungeontrain:rarest_pct";
    private static final String NBT_NEOFORGE_DATA = "NeoForgeData";

    /** Rarest first. Colours are 0xRRGGBB. */
    public enum Tier {
        GREEN(0.2, 0x55FF55),
        PURPLE(1.0, 0xC060FF),
        LIGHT_BLUE(4.0, 0x6FD8FF),
        YELLOW(10.0, 0xFFE14D),
        IRON(20.0, 0xD8D8D8),
        STONE(50.0, 0x8A8A8A),
        WOOD(100.0, 0xA8783F),
        UNKNOWN(Double.NaN, 0xFFD140);

        private final double maxPct;
        private final int rgb;

        Tier(double maxPct, int rgb) {
            this.maxPct = maxPct;
            this.rgb = rgb;
        }

        public int rgb() {
            return rgb;
        }

        public int red() {
            return (rgb >> 16) & 0xFF;
        }

        public int green() {
            return (rgb >> 8) & 0xFF;
        }

        public int blue() {
            return rgb & 0xFF;
        }

        private static final Tier[] VALUES = values();

        /** The tier at {@code ordinal}, or {@link #UNKNOWN} for one out of range (a newer server's). */
        public static Tier byOrdinal(int ordinal) {
            return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : UNKNOWN;
        }
    }

    /** Slack on each band's top bound: the chance is stored as a float, and 0.2f reads back a hair over 0.2. */
    private static final double EDGE_EPSILON = 1e-4;

    private ChestSenseRarity() {}

    /** The band for a chance in percent; {@link Tier#UNKNOWN} for a negative or non-number. */
    public static Tier tierFor(double pct) {
        if (Double.isNaN(pct) || pct < 0) return Tier.UNKNOWN;
        for (Tier t : Tier.values()) {
            if (t != Tier.UNKNOWN && pct <= t.maxPct + EDGE_EPSILON) return t;
        }
        return Tier.WOOD;
    }

    /** One entry's per-slot chance in percent. */
    public static double pctOf(int weight, int totalWeight) {
        if (totalWeight <= 0 || weight <= 0) return Double.NaN;
        return 100.0 * weight / totalWeight;
    }

    /** A copy of rolled block-entity NBT with the rarest chance recorded; {@code beNbt} itself when there is none. */
    public static CompoundTag withRarestPct(CompoundTag beNbt, double pct) {
        if (beNbt == null || Double.isNaN(pct)) return beNbt;
        CompoundTag out = beNbt.copy();
        CompoundTag data = out.contains(NBT_NEOFORGE_DATA, Tag.TAG_COMPOUND)
            ? out.getCompound(NBT_NEOFORGE_DATA).copy()
            : new CompoundTag();
        data.putFloat(NBT_KEY, (float) pct);
        out.put(NBT_NEOFORGE_DATA, data);
        return out;
    }

    /** The tier recorded on a placed container, or {@link Tier#UNKNOWN}. */
    public static Tier tierOf(BlockEntity be) {
        CompoundTag data = be.getPersistentData();
        if (!data.contains(NBT_KEY, Tag.TAG_FLOAT)) return Tier.UNKNOWN;
        return tierFor(data.getFloat(NBT_KEY));
    }
}
