package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.compat.EchoIdentity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.warden.WardenAi;

import java.util.List;
import java.util.UUID;

/**
 * Reads what was happening between the creatures in a photo — a fight, a pet, a courtship — off the
 * live entities in frame, for The Enchiridion's moment advancements ({@link PhotoSubjects.Scene}) —
 * and a Warden caught half out of the ground.
 * The photographer is never part of the scene.
 */
final class PhotoScene {

    /** How recently one creature must have hurt another for the two to still count as fighting. */
    static final int RECENT_HURT_TICKS = 60;

    /**
     * The stretch of a Warden's dig-out that reads as "half out of the ground", in ticks since it
     * began (vanilla's emerge runs {@link WardenAi#EMERGE_DURATION}). Picked from frame-by-frame
     * captures: before tick 20 only dirt and a hand show; after tick 80 it is standing up out of the hole.
     */
    static final int HALF_OUT_FROM_TICK = 20;
    static final int HALF_OUT_TO_TICK = 80;

    private PhotoScene() {}

    static PhotoSubjects.Scene of(ServerPlayer photographer, List<LivingEntity> inFrame) {
        List<LivingEntity> others = inFrame.stream().filter(e -> e != photographer).toList();
        boolean ownPet = false, echoPet = false, wardenEmerging = false;
        int inLove = 0;
        for (LivingEntity e : others) {
            UUID owner = e instanceof OwnableEntity ownable ? ownable.getOwnerUUID() : null;
            if (owner != null) {
                if (owner.equals(photographer.getUUID())) ownPet = true;
                else if (isEcho(photographer.serverLevel(), owner)) echoPet = true;
            }
            if (e instanceof Animal animal && animal.isInLove()) inLove++;
            if (e instanceof Warden warden && warden.hasPose(Pose.EMERGING) && halfOut(emergeTicks(warden))) wardenEmerging = true;
        }
        return new PhotoSubjects.Scene(fighting(others), ownPet, echoPet, inLove >= 2, wardenEmerging);
    }

    /** Ticks since this Warden began digging out: its {@code IS_EMERGING} memory counts down from the full duration. */
    private static long emergeTicks(Warden warden) {
        return WardenAi.EMERGE_DURATION - warden.getBrain().getTimeUntilExpiry(MemoryModuleType.IS_EMERGING);
    }

    /** Pure, for the tests: is this far into the dig-out "half out of the ground"? */
    static boolean halfOut(long emergeTicks) {
        return emergeTicks >= HALF_OUT_FROM_TICK && emergeTicks <= HALF_OUT_TO_TICK;
    }

    /** Two creatures in frame going after each other: one targeting the other, or freshly hurt by it. */
    private static boolean fighting(List<LivingEntity> creatures) {
        for (LivingEntity a : creatures) {
            for (LivingEntity b : creatures) {
                if (a != b && (targets(a, b) || recentlyHurtBy(a, b))) return true;
            }
        }
        return false;
    }

    private static boolean targets(LivingEntity a, LivingEntity b) {
        return a instanceof Mob mob && mob.getTarget() == b;
    }

    private static boolean recentlyHurtBy(LivingEntity a, LivingEntity b) {
        return a.getLastHurtByMob() == b && a.tickCount - a.getLastHurtByMobTimestamp() <= RECENT_HURT_TICKS;
    }

    /** Whether {@code owner} is an Echo in this level — the pets an Echo brings back are tamed to it. */
    private static boolean isEcho(ServerLevel level, UUID owner) {
        Entity entity = level.getEntity(owner);
        return entity != null && EchoIdentity.sourcePlayer(entity).isPresent();
    }
}
