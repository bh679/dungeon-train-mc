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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How much play it took to earn the "Everything Burrito" capstone ({@link CompletionistAdvancement}),
 * counted since the player first played or since their last reset.
 *
 * <p><b>The clock is the hours we already keep.</b> Play time here is {@link GlobalPlayerStats#trainTicks}
 * — lifetime time aboard, the same figure as the "Longest Aboard" board, and already frozen in Free
 * Play, while dead and while AFK. A burrito's time is that counter at the earn minus its value at the
 * last reset ({@link #reset}: the "It's Not That Simple" wipe, or a profile advancement reset). A player
 * who has never reset has no baseline, which reads as 0 — "since first played" — so everyone who was
 * already playing before this existed gets their full history with no backfill.</p>
 *
 * <p><b>One clock per advancement profile</b> ({@link AdvancementProfiles#currentKey}), because each
 * difficulty earns its own burrito. The counter itself is not split by difficulty, so a profile that
 * has never been reset counts time aboard on every difficulty; a reset makes it exact from then on.</p>
 *
 * <p>Every completion is kept, with its attempt number (1 until the first reset, +1 per reset). The
 * earn's time also rides the relay's first-claim ({@code AdvancementFirstClient}) for the fastest
 * burrito board. File: {@code <gameDir>/dungeontrain/stats/<uuid>-burrito.json}, written at once —
 * resets and earns are rare.</p>
 */
public final class BurritoClock {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Pre-relocation home of the stats folder. This store never lived there; kept for {@link Located}. */
    private static final String LEGACY_DIR_NAME = "dungeontrain-stats";
    private static final String FILE_SUFFIX = "-burrito.json";

    /** One earned burrito: its play time (ticks aboard), when, which attempt, on what difficulty and version. */
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

        /** A new attempt starting at {@code trainTicksNow}. Earlier completions are kept. */
        Profile reset(long trainTicksNow) {
            return new Profile(Math.max(0L, trainTicksNow), attempt + 1, completions);
        }

        /** This attempt's earn, made at {@code trainTicksNow}. */
        Completion completionAt(long trainTicksNow, long earnedAt, String difficulty, String modVersion) {
            return new Completion(elapsed(trainTicksNow, baseline), earnedAt, attempt, difficulty, modVersion);
        }

        Profile with(Completion completion) {
            List<Completion> next = new ArrayList<>(completions);
            next.add(completion);
            return new Profile(baseline, attempt, List.copyOf(next));
        }
    }

    /** The whole file: one {@link Profile} per advancement profile key. */
    record Data(Map<String, Profile> profiles) {
        static final Codec<Data> CODEC = RecordCodecBuilder.create(in -> in.group(
            Codec.unboundedMap(Codec.STRING, Profile.CODEC).optionalFieldOf("profiles", Map.of()).forGetter(Data::profiles)
        ).apply(in, Data::new));

        static final Data EMPTY = new Data(Map.of());

        Profile profile(String key) {
            return profiles.getOrDefault(key, Profile.FRESH);
        }

        Data with(String key, Profile profile) {
            Map<String, Profile> next = new HashMap<>(profiles);
            next.put(key, profile);
            return new Data(Map.copyOf(next));
        }

        /** Every known profile starts a new attempt — for a reset that wipes the whole profile. */
        Data resetAll(long trainTicksNow) {
            Map<String, Profile> next = new HashMap<>();
            profiles.forEach((k, p) -> next.put(k, p.reset(trainTicksNow)));
            return new Data(Map.copyOf(next));
        }
    }

    private static final Map<UUID, Data> CACHE = new ConcurrentHashMap<>();

    private BurritoClock() {}

    /**
     * Ticks aboard since the baseline. A counter below its baseline means the stats were reset after the
     * baseline was taken (Video Tools profile reset), so the whole counter is this attempt's.
     */
    static long elapsed(long trainTicksNow, long baseline) {
        long now = Math.max(0L, trainTicksNow);
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
        return CACHE.computeIfAbsent(uuid, BurritoClock::loadFromDisk);
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
        store(uuid, data.with(key, data.profile(key).reset(GlobalPlayerStats.trainTicks(uuid))));
    }

    /** A wipe of the whole advancement profile (Video Tools): every known profile starts again now. */
    public static synchronized void resetAll(UUID uuid) {
        store(uuid, current(uuid).resetAll(GlobalPlayerStats.trainTicks(uuid)));
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
        Completion completion = profile.completionAt(GlobalPlayerStats.trainTicks(uuid),
            System.currentTimeMillis(), DifficultyPartition.keyFor(player.level()), VersionInfo.VERSION);
        store(uuid, data.with(key, profile.with(completion)));
        LOGGER.info("[DungeonTrain] {} earned Everything Burrito in {} ticks aboard (attempt {}, profile {})",
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
        boolean removed = false;
        for (Path path : located(uuid).all()) {
            removed |= Files.deleteIfExists(path);
        }
        return removed;
    }

    private static void store(UUID uuid, Data data) {
        CACHE.put(uuid, data);
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
