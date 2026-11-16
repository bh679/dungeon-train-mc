package games.brennan.dungeontrain.nap;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.difficulty.DifficultyOffset;
import games.brennan.dungeontrain.narrative.NarrativeProgressData;
import games.brennan.dungeontrain.player.PlayerRunState;
import games.brennan.dungeontrain.player.RunRestore;
import games.brennan.dungeontrain.registry.ModDataAttachments;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p>The nap is used up once the player has finished loading in ({@link #finishLoading}): placed on
 * the train, or gone mid-load with its gear already in this world. A load that crashes part way
 * keeps it for the next run. {@link NapWakeEvents} turns the woken player to face the way they were heading.</p>
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
    /** {@link #planFor} already ran for this server start. Server thread. */
    private static boolean plannedThisStart;
    /** Naps loaded this session, waiting for {@link NapWakeEvents} to turn the player. Server thread. */
    private static final Map<UUID, NapSnapshot> WOKEN = new HashMap<>();
    /** Naps loaded into this world but not yet used up (the player is still loading in). Server thread. */
    private static final Map<UUID, Plan> LOADING = new HashMap<>();
    /** World-X a woken player is put on the train at, for the join placement. Server thread. */
    private static final Map<UUID, Double> PLACE_AT = new HashMap<>();

    private NapWake() {}

    /**
     * At server start: wake the singleplayer owner's nap here, when this is the world it is bound to
     * or a fresh run world it may bind to, and raise the world's carriage counter to its depth. A nap
     * bound to this world whose player is already saved here is used up instead ({@link NapBinding}).
     * Runs once per server start; the train bootstrap calls it before the seed spawns, and
     * {@link NapWakeEvents} calls it for starts the bootstrap skipped (a reopened world).
     */
    public static void planFor(MinecraftServer server) {
        if (plannedThisStart) return;
        plannedThisStart = true;
        planned = null;
        if (server.isDedicatedServer()) return;
        GameProfile owner = server.getSingleplayerProfile();
        if (owner == null || owner.getId() == null) return;
        UUID uuid = owner.getId();
        String here = NapService.levelIdOf(server);
        if (here == null) return;
        NapStore store = NapStore.local();
        if (wakeBoundHere(server, store, uuid, here)) return;
        if (!isFreshRunWorld(server, uuid)) return;
        String slot = NapSlot.forNewWorld(server);
        Optional<NapSnapshot> nap = store.load(uuid, slot);
        if (nap.isEmpty() || nap.get().levelId().equals(here)) return;
        if (!NapSlot.matches(slot, nap.get())) {
            LOGGER.warn("[DungeonTrain] Nap {} is a {} {} run but sits in slot {}; not waking it.", nap.get().napId(),
                nap.get().cheated() ? "freeplay" : "survival", nap.get().difficulty(), slot);
            return;
        }
        if (NapBinding.decide(nap.get(), here, boundWorldHolds(server, nap.get(), uuid), false)
                != NapBinding.Decision.WAKE) {
            LOGGER.info("[DungeonTrain] Nap {} is waking in '{}'; not here.", nap.get().napId(), nap.get().wakeLevelId());
            return;
        }
        NapSnapshot bound;
        try {
            // Bound before the train spawns or the player loads: from here on it wakes only in this world.
            bound = store.bind(uuid, slot, nap.get(), here);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Nap {} could not be bound to '{}'; not waking it.", nap.get().napId(), here, e);
            return;
        }
        plan(server, uuid, slot, bound);
    }

    /**
     * A nap already bound to this world (it was reopened, which only crash recovery does): used up
     * when the player saved here, so the gear is here; woken again when they never did. True when
     * one was woken.
     */
    private static boolean wakeBoundHere(MinecraftServer server, NapStore store, UUID owner, String here) {
        boolean saved = hasSavedPlayer(server, owner);
        for (NapStore.Pending p : store.pending(owner)) {
            if (!here.equals(p.nap().wakeLevelId())) continue;
            if (NapBinding.decide(p.nap(), here, true, saved) == NapBinding.Decision.CONSUME) {
                try {
                    store.consume(owner, p.slot(), p.nap());
                    LOGGER.info("[DungeonTrain] Nap {} already loaded into '{}'; used up.", p.nap().napId(), here);
                } catch (IOException e) {
                    LOGGER.warn("[DungeonTrain] Nap {} could not be marked used", p.nap().napId(), e);
                }
                continue;
            }
            LOGGER.info("[DungeonTrain] Nap {} never finished loading into '{}'; waking it again.",
                p.nap().napId(), here);
            plan(server, owner, p.slot(), p.nap());
            return true;
        }
        return false;
    }

    private static void plan(MinecraftServer server, UUID owner, String slot, NapSnapshot nap) {
        planned = new Plan(owner, slot, nap);
        RunRestore.raiseWorldProgress(server.overworld(), nap.worldDepth());
        carryDifficultyOffset(server, nap);
        carryPreviousLife(server, owner, nap);
        LOGGER.info("[DungeonTrain] Nap {} ({}) will wake here at x={} carriage={} band={}", nap.napId(), slot,
            Math.round(nap.x()), nap.carriage(), nap.band());
    }

    /**
     * Whether the world {@code nap} is bound to still holds the owner's saved life
     * ({@link NapBoundWorld}). Naps only wake on an integrated server, whose world sits in the
     * client's saves folder beside every other save.
     */
    static boolean boundWorldHolds(MinecraftServer server, NapSnapshot nap, UUID owner) {
        if (!nap.bound()) return false;
        try {
            Path saves = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getParent();
            return NapBoundWorld.holdsLife(saves, nap.wakeLevelId(), owner);
        } catch (RuntimeException e) {
            return true; // can't tell: leave it to its own world
        }
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
        // Used up once the player has finished loading in (finishLoading), not here: a load that dies
        // part way keeps the nap for the next run.
        LOADING.put(player.getUUID(), plan);
        // Not a new player: no welcome book and lightning. Marked before the login event, which reads it.
        NarrativeProgressData.get(server.overworld()).markStartingBookReceived(player.getUUID());
        // No advancement can be earned by the wake itself (landing on the train, the first tick in a biome).
        NapWakeGrace.open(player.getUUID());
        WOKEN.put(player.getUUID(), plan.nap());
        PLACE_AT.put(player.getUUID(), plan.nap().x());
        LOGGER.info("[DungeonTrain] Waking nap {} for {}", plan.nap().napId(), player.getName().getString());
        return Optional.of(loadableTag(plan.nap().player(), server));
    }

    /**
     * Right after vanilla loaded {@code player} from the nap ({@code mixin/PlayerListNapLoadMixin}),
     * before the login event: give the run state back the fields its codec could not carry, and make
     * the chest streak forget the napped world's chest positions while keeping its count.
     */
    public static void restoreRunState(ServerPlayer player) {
        Plan plan = LOADING.get(player.getUUID());
        if (plan == null) return;
        try {
            PlayerRunState run = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
            run.applyNapExtras(plan.nap().runExtras(), player.registryAccess());
            run.forgetChestPositionsKeepingCount();
            // Behind the nap point this world's carriages are re-rolled copies of cleared ones, so
            // count-based run counters step down there for the rest of the life.
            run.setNapGate(plan.nap().x(),
                PlayerRunState.travelDirection(run.travelledCarriageIndex(), plan.nap().direction()));
            NapGateData.remember(player, run);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Nap {}: run-state extras not restored", plan.nap().napId(), e);
        }
    }

    /**
     * The napped world's admin difficulty offset, set on this one through the same path as the
     * command, before the seed train generates. The per-player streak counters carried in the
     * player tag hold offset-inclusive carriage readings, so a different offset here would shift
     * them. {@code DifficultyOffsetLifecycle} only re-mirrors at world load (already past) and
     * clears on a death, so nothing undoes this on login.
     */
    private static void carryDifficultyOffset(MinecraftServer server, NapSnapshot nap) {
        if (nap.difficultyOffset() == 0) return;
        try {
            DifficultyOffset.set(server, nap.difficultyOffset());
            LOGGER.info("[DungeonTrain] Nap {}: difficulty offset {} carried", nap.napId(), nap.difficultyOffset());
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Nap {}: difficulty offset not carried", nap.napId(), e);
        }
    }

    /**
     * The woken player has finished loading in (placed on the train, or left mid-load with the nap's
     * gear now in this world): the nap is used up, its file removed and its id recorded so a copy
     * can't wake again. Safe to call more than once.
     */
    public static void finishLoading(UUID player) {
        Plan plan = LOADING.remove(player);
        if (plan == null) return;
        try {
            NapStore.local().consume(plan.owner(), plan.slot(), plan.nap());
            LOGGER.info("[DungeonTrain] Nap {} loaded in and used up", plan.nap().napId());
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Nap {} could not be marked used", plan.nap().napId(), e);
        }
    }

    static boolean isLoading(UUID player) {
        return LOADING.containsKey(player);
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
        plannedThisStart = false;
        LOADING.clear();
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

    /**
     * The previous life's advancements, stats and narrative progress, written into this fresh world
     * before anyone logs in, so vanilla loads them at login as already earned (no toasts) and the
     * stories carry on where they were.
     */
    private static void carryPreviousLife(MinecraftServer server, UUID owner, NapSnapshot nap) {
        writePlayerFile(server, LevelResource.PLAYER_ADVANCEMENTS_DIR, owner, nap.advancementsJson());
        writePlayerFile(server, LevelResource.PLAYER_STATS_DIR, owner, nap.statsJson());
        if (nap.narrative().isEmpty()) return;
        try {
            NarrativeProgressData.get(server.overworld()).importFor(owner, nap.narrative());
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Nap {}: narrative progress not carried", nap.napId(), e);
        }
    }

    private static void writePlayerFile(MinecraftServer server, LevelResource dir, UUID owner, String json) {
        if (json.isEmpty()) return;
        Path file = server.getWorldPath(dir).resolve(owner + ".json");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Nap: could not write {}; not carried", file, e);
        }
    }

    /**
     * True when {@code profile} is this integrated server's owner and this world is the one a nap of
     * theirs was taken in: that life is in the nap, so the world must not be played
     * ({@code mixin/PlayerListNapWorldRefuseMixin}). The client deletes it from the title screen.
     */
    public static boolean isNappedWorld(MinecraftServer server, GameProfile profile) {
        if (server == null || server.isDedicatedServer() || profile == null || profile.getId() == null) return false;
        GameProfile owner = server.getSingleplayerProfile();
        if (owner == null || !profile.getId().equals(owner.getId())) return false;
        String here = NapService.levelIdOf(server);
        if (here == null) return false;
        for (NapStore.Pending p : NapStore.local().pending(owner.getId())) {
            if (here.equals(p.nap().levelId())) return true;
        }
        return false;
    }

    /** A run world nobody has played yet: DT generated its name, it has a train, and no saved player. */
    private static boolean isFreshRunWorld(MinecraftServer server, UUID player) {
        if (NapService.levelIdOf(server) == null) return false;
        if (!DungeonTrainWorldData.get(server.overworld()).startsWithTrain()) return false;
        return !hasSavedPlayer(server, player);
    }

    /** The world already holds {@code player}'s saved data (singleplayer keeps it in level.dat). */
    private static boolean hasSavedPlayer(MinecraftServer server, UUID player) {
        if (server.getWorldData().getLoadedPlayerTag() != null) return true;
        return Files.exists(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player + ".dat"));
    }
}
