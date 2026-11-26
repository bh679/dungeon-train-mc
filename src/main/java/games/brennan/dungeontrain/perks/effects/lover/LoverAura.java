package games.brennan.dungeontrain.perks.effects.lover;

import games.brennan.dungeontrain.perks.aura.AuraDef;
import games.brennan.dungeontrain.perks.aura.AuraLadder;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Set;

/**
 * Lover's Aura (Brennan, 2026-10-29): friends near the Lover get buffs, each buff with its own range.
 *
 * <p>Ladder: the first buff grows 2 to 5 blocks over points 1 to 4. A new buff enters at 2 and grows a
 * block on each of the next two points; on the point after, it jumps to 5, every older buff gains a block
 * and the next buff enters at 2 (every third point from 8). The ranges stay one block apart, oldest
 * widest. With all ten drawn (point 29) and the tenth at 5 (point 32), points alternate: +1 block to
 * all, then a level round. No cap.</p>
 */
public final class LoverAura {

    public static final int POOL_SIZE = 10;
    private static final int FULL = 32;

    public static final List<ResourceLocation> POOL = List.of(
        mc("regeneration"), mc("speed"), mc("resistance"), mc("strength"), mc("haste"),
        mc("jump_boost"), mc("fire_resistance"), mc("absorption"), mc("slow_falling"), mc("water_breathing"));
    /** The first buff drawn is always one of these. */
    public static final List<ResourceLocation> FIRST = List.of(mc("strength"), mc("resistance"), mc("absorption"));
    /** Their amplifier changes nothing, so level rounds skip them. */
    public static final Set<ResourceLocation> NO_LEVELS = Set.of(mc("fire_resistance"), mc("water_breathing"));

    /** Six seconds a copy; topped up in its last second, so it fades about five seconds after a friend leaves. */
    static final int DURATION_TICKS = 120;
    static final int TOP_UP_TICKS = 20;
    static final int CHIP_COLOUR = 0x5FBF7F;

    private LoverAura() {}

    public static final AuraLadder LADDER = LoverAura::shape;

    public static AuraDef def() {
        return new AuraDef(LoverPerkIds.AURA, POOL, FIRST, NO_LEVELS, LADDER, DURATION_TICKS, TOP_UP_TICKS,
            false, LoverFriends::isFriend, "buff", CHIP_COLOUR);
    }

    /** The shape at {@code rank}: one range per buff (oldest first) and the level rounds so far. */
    public static AuraLadder.Shape shape(int rank) {
        if (rank <= 0) return AuraLadder.Shape.NONE;
        if (rank <= 4) return new AuraLadder.Shape(new int[] {1 + rank}, 0);
        if (rank < FULL) {
            int stage = (rank - 5) / 3, step = (rank - 5) % 3;
            int count = Math.min(POOL_SIZE, stage + 2);
            int[] ranges = new int[count];
            for (int i = 0; i < count; i++) {
                ranges[i] = i == stage + 1 ? 2 + step : 5 + (stage - i);
            }
            return new AuraLadder.Shape(ranges, 0);
        }
        int past = rank - FULL;
        int plus = (past + 1) / 2, levelUps = past / 2;
        int[] ranges = new int[POOL_SIZE];
        for (int i = 0; i < POOL_SIZE; i++) ranges[i] = 5 + (POOL_SIZE - 1 - i) + plus;
        return new AuraLadder.Shape(ranges, levelUps);
    }

    private static ResourceLocation mc(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }
}
