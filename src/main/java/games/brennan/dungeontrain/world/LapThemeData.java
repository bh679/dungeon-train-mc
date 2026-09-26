package games.brennan.dungeontrain.world;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.advancement.LapThemeProgress;
import games.brennan.dungeontrain.worldgen.LapTheme;
import games.brennan.dungeontrain.worldgen.LapThemePlan;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * This world's theme-lap decisions — lap index → {@link LapTheme} — so a lap keeps the look it was
 * given across restarts. Chunks are generated ahead of the train, so a lap's theme is fixed the first
 * time anything asks for it and never recomputed from later progress.
 *
 * <p>Per world, at {@code <world>/data/dungeontrain_lap_themes.dat}. Owns the world's
 * {@link LapThemePlan}: new decisions are written here ({@link #setDirty}) and logged with the
 * progress they were made from.</p>
 */
public final class LapThemeData extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String NAME = "dungeontrain_lap_themes";
    private static final String TAG_LAPS = "laps";

    private final Map<Long, LapTheme> decided = new TreeMap<>();
    private volatile LapThemePlan plan;

    LapThemeData() {}

    public static LapThemeData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(LapThemeData::new, (tag, registries) -> load(tag)), NAME);
    }

    /**
     * This world's plan, built once. {@code worldSeed} seeds each lap's pick; progress comes from the
     * players online when a lap is decided — or, before anyone has joined, the singleplayer host.
     */
    public synchronized LapThemePlan plan(MinecraftServer server, long worldSeed) {
        if (plan == null) {
            Map<Long, LapTheme> known;
            synchronized (decided) {
                known = Map.copyOf(decided);
            }
            plan = new LapThemePlan(worldSeed, known, () -> progressOf(server), this::record);
        }
        return plan;
    }

    private void record(long n, LapTheme theme, Map<LapTheme, Double> progress) {
        synchronized (decided) {
            decided.put(n, theme);
        }
        setDirty();
        LOGGER.info("[DT-LapTheme] lap {} -> {} (progress {})", n, theme.id(), progress);
    }

    /** Mean progress of the players online, else the singleplayer host's, else all zero. */
    static Map<LapTheme, Double> progressOf(MinecraftServer server) {
        List<UUID> players = new ArrayList<>();
        try {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) players.add(p.getUUID());
            if (players.isEmpty() && server.getSingleplayerProfile() != null) {
                players.add(server.getSingleplayerProfile().getId());
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[DT-LapTheme] could not list players for a lap decision; using no progress", e);
        }
        return LapThemeProgress.average(players);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag laps = new CompoundTag();
        synchronized (decided) {
            decided.forEach((n, t) -> laps.putString(Long.toString(n), t.id()));
        }
        tag.put(TAG_LAPS, laps);
        return tag;
    }

    static LapThemeData load(CompoundTag tag) {
        LapThemeData data = new LapThemeData();
        CompoundTag laps = tag.getCompound(TAG_LAPS);
        for (String key : laps.getAllKeys()) {
            LapTheme theme = LapTheme.byId(laps.getString(key));
            try {
                long n = Long.parseLong(key);
                if (theme != null && n >= 0L) data.decided.put(n, theme);
            } catch (NumberFormatException e) {
                LOGGER.warn("[DT-LapTheme] ignoring bad lap key '{}'", key);
            }
        }
        return data;
    }
}
