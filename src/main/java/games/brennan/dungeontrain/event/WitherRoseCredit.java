package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A wither rose a player placed earns them the kill: anything that dies of Wither after standing in
 * it is credited to the placer through {@link KillCredit#resolveKiller}. Wither damage carries no
 * causing entity, so without this such a death credits nobody.
 *
 * <p>Placers are recorded by block position as the placement event reports it, and exposures by the
 * position {@code WitherRoseBlock#entityInside} is handed ({@code WitherRoseBlockCreditMixin}). Sable
 * routes both through the carriage plot, so a rose on a moving carriage matches too. A naturally
 * generated rose has no placer and credits no one; neither does a rose the victim placed.</p>
 *
 * <p>In memory only, like {@code PlayerPlacedTrainBlocks}: train builds do not outlive the session
 * either. Server thread only.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class WitherRoseCredit {

    /**
     * How long after last standing in a placed rose a Wither death still credits its placer. The
     * effect lingers 40 ticks after leaving the rose and level-I Wither hurts every 40 ticks, so a
     * victim who steps off can still take one more fatal tick well inside this.
     */
    static final long EXPOSURE_WINDOW_TICKS = 100L;

    /** Most placed roses remembered at once; the oldest is forgotten past this. */
    private static final int MAX_PLACED_ROSES = 8192;

    /** How often (ticks) stale exposures are swept. */
    private static final int PRUNE_PERIOD_TICKS = 200;

    private static final Map<RoseKey, UUID> PLACERS = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<RoseKey, UUID> eldest) {
            return size() > MAX_PLACED_ROSES;
        }
    };

    /** victim UUID → most recent contact with a placed rose. */
    private static final Map<UUID, Exposure> EXPOSURES = new HashMap<>();

    record RoseKey(ResourceKey<Level> dimension, long pos) {}

    record Exposure(UUID placerUuid, long tick) {}

    private WitherRoseCredit() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) return;
        RoseKey key = key(level, event.getPos());
        if (event.getPlacedBlock().is(Blocks.WITHER_ROSE) && event.getEntity() instanceof Player player) {
            PLACERS.put(key, player.getUUID());
        } else {
            PLACERS.remove(key);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) return;
        PLACERS.remove(key(level, event.getPos()));
    }

    /** Called from {@code WitherRoseBlock#entityInside}: remember that {@code entity} is in this rose. */
    public static void onEntityInside(Level level, BlockPos pos, Entity entity) {
        if (!(level instanceof ServerLevel serverLevel) || !(entity instanceof LivingEntity victim)) return;
        UUID placer = PLACERS.get(key(serverLevel, pos));
        if (placer == null || placer.equals(victim.getUUID())) return;
        EXPOSURES.put(victim.getUUID(), new Exposure(placer, serverLevel.getGameTime()));
    }

    /** The player whose rose withered {@code victim}, if {@code source} is Wither and the contact is recent. */
    public static Optional<Entity> creditFor(LivingEntity victim, DamageSource source) {
        if (!source.is(DamageTypes.WITHER)) return Optional.empty();
        Exposure exposure = EXPOSURES.get(victim.getUUID());
        if (exposure == null || !withinWindow(exposure.tick(), victim.level().getGameTime())) {
            return Optional.empty();
        }
        MinecraftServer server = victim.getServer();
        if (server == null) return Optional.empty();
        ServerPlayer placer = server.getPlayerList().getPlayer(exposure.placerUuid());
        return Optional.<Entity>ofNullable(placer).filter(p -> p != victim);
    }

    static boolean withinWindow(long exposureTick, long now) {
        long age = now - exposureTick;
        return age >= 0 && age <= EXPOSURE_WINDOW_TICKS;
    }

    private static RoseKey key(ServerLevel level, BlockPos pos) {
        return new RoseKey(level.dimension(), pos.asLong());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (EXPOSURES.isEmpty()) return;
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % PRUNE_PERIOD_TICKS != 0) return;
        long now = server.overworld().getGameTime();
        EXPOSURES.values().removeIf(e -> !withinWindow(e.tick(), now));
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PLACERS.clear();
        EXPOSURES.clear();
    }
}
