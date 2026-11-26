package games.brennan.dungeontrain.perks.aura;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;
import java.util.Set;

/**
 * One aura perk: which effects it can draw ({@code firstPool} for the first draw, {@code pool} for the
 * rest), which ignore levels, its ladder, how long each application lasts and when it is topped up, and
 * who it lands on. {@code langGroup} picks the chip lines, {@code gui.dungeontrain.perks.aura.<group>.*}
 * (hint, new, all_new, all, level), and {@code chipColour} the chip border.
 *
 * <p>Built for Lover's Aura; Knight's Debuff Aura is meant to register its own with a different pool,
 * ladder and target test.</p>
 */
public record AuraDef(String perkId, List<ResourceLocation> pool, List<ResourceLocation> firstPool,
                      Set<ResourceLocation> noLevels, AuraLadder ladder, int durationTicks, int topUpTicks,
                      boolean particles, Targets targets, String langGroup, int chipColour) {

    /** Who an aura's owner can give its effects to. Server side only. */
    @FunctionalInterface
    public interface Targets {
        boolean test(ServerPlayer owner, LivingEntity entity);
    }

    public AuraDef {
        pool = List.copyOf(pool);
        firstPool = List.copyOf(firstPool);
        noLevels = Set.copyOf(noLevels);
    }

    /** Effects the first draw may be: {@code firstPool} when set, else the whole pool. */
    public List<ResourceLocation> firstCandidates() {
        return firstPool.isEmpty() ? pool : firstPool;
    }
}
