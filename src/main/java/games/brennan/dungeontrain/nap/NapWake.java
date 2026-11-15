package games.brennan.dungeontrain.nap;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The first half of waking from a nap: when the singleplayer owner first joins a brand-new run
 * world and has a nap pending, vanilla loads them from the nap instead of as a new player
 * ({@code mixin/PlayerListNapLoadMixin}). Going through vanilla's own load means inventory, XP,
 * effects, attributes and every mod's attachments arrive and sync exactly as on a normal rejoin.
 *
 * <p>The nap is used up right here, before the player is in the world, so no crash can wake it
 * twice. {@link NapWakeEvents} then moves the woken player to where they napped.</p>
 */
public final class NapWake {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Where the old world put the player: meaningless in a new one, so vanilla's defaults stand. */
    private static final List<String> STRIPPED_KEYS = List.of(
        "Motion", "Rotation", "Dimension", "RootVehicle", "FallDistance", "enteredNetherPosition",
        "SpawnX", "SpawnY", "SpawnZ", "SpawnDimension", "SpawnAngle", "SpawnForced",
        "SleepingX", "SleepingY", "SleepingZ");

    /** Naps loaded this session, waiting for {@link NapWakeEvents} to place the player. Server thread. */
    private static final Map<UUID, NapSnapshot> WOKEN = new HashMap<>();

    private NapWake() {}

    /**
     * The tag to load {@code player} from, when this login wakes a nap: the player is the integrated
     * server's owner, the world is a fresh run world (no saved player yet), and a nap is pending.
     */
    public static Optional<CompoundTag> tagFor(MinecraftServer server, ServerPlayer player) {
        if (server.isDedicatedServer() || !server.isSingleplayerOwner(player.getGameProfile())) return Optional.empty();
        if (!isFreshRunWorld(server, player.getUUID())) return Optional.empty();
        NapStore store = NapStore.local();
        Optional<NapSnapshot> nap = store.load(player.getUUID());
        if (nap.isEmpty()) return Optional.empty();
        NapSnapshot snapshot = nap.get();
        if (snapshot.levelId().equals(NapService.levelIdOf(server))) return Optional.empty();
        try {
            store.consume(player.getUUID(), snapshot);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Nap {} could not be marked used; not waking it.", snapshot.napId(), e);
            return Optional.empty();
        }
        WOKEN.put(player.getUUID(), snapshot);
        LOGGER.info("[DungeonTrain] Waking nap {} for {} (napped at x={} carriage={} band={})",
            snapshot.napId(), player.getName().getString(), Math.round(snapshot.x()), snapshot.carriage(), snapshot.band());
        return Optional.of(loadableTag(snapshot.player(), server));
    }

    static Optional<NapSnapshot> takeWoken(UUID player) {
        return Optional.ofNullable(WOKEN.remove(player));
    }

    static void clear() {
        WOKEN.clear();
    }

    /** The nap's player tag with the old world's whereabouts removed, standing at this world's spawn. */
    static CompoundTag loadableTag(CompoundTag napped, MinecraftServer server) {
        CompoundTag tag = napped.copy();
        STRIPPED_KEYS.forEach(tag::remove);
        var spawn = server.overworld().getSharedSpawnPos();
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(spawn.getX() + 0.5));
        pos.add(DoubleTag.valueOf(spawn.getY()));
        pos.add(DoubleTag.valueOf(spawn.getZ() + 0.5));
        tag.put("Pos", pos);
        return tag;
    }

    /** A run world nobody has played yet: DT generated its name, it has a train, and no saved player. */
    private static boolean isFreshRunWorld(MinecraftServer server, UUID player) {
        if (NapService.levelIdOf(server) == null) return false;
        if (!DungeonTrainWorldData.get(server.overworld()).startsWithTrain()) return false;
        if (server.getWorldData().getLoadedPlayerTag() != null) return false;
        return !Files.exists(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player + ".dat"));
    }
}
