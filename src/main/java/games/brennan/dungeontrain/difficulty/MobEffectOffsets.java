package games.brennan.dungeontrain.difficulty;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;

import java.util.Map;

/**
 * How many difficulty levels later each hostile's potion buffs start, so a mob that only begins
 * spawning deep into the run is not buffed the moment it appears. The effect curve in
 * {@link ProceduralTiers} is read at {@code level - offset} for that mob (chance and amplifier cap
 * both shift); gear is not affected.
 *
 * <p>Hand-tuned, seeded from a one-off scan of the bundled carriage contents (weights, groups,
 * stage gates, variant {@code difficulty.min} and template-baked mobs): each value is the lowest
 * level at which any room can spawn that mob. Mobs missing here appear from level 0. Re-derive by
 * repeating that scan when rooms that gate a late mob change.</p>
 */
public final class MobEffectOffsets {

    /** Extra levels a baby mob waits for its buffs, on top of its type's offset. */
    public static final int BABY_OFFSET = 5;

    private static final Map<String, Integer> OFFSETS = Map.ofEntries(
            Map.entry("minecraft:pillager", 1),
            Map.entry("minecraft:bogged", 2),
            Map.entry("minecraft:cave_spider", 2),
            Map.entry("minecraft:witch", 3),
            Map.entry("minecraft:breeze", 4),
            Map.entry("minecraft:hoglin", 4),
            Map.entry("betternether:jungle_skeleton", 4),
            Map.entry("betternether:naga", 5),
            Map.entry("minecraft:vindicator", 5),
            Map.entry("minecraft:magma_cube", 6),
            Map.entry("minecraft:piglin_brute", 6),
            Map.entry("betterend:shadow_walker", 7),
            Map.entry("minecraft:guardian", 11),
            Map.entry("minecraft:shulker", 20),
            Map.entry("minecraft:elder_guardian", 30),
            Map.entry("minecraft:ravager", 40),
            Map.entry("minecraft:warden", 60)
    );

    private MobEffectOffsets() {}

    /** Buff offset for {@code mob}: its type's first-appearance level, plus {@link #BABY_OFFSET} for babies. */
    public static int offsetFor(Mob mob) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
        return offsetFor(id.toString(), mob.isBaby());
    }

    /** Pure form of {@link #offsetFor(Mob)}. Unknown ids get 0. */
    static int offsetFor(String entityId, boolean baby) {
        return OFFSETS.getOrDefault(entityId, 0) + (baby ? BABY_OFFSET : 0);
    }

    /** Test seam: the full table. */
    static Map<String, Integer> table() {
        return OFFSETS;
    }
}
