package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import games.brennan.dungeonbackup.api.Located;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import games.brennan.dungeontrain.player.DifficultyPartition;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How much play it took to earn the "Everything Burrito" capstone ({@link CompletionistAdvancement}),
 * counted since the player first played or since their last reset.
 *
 * <p><b>The clock is play time, not time aboard.</b> {@link #playTicks} counts every tick a player is in
 * the world and alive, on the train or off it, AFK or not, with any screen open except the pause menu
 * (and the settings reached from it). It stops on the pause menu, the main menu, the death screens and
 * in Free Play. The in-world part is ticked by {@code BurritoPlayTimeEvents}; time on a pausing screen
 * (books, photos), when a single-player server stands still, is measured on the client and credited when
 * the screen closes ({@code BurritoScreenTimePacket}). Unlike {@link GlobalPlayerStats#trainTicks} it has
 * no AFK or progress rules: the burrito measures how long it took, not how hard the player worked.</p>
 *
 * <p>A burrito's time is that counter at the earn minus its value at the last reset ({@link #reset}:
 * the "It's Not That Simple" wipe, or a profile advancement reset). A player who has never reset has no
 * baseline, which reads as 0: "since first played".</p>
 *
 * <p><b>Seeded from time aboard.</b> The counter arrived after the burrito did, whose clock was then
 * {@link GlobalPlayerStats#trainTicks}. A file without one starts it at that player's time aboard, so the
 * baselines already taken on that counter stay valid and nobody's history is lost: it counts under the old
 * rules up to the seed and under these from then on.</p>
 *
 * <p><b>One clock per advancement profile</b> ({@link AdvancementProfiles#currentKey}), because each
 * difficulty earns its own burrito. The counter itself is not split by difficulty, so a profile that
 * has never been reset counts play on every difficulty; a reset makes it exact from then on.</p>
 *
 * <p>Every completion is kept, with its attempt number (1 until the first reset, +1 per reset). The
 * earn's time also rides the relay's first-claim ({@code AdvancementFirstClient}) for the fastest
 * burrito board. File: {@code <gameDir>/dungeontrain/stats/<uuid>-burrito.json}. Resets and earns are
 * written at once; the ticking counter on logout and server stop ({@link #flush}, {@link #flushAll}).</p>
 */
public final class BurritoClock {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Pre-relocation home of the stats folder. This store never lived there; kept for {@link Located}. */
    private static final String LEGACY_DIR_NAME = "dungeontrain-stats";
    private static final String FILE_SUFFIX = "-burrito.json";

    /** One earned burrito: its play time (ticks), when, which attempt, on what difficulty and version. */
    public record Completion(long ticks, long earnedAt, int attempt, String difficulty, String modVersion) {
        static final Codec<Completion> CODEC = RecordCodecBuilder.create(in -> in.group(
            Codec.LONG.fieldOf("ticks").forGetter(Completion::ticks),
            Codec.LONG.optionalFieldOf("earnedAt", 0L).forGetter(Completion::earnedAt),
            Codec.INT.optionalFieldOf("attempt", 1).forGetter(Completion::attempt),
            Codec.STRING.optionalFieldOf("difficulty", "").forGetter(Completion::difficulty),
            Codec.STRING.optionalFieldOf("modVersion", "").forGetter(Completion::modVersion)
        ).apply(in, Completion::new));
    }

    /** One advancement profile's clock: where the current attempt started, its number, and every earn. */
    record Profile(long baseline, int attempt, List<Completion> completions) {
        static final Codec<Profile> CODEC = RecordCodecBuilder.create(in -> in.group(
            Codec.LONG.optionalFieldOf("baseline", 0L).forGetter(Profile::baseline),
            Codec.INT.optionalFieldOf("attempt", 1).forGetter(Profile::attempt),
            Completion.CODEC.listOf().optionalFieldOf("completions", List.of()).forGetter(Profile::completions)
        ).apply(in, Profile::new));

        static final Profile FRESH = new Profile(0L, 1, List.of());

        /** A new attempt starting at {@code playTicksNow}. Earlier completions are kept. */
        Profile reset(long playTicksNow) {
            return new Profile(Math.max(0L, playTicksNow), attempt + 1, completions);
        }

        /** This attempt's earn, made at {@code playTicksNow}. */
        Completion completionAt(long playTicksNow, long earnedAt, String difficulty, String modVersion) {
            return new Completion(elapsed(playTicksNow, baseline), earnedAt, attempt, difficulty, modVersion);
        }

        Profile with(Completion completion) {
            List<Completion> next = new ArrayList<>(completions);
            next.add(completion);
            return new Profile(baseline, attempt, List.copyOf(next));
        }
    }

    /** Marks a file written before the play counter existed: {@link #seeded} replaces it with time aboard. */
    static final long UNSEEDED = -1L;

    /**
     * The whole file: the player's play-time counter and one {@link Profile} per advancement profile key.
     * {@code playTicks} is {@link #UNSEEDED} until {@link #seeded} gives it a start.
     */
    record Data(long playTicks, Map<String, Profile> profiles) {
        static final Codec<Data> CODEC = RecordCodecBuilder.create(in -> in.group(
            Codec.LONG.optionalFieldOf("playTicks", UNSEEDED).forGetter(Data::playTicks),
            Codec.unboundedMap(Codec.STRING, Profile.CODEC).optionalFieldOf("profiles", Map.of()).forGetter(Data::profiles)
        ).apply(in, Data::new));

        static final Data EMPTY = new Data(UNSEEDED, Map.of());

        /** This data with its counter started at {@code trainTicks} if it has none yet. */
        Data seeded(long trainTicks) {
            return playTicks >= 0L ? this : new Data(Math.max(0L, trainTicks), profiles);
        }

        Data plusPlayTicks(long delta) {
            return new Data(Math.max(0L, playTicks) + delta, profiles);
        }

        Profile profile(String key) {
            return profiles.getOrDefault(key, Profile.FRESH);
        }

        Data with(String key, Profile profile) {
            Map<String, Profile> next = new HashMap<>(profiles);
            next.put(key, profile);
            return new Data(playTicks, Map.copyOf(next));
        }

        /** Every known profile starts a new attempt — for a reset that wipes the whole profile. */
        Data resetAll(long playTicksNow) {
            Map<String, Profile> next = new HashMap<>();
            profiles.forEach((k, p) -> next.put(k, p.reset(playTicksNow)));
            return new Data(playTicks, Map.copyOf(next));
        }
    }

    private static final Map<UUID, Data> CACHE = new ConcurrentHashMap<>();

    /** Players whose cached counter has moved since it was last written. */
    private static final Set<UUID> DIRTY = ConcurrentHashMap.newKeySet();

    private BurritoClock() {}

    /**
     * Ticks of play since the baseline. A counter below its baseline means the stats were reset after the
     * baseline was taken (Video Tools stats wipe), so the whole counter is this attempt's.
     */
    static long elapsed(long playTicksNow, long baseline) {
        long now = Math.max(0L, playTicksNow);
        return now >= baseline ? now - baseline : now;
    }

    /** {@code <gameDir>/dungeontrain/stats/<uuid>-burrito.json}. See {@link PlayerDataPaths}. */
    public static Path file(UUID playerUuid) {
        return located(playerUuid).read();
    }

    static Located located(UUID playerUuid) {
        return PlayerDataPaths.locate(PlayerDataPaths.STATS, LEGACY_DIR_NAME, playerUuid + FILE_SUFFIX);
    }

    private static Data current(UUID uuid) {
        return CACHE.computeIfAbsent(uuid, k -> loadFromDisk(k).seeded(GlobalPlayerStats.trainTicks(k)));
    }

    /** This player's play-time counter: the clock every burrito attempt is measured on. */
    public static long playTicks(UUID uuid) {
        return current(uuid).playTicks();
    }

    /** Add {@code delta} ticks of play. Cached only; written by {@link #flush} / {@link #flushAll}. */
    public static void addPlayTicks(UUID uuid, long delta) {
        if (delta <= 0L) return;
        CACHE.compute(uuid, (k, e) -> (e != null ? e : loadFromDisk(k).seeded(GlobalPlayerStats.trainTicks(k)))
            .plusPlayTicks(delta));
        DIRTY.add(uuid);
    }

    /** Write a player's counter if it has moved since the last write. */
    public static void flush(UUID uuid) {
        Data data = CACHE.get(uuid);
        if (data != null && DIRTY.remove(uuid)) saveToDisk(uuid, data);
    }

    /** Write every moved counter. Called on server stop. */
    public static void flushAll() {
        for (UUID uuid : List.copyOf(DIRTY)) flush(uuid);
    }

    /** Drop a player's cached clock after {@link #flush}. Used on logout. */
    public static void evict(UUID uuid) {
        flush(uuid);
        CACHE.remove(uuid);
    }

    /** Every burrito this player has earned on {@code profileKey}, oldest first. */
    public static List<Completion> completions(UUID uuid, String profileKey) {
        return current(uuid).profile(profileKey).completions();
    }

    /** The "It's Not That Simple" wipe: the burrito on the player's current profile starts again now. */
    public static synchronized void reset(ServerPlayer player) {
        UUID uuid = player.getUUID();
        String key = AdvancementProfiles.currentKey(player);
        Data data = current(uuid);
        store(uuid, data.with(key, data.profile(key).reset(data.playTicks())));
    }

    /** A wipe of the whole advancement profile (Video Tools): every known profile starts again now. */
    public static synchronized void resetAll(UUID uuid) {
        Data data = current(uuid);
        store(uuid, data.resetAll(data.playTicks()));
    }

    /**
     * Record a genuine burrito earn and return it, or {@code null} in Free Play — a cheated run does not
     * earn the capstone for real, and its time aboard was not counted anyway.
     */
    public static synchronized Completion recordEarn(ServerPlayer player) {
        if (RunIntegrity.isCheated(player)) return null;
        UUID uuid = player.getUUID();
        String key = AdvancementProfiles.currentKey(player);
        Data data = current(uuid);
        Profile profile = data.profile(key);
        Completion completion = profile.completionAt(data.playTicks(),
            System.currentTimeMillis(), DifficultyPartition.keyFor(player.level()), VersionInfo.VERSION);
        store(uuid, data.with(key, profile.with(completion)));
        LOGGER.info("[DungeonTrain] {} earned Everything Burrito in {} ticks of play (attempt {}, profile {})",
            player.getName().getString(), completion.ticks(), completion.attempt(), key);
        return completion;
    }

    /**
     * Wipe the record, cache first. Goes with the lifetime stats it is measured against.
     *
     * @return true when a file was actually removed
     */
    public static synchronized boolean deleteFor(UUID uuid) throws IOException {
        CACHE.remove(uuid);
        DIRTY.remove(uuid);
        boolean removed = false;
        for (Path path : located(uuid).all()) {
            removed |= Files.deleteIfExists(path);
        }
        return removed;
    }

    private static void store(UUID uuid, Data data) {
        CACHE.put(uuid, data);
        DIRTY.remove(uuid);
        saveToDisk(uuid, data);
    }

    private static Data loadFromDisk(UUID uuid) {
        Path path = file(uuid);
        if (!Files.isRegularFile(path)) return Data.EMPTY;
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonElement element = JsonParser.parseReader(reader);
            var result = Data.CODEC.parse(JsonOps.INSTANCE, element);
            if (result.error().isPresent()) {
                LOGGER.warn("[DungeonTrain] BurritoClock: parse failed for {}: {}",
                    path, result.error().get().message());
                return Data.EMPTY;
            }
            return result.result().orElse(Data.EMPTY);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] BurritoClock: error reading {}: {}", path, e.getMessage());
            return Data.EMPTY;
        }
    }

    private static void saveToDisk(UUID uuid, Data data) {
        Path path = located(uuid).preferred();
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] BurritoClock: failed to create dir {}: {}",
                path.getParent(), e.getMessage());
            return;
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        var result = Data.CODEC.encodeStart(JsonOps.INSTANCE, data);
        if (result.error().isPresent()) {
            LOGGER.error("[DungeonTrain] BurritoClock: encode failed: {}", result.error().get().message());
            return;
        }
        JsonElement element = result.result().orElseThrow();
        try (Writer writer = Files.newBufferedWriter(tmp)) {
            writer.write(element.toString());
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] BurritoClock: write tmp {} failed: {}", tmp, e.getMessage());
            return;
        }
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                LOGGER.error("[DungeonTrain] BurritoClock: rename {} -> {} failed: {}", tmp, path, e2.getMessage());
            }
        }
    }
}
