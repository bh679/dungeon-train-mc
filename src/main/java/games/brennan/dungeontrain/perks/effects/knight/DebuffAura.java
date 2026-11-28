package games.brennan.dungeontrain.perks.effects.knight;

import games.brennan.dungeontrain.perks.BuiltPerks;
import games.brennan.dungeontrain.perks.aura.AuraDef;
import games.brennan.dungeontrain.perks.aura.AuraLadder;
import games.brennan.dungeontrain.perks.aura.Auras;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.Tags;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Knight tier 2 perk "Debuff Aura" (Brennan, 2026-10-29): the Lover's Aura turned on enemies, run by
 * the shared {@code perks/aura} machinery with its own pool, ladder and targets.
 *
 * <p>Hostile mobs within a debuff's range get it, topped up every second with a 6 s tail. PlayerMobs
 * and neutral mobs only while aggressive toward the Knight; bosses never. Pool: Weakness, Slowness,
 * Poison, Wither, Glowing, drawn per life. Poison and Wither stop at half a heart ({@link DebuffAuraEvents}).</p>
 *
 * <p>Ladder ({@link #shape}): the first debuff enters at 1 block and grows to 4. After that each new
 * debuff enters at 1 and gains a block a point until it matches the others, then one point adds a
 * block to all, then the next debuff enters. With all five owned, +1 to all alternates with a level
 * round. No cap.</p>
 */
public final class DebuffAura {

    public static final String ID = "debuff-aura";

    public static final List<ResourceLocation> POOL = List.of(
        mc("weakness"), mc("slowness"), mc("poison"), mc("wither"), mc("glowing"));
    /** Glowing's amplifier changes nothing, so level rounds skip it. */
    public static final Set<ResourceLocation> NO_LEVELS = Set.of(mc("glowing"));

    static final int POOL_SIZE = 5;
    /** Range the first debuff grows to before the second enters. */
    static final int FIRST_GROWS_TO = 4;
    /** Seven seconds a copy, topped up in its last second: it fades about six seconds after a mob leaves. */
    static final int DURATION_TICKS = 140;
    static final int TOP_UP_TICKS = 20;
    static final int CHIP_COLOUR = 0xD95B5B;

    public static final AuraLadder LADDER = DebuffAura::shape;

    private DebuffAura() {}

    /** Marks Debuff Aura built and registers its aura; its chips come from the aura, not stats. */
    static void declare() {
        BuiltPerks.markBuilt(ID);
        Auras.register(def());
    }

    public static AuraDef def() {
        return new AuraDef(ID, POOL, List.of(), NO_LEVELS, LADDER, DURATION_TICKS, TOP_UP_TICKS,
            true, DebuffAura::isTarget, "debuff", CHIP_COLOUR);
    }

    /** The ranges (oldest debuff first) and level rounds at {@code rank}, played forward point by point. */
    public static AuraLadder.Shape shape(int rank) {
        if (rank <= 0) return AuraLadder.Shape.NONE;
        List<Integer> ranges = new ArrayList<>();
        int levelUps = 0;
        boolean lastWasAll = false;
        for (int p = 1; p <= rank; p++) {
            int count = ranges.size();
            if (count == 0) {
                ranges.add(1);
            } else if (count == 1 && ranges.get(0) < FIRST_GROWS_TO) {
                ranges.set(0, ranges.get(0) + 1);
            } else if (count > 1 && ranges.get(count - 1) < ranges.get(0)) {
                ranges.set(count - 1, ranges.get(count - 1) + 1);
            } else if (count < POOL_SIZE && (count == 1 || lastWasAll)) {
                ranges.add(1);
                lastWasAll = false;
            } else if (count == POOL_SIZE && lastWasAll) {
                levelUps++;
                lastWasAll = false;
            } else {
                ranges.replaceAll(r -> r + 1);
                lastWasAll = true;
            }
        }
        return new AuraLadder.Shape(ranges.stream().mapToInt(Integer::intValue).toArray(), levelUps);
    }

    /**
     * Who the Knight's debuffs land on: hostile mobs; PlayerMobs and neutral mobs (spiders and piglins
     * included) only while they are aggressive toward the Knight. Never a player, a pet, or a boss.
     */
    static boolean isTarget(ServerPlayer owner, LivingEntity e) {
        if (e == owner || !e.isAlive() || e.isSpectator() || e instanceof Player) return false;
        if (isBoss(e.getType())) return false;
        if (e instanceof OwnableEntity pet && pet.getOwnerUUID() != null) return false;
        if (e instanceof PlayerMobEntity || e instanceof NeutralMob || NATURALLY_NEUTRAL.contains(e.getType())) {
            return isAggressiveToward(e, owner);
        }
        return e instanceof Enemy;
    }

    /** Hostile by class but calm until provoked (spiders in daylight, piglins when you wear gold). */
    private static final Set<EntityType<?>> NATURALLY_NEUTRAL =
        Set.of(EntityType.SPIDER, EntityType.CAVE_SPIDER, EntityType.PIGLIN);

    /** Targeting the Knight, or a neutral mob angry at them. */
    static boolean isAggressiveToward(LivingEntity e, ServerPlayer owner) {
        if (e instanceof Mob mob && mob.getTarget() == owner) return true;
        return e instanceof NeutralMob neutral && neutral.isAngryAt(owner);
    }

    static boolean isBoss(EntityType<?> type) {
        return type == EntityType.WITHER || type == EntityType.ENDER_DRAGON || type == EntityType.WARDEN
            || type.is(Tags.EntityTypes.BOSSES);
    }

    private static ResourceLocation mc(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }
}
