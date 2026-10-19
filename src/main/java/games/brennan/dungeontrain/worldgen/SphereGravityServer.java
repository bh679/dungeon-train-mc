package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.config.SpheresGravityConfig;
import games.brennan.dungeontrain.mixin.ServerGamePacketListenerFloatAccessor;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Server half of sphere gravity: every overworld tick, pulls the mobs, items, XP orbs and projectiles
 * near each player in the spheres band toward the spheres around them ({@link SphereGravityPush}).
 * Players are skipped — each client pulls its own ({@code client.SphereGravityClient}), since player
 * movement is client-authoritative. Only entities within one sphere-reach of a player are looked at, and
 * only where the chunk is entity-ticking (a non-ticking entity would just pile up velocity).
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SphereGravityServer {

    /** Padding on the per-player entity box beyond the largest sphere's reach. */
    private static final double BOX_PAD = 8.0;
    /** Half-diagonal of a 16³ section: a section-centred sphere list covers every point inside it. */
    private static final double SECTION_HALF_DIAGONAL = 14.0;

    private static volatile boolean forcedOff;

    private SphereGravityServer() {}

    /** Debug toggle ({@code /dungeontrain debug sphere-gravity on|off}); doesn't touch config. */
    public static void setForcedOff(boolean off) {
        forcedOff = off;
    }

    public static boolean isForcedOff() {
        return forcedOff;
    }

    /** True when sphere gravity runs at all: config on, band on, not forced off. */
    public static boolean active() {
        return !forcedOff && SpheresGravityConfig.enabled() && SpheresGravityConfig.strength() > 0.0
                && DungeonTrainCommonConfig.isSpheresEnabled();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Level.OVERWORLD.equals(level.dimension())) return;
        if (!active() || level.players().isEmpty()) return;
        if (!DungeonTrainWorldData.get(level).startsWithTrain()) return;
        double reach = SphereGravityPush.lookupReach();
        double half = DungeonTrainCommonConfig.getSpheresMaxRadius() + reach + BOX_PAD;
        Set<Entity> seen = null;
        Long2ObjectOpenHashMap<List<SphereField.Sphere>> bySection = null;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            WorldGenCycle cycle = MixBand.cycleAtBlock(level, player.getBlockX(), player.getBlockZ());
            if (!SpheresBand.nearBand(cycle, player.getX(), half)) continue;
            keepAloft(level, player, reach);
            AABB box = player.getBoundingBox().inflate(half);
            for (Entity e : level.getEntities((Entity) null, box, SphereGravityServer::pullable)) {
                if (seen == null) {
                    seen = new HashSet<>();
                    bySection = new Long2ObjectOpenHashMap<>();
                }
                if (!seen.add(e) || !level.isPositionEntityTicking(e.blockPosition())) continue;
                pull(level, e, reach, bySection);
            }
        }
    }

    private static void pull(ServerLevel level, Entity e, double reach,
                             Long2ObjectOpenHashMap<List<SphereField.Sphere>> bySection) {
        long key = SectionPos.asLong(e.blockPosition());
        List<SphereField.Sphere> spheres = bySection.get(key);
        if (spheres == null) {
            spheres = SpheresBand.spheresNear(level,
                    SectionPos.sectionToBlockCoord(SectionPos.x(key), 8),
                    SectionPos.sectionToBlockCoord(SectionPos.y(key), 8),
                    SectionPos.sectionToBlockCoord(SectionPos.z(key), 8),
                    reach + SECTION_HALF_DIAGONAL);
            bySection.put(key, spheres);
        }
        SphereGravity.Pull pull = SphereGravityPush.pullOn(e, spheres);
        if (pull.isZero()) return;
        SphereGravityPush.apply(e, pull, SphereGravityShelter.factor(e, SpheresGravityConfig.riderFactor()));
    }

    /**
     * A player a sphere is pulling may hang over the void (under a sphere its pull nearly cancels gravity),
     * which vanilla's flight check reads as flying and kicks. Clear the count while any sphere pulls them.
     */
    private static void keepAloft(ServerLevel level, ServerPlayer player, double reach) {
        double midY = player.getY() + player.getBbHeight() * 0.5;
        List<SphereField.Sphere> spheres = SpheresBand.spheresNear(level, player.getX(), midY, player.getZ(), reach);
        if (spheres.isEmpty()) return;
        if (SphereGravity.pull(spheres, player.getX(), midY, player.getZ(), 0, 0, 0,
                SpheresGravityConfig.strength(), SpheresGravityConfig.reachRadii()).isZero()) return;
        ((ServerGamePacketListenerFloatAccessor) player.connection).dungeontrain$setAboveGroundTickCount(0);
    }

    /** Mobs, items, XP, projectiles — not players (their client pulls them), decorations, riders or floaters. */
    static boolean pullable(Entity e) {
        return !(e instanceof Player) && !(e instanceof HangingEntity) && !(e instanceof Display)
                && !e.isPassenger() && !e.isNoGravity() && !e.isSpectator() && e.isAlive();
    }
}
