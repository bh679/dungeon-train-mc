package games.brennan.dungeontrain.compat.vista;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.mehvahdjukaar.vista.common.connection.IConnectedBlock;
import net.mehvahdjukaar.vista.common.tv.TVBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Some TVs on the train come already switched on.
 *
 * <p>Right after a carriage's TVs are joined into screens ({@link TvTemplateJoin}), each screen rolls
 * {@link #SPAWN_ON_CHANCE}; a hit switches it on through {@link TvPowerToggle#toggle}, the same path a
 * click takes, so Vista's power propagation and sounds run and the screen tunes itself to the Live Feed
 * Cassette. The roll is seeded by {@code (seed, carriageIndex, screen position in the carriage)}, so a
 * carriage built again comes back with the same screens on.</p>
 *
 * <p>Players can stop it from the Live feed row on the Performance tab
 * ({@code LiveFeedClientConfig#tvsSpawnOn}, synced by {@code net.TvSpawnPrefPacket}). The power state
 * is a block state everyone shares, so while any online player has opted out, every TV spawns off.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TvSpawnPower {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Chance each stamped screen comes on. */
    static final double SPAWN_ON_CHANCE = 0.25;

    /** Online players who have asked for TVs to spawn off. */
    private static final Set<UUID> OPTED_OUT = ConcurrentHashMap.newKeySet();

    private TvSpawnPower() {}

    /** Records a player's choice from {@code TvSpawnPrefPacket}. */
    public static void setAllowed(UUID player, boolean allow) {
        if (allow) OPTED_OUT.remove(player); else OPTED_OUT.add(player);
    }

    /** Whether spawned TVs may come on: no online player has opted out. */
    static boolean allowedFor(Collection<UUID> online, Set<UUID> optedOut) {
        for (UUID id : online) if (optedOut.contains(id)) return false;
        return true;
    }

    private static boolean allowed(MinecraftServer server) {
        if (OPTED_OUT.isEmpty()) return true;
        List<UUID> online = server.getPlayerList().getPlayers().stream().map(ServerPlayer::getUUID).toList();
        return allowedFor(online, OPTED_OUT);
    }

    /** The deterministic roll for one screen; {@code rel} is its master's offset inside the carriage box. */
    static boolean rollOn(long seed, int carriageIndex, Vec3i rel) {
        long h = seed ^ 0x7456_5350_4f57_4552L; // "tVSPOWER"
        h = mix(h + carriageIndex);
        h = mix(h + rel.getX());
        h = mix(h + rel.getY());
        h = mix(h + rel.getZ());
        double unit = (h >>> 11) * 0x1.0p-53;
        return unit < SPAWN_ON_CHANCE;
    }

    /** SplitMix64's finaliser. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    /** Rolls every screen inside the box {@code origin} .. {@code origin + size - 1}. Call after the TV join. */
    public static void apply(ServerLevel level, BlockPos origin, Vec3i size, long seed, int carriageIndex) {
        List<BlockPos> tvs = TvTemplateJoin.findTvs(level, origin, size);
        if (tvs.isEmpty() || !allowed(level.getServer())) return;
        try {
            Set<BlockPos> screens = masters(level, tvs);
            int switchedOn = 0;
            for (BlockPos master : screens) {
                BlockState state = level.getBlockState(master);
                if (!state.hasProperty(TVBlock.POWER_STATE) || state.getValue(TVBlock.POWER_STATE).isOn()) continue;
                if (rollOn(seed, carriageIndex, master.subtract(origin))) {
                    TvPowerToggle.toggle(level, master);
                    switchedOn++;
                }
            }
            LOGGER.debug("[DT-TvSpawn] carriage {}: {} of {} screens switched on", carriageIndex, switchedOn, screens.size());
        } catch (RuntimeException e) {
            // A TV left off is cosmetic; a failed carriage stamp is not.
            LOGGER.warn("[DungeonTrain] TV spawn power failed at {} ({} TVs); leaving them off", origin, tvs.size(), e);
        }
    }

    /** One position per screen: the cell holding its block entity, in scan order. */
    private static Set<BlockPos> masters(ServerLevel level, List<BlockPos> tvs) {
        Set<BlockPos> found = new LinkedHashSet<>();
        for (BlockPos pos : tvs) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof IConnectedBlock tv)) continue;
            BlockEntity master = tv.findMasterBlockEntity(level, pos, state);
            if (master != null) found.add(master.getBlockPos().immutable());
        }
        return found;
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        OPTED_OUT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        OPTED_OUT.clear();
    }
}
