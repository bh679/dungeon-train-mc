package games.brennan.dungeontrain.nap;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.narrative.NarrativeProgressData;
import games.brennan.dungeontrain.player.RunRestore;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Waking from a nap, in a brand-new run world of the nap's own {@link NapSlot}:
 * <ol>
 *   <li>At server start ({@link #planFor}, called by the train bootstrap just before it spawns), the owner's pending nap for
 *       this world's slot is picked and the world's carriage counter raised to its depth, so the seed
 *       train spawns at the nap's X ({@link #bootstrapOriginX}) and the first carriages generate at
 *       that depth. The world spawn lands beside it.</li>
 *   <li>On the owner's first login, vanilla loads them from the nap instead of as a new player
 *       ({@code mixin/PlayerListNapLoadMixin}), standing at that spawn. Going through vanilla's own
 *       load means inventory, XP, effects, attributes and every mod's attachments arrive and sync
 *       exactly as on a normal rejoin, and the normal first-join placement puts them on the deck.</li>
 * </ol>
 *
 * <p>The nap is used up at that login, before the player is in the world, so no crash can wake it
 * twice. {@link NapWakeEvents} turns the woken player to face the way they were heading.</p>
 */
public final class NapWake {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Where the old world put the player: meaningless in a new one, so vanilla's defaults stand. */
    private static final List<String> STRIPPED_KEYS = List.of(
        "Motion", "Rotation", "Dimension", "RootVehicle", "FallDistance", "enteredNetherPosition",
        "SpawnX", "SpawnY", "SpawnZ", "SpawnDimension", "SpawnAngle", "SpawnForced",
        "SleepingX", "SleepingY", "SleepingZ");

    private record Plan(UUID owner, String slot, NapSnapshot nap) {}

    /** The nap this server start will wake, if any. Server thread. */
    private static Plan planned;
    /** Naps loaded this session, waiting for {@link NapWakeEvents} to turn the player. Server thread. */
    private static final Map<UUID, NapSnapshot> WOKEN = new HashMap<>();
    /** World-X a woken player is put on the train at, for the join placement. Server thread. */
    private static final Map<UUID, Double> PLACE_AT = new HashMap<>();

    private NapWake() {}

    /**
     * At server start: pick the singleplayer owner's nap for this world's slot, when this is a fresh
     * run world, and raise the world's carriage counter to its depth.
     */
    public static void planFor(MinecraftServer server) {
        planned = null;
        if (server.isDedicatedServer()) return;
        GameProfile owner = server.getSingleplayerProfile();
        if (owner == null || owner.getId() == null) return;
        UUID uuid = owner.getId();
        if (!isFreshRunWorld(server, uuid)) return;
        String slot = NapSlot.forNewWorld(server);
        Optional<NapSnapshot> nap = NapStore.local().load(uuid, slot);
        if (nap.isEmpty() || nap.get().levelId().equals(NapService.levelIdOf(server))) return;
        planned = new Plan(uuid, slot, nap.get());
        RunRestore.raiseWorldProgress(server.overworld(), nap.get().worldDepth());
        LOGGER.info("[DungeonTrain] Nap {} ({}) will wake here at x={} carriage={} band={}", nap.get().napId(), slot,
            Math.round(nap.get().x()), nap.get().carriage(), nap.get().band());
    }

    /** Where the seed train goes in {@code level}: the planned nap's X in the overworld, else 0. */
    public static int bootstrapOriginX(ServerLevel level) {
        if (planned == null || level.dimension() != Level.OVERWORLD) return 0;
        return Mth.floor(planned.nap().x());
    }

    /** The tag to load {@code player} from, when this login wakes the planned nap. */
    public static Optional<CompoundTag> tagFor(MinecraftServer server, ServerPlayer player) {
        Plan plan = planned;
        if (plan == null || !plan.owner().equals(player.getUUID())) return Optional.empty();
        planned = null;
        try {
            NapStore.local().consume(plan.owner(), plan.slot(), plan.nap());
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Nap {} could not be marked used; not waking it.", plan.nap().napId(), e);
            return Optional.empty();
        }
        // Not a new player: no welcome book and lightning. Marked before the login event, which reads it.
        NarrativeProgressData.get(server.overworld()).markStartingBookReceived(player.getUUID());
        WOKEN.put(player.getUUID(), plan.nap());
        PLACE_AT.put(player.getUUID(), plan.nap().x());
        LOGGER.info("[DungeonTrain] Waking nap {} for {}", plan.nap().napId(), player.getName().getString());
        return Optional.of(loadableTag(plan.nap().player(), server));
    }

    /** Where the join placement should put {@code player} on the train, once: a woken nap's X, else null. */
    public static Double takePlacementX(UUID player) {
        return PLACE_AT.remove(player);
    }

    static Optional<NapSnapshot> takeWoken(UUID player) {
        return Optional.ofNullable(WOKEN.remove(player));
    }

    static void clear() {
        planned = null;
        WOKEN.clear();
        PLACE_AT.clear();
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
