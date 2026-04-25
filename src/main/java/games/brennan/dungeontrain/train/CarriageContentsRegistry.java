package games.brennan.dungeontrain.train;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.train.CarriageContents.ContentsType;
import net.minecraft.core.BlockPos;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.TreeSet;

/**
 * Ordered list of all carriage-contents variants available for spawning and
 * editing. The {@link ContentsType} built-ins come first in declared enum order;
 * custom contents follow in alphabetical order by id.
 *
 * <p>Custom contents are discovered from two tiers at server start — identical
 * structure to {@link CarriageVariantRegistry}:
 * <ol>
 *   <li><b>Bundled manifest</b> — {@code /data/dungeontrain/contents/customs.json}
 *       on the classpath lists the ids that ship with the mod jar.</li>
 *   <li><b>Config directory</b> — {@link CarriageContentsStore#directory()}
 *       scanned for {@code .nbt} files whose basenames are not built-in ids.</li>
 * </ol>
 *
 * <p>Only interior-sized {@code .nbt} files belong in that directory — shell
 * templates live under {@code config/dungeontrain/templates/} and pillar
 * templates under {@code config/dungeontrain/pillars/}. Because all three
 * stores have distinct subdirs, file-name collisions cannot leak contents into
 * the shell registry or vice versa.
 *
 * <p>{@link #pick(long, int)} mirrors the deterministic position-free variant
 * of {@link games.brennan.dungeontrain.editor.CarriageVariantBlocks#pickIndex}
 * so the same world seed + carriage index always resolves to the same
 * contents across reloads.
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CarriageContentsRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final List<CarriageContents> BUILTINS;
    static {
        List<CarriageContents> list = new ArrayList<>();
        for (ContentsType t : ContentsType.values()) list.add(CarriageContents.of(t));
        BUILTINS = List.copyOf(list);
    }

    /** Sorted custom contents names. Mutations go through register/unregister/reload. */
    private static final TreeSet<String> CUSTOMS = new TreeSet<>();

    /** Classpath location of the shipped-customs manifest. */
    static final String BUNDLED_MANIFEST_RESOURCE = "/data/dungeontrain/contents/customs.json";

    private CarriageContentsRegistry() {}

    /**
     * Every contents variant, built-ins first (enum order), then customs
     * alphabetical. Snapshot — safe to iterate without locking even if another
     * thread mutates the registry.
     */
    public static synchronized List<CarriageContents> allContents() {
        List<CarriageContents> all = new ArrayList<>(BUILTINS.size() + CUSTOMS.size());
        all.addAll(BUILTINS);
        for (String name : CUSTOMS) all.add(new CarriageContents.Custom(name));
        return all;
    }

    public static synchronized List<CarriageContents> builtins() {
        return BUILTINS;
    }

    /**
     * Look up a contents variant by id. Returns empty if {@code id} is neither
     * a built-in nor a registered custom.
     */
    public static synchronized Optional<CarriageContents> find(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        for (ContentsType t : ContentsType.values()) {
            if (t.name().toLowerCase(Locale.ROOT).equals(key)) {
                return Optional.of(CarriageContents.of(t));
            }
        }
        if (CUSTOMS.contains(key)) return Optional.of(new CarriageContents.Custom(key));
        return Optional.empty();
    }

    /**
     * Register a new custom contents. Returns false if a contents with the
     * same id already exists (built-in or custom). Caller is responsible for
     * writing the backing .nbt file before calling this.
     */
    public static synchronized boolean register(CarriageContents.Custom contents) {
        if (CarriageContents.isReservedBuiltinName(contents.name())) return false;
        return CUSTOMS.add(contents.name());
    }

    /**
     * Remove a custom contents from the registry. Built-ins can't be
     * unregistered. Caller is responsible for deleting the backing .nbt file.
     */
    public static synchronized boolean unregister(String id) {
        if (CarriageContents.isReservedBuiltinName(id)) return false;
        boolean removed = CUSTOMS.remove(id.toLowerCase(Locale.ROOT));
        if (removed) {
            games.brennan.dungeontrain.editor.CarriageContentsVariantBlocks.invalidate(
                id.toLowerCase(Locale.ROOT));
        }
        return removed;
    }

    /**
     * Deterministic <em>weighted</em> contents pick for a carriage. Seeds a
     * fresh {@link Random} with {@code worldSeed} XOR'd through the carriage
     * index so the same world + same index always resolves to the same
     * contents across reloads. Weights come from
     * {@link CarriageContentsWeights#current()} — weight 0 excludes a contents
     * from the pool entirely; if every contents has weight 0 the function
     * falls back to a uniform pick over the full registry (and warns once
     * per server lifetime so the misconfiguration surfaces).
     *
     * <p>Mirrors {@link games.brennan.dungeontrain.train.CarriageTemplate}'s
     * {@code weightedSeededPick} shape (cumulative array + threshold draw)
     * with a position-free seed mixing constant so a contents pick and a
     * variant-block pick for the same carriage are not correlated.</p>
     */
    public static synchronized CarriageContents pick(long worldSeed, int carriageIndex) {
        List<CarriageContents> all = allContents();
        int n = all.size();
        if (n == 0) {
            return CarriageContents.of(ContentsType.DEFAULT);
        }
        CarriageContentsWeights weights = CarriageContentsWeights.current();
        int[] cumulative = new int[n];
        int total = 0;
        for (int i = 0; i < n; i++) {
            total += weights.weightFor(all.get(i).id());
            cumulative[i] = total;
        }
        long seed = worldSeed ^ ((long) carriageIndex * 0x9E3779B97F4A7C15L);
        Random rng = new Random(seed);
        if (total <= 0) {
            warnAllZeroOnce();
            return all.get(rng.nextInt(n));
        }
        int r = rng.nextInt(total);
        for (int i = 0; i < n; i++) {
            if (r < cumulative[i]) return all.get(i);
        }
        // Unreachable: r < total and cumulative[n-1] == total. Defensive tail.
        return all.get(n - 1);
    }

    /** One-shot warning when every registered contents has weight 0 — once per server lifetime. */
    private static volatile boolean ZERO_WARNED = false;
    private static void warnAllZeroOnce() {
        if (ZERO_WARNED) return;
        synchronized (CarriageContentsRegistry.class) {
            if (ZERO_WARNED) return;
            ZERO_WARNED = true;
            LOGGER.warn("[DungeonTrain] All carriage contents have weight 0 — falling back to uniform pick. "
                + "Set at least one contents weight > 0 in config/dungeontrain/contents/weights.json.");
        }
    }

    /** Reload custom contents from the bundled manifest + the per-install config dir. */
    public static synchronized void reload() {
        CUSTOMS.clear();
        int bundled = loadBundledManifest();
        int config = loadConfigDir();

        LOGGER.info("[DungeonTrain] Carriage contents registry loaded — {} built-in + {} custom ({} bundled, {} config)",
            BUILTINS.size(), CUSTOMS.size(), bundled, config);
    }

    private static int loadBundledManifest() {
        try (InputStream in = CarriageContentsRegistry.class.getResourceAsStream(BUNDLED_MANIFEST_RESOURCE)) {
            if (in == null) return 0;
            JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            if (!root.isJsonArray()) {
                LOGGER.warn("[DungeonTrain] {} is not a JSON array — ignoring", BUNDLED_MANIFEST_RESOURCE);
                return 0;
            }
            JsonArray arr = root.getAsJsonArray();
            int added = 0;
            for (JsonElement el : arr) {
                if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
                    LOGGER.warn("[DungeonTrain] Skipping non-string contents manifest entry: {}", el);
                    continue;
                }
                String id = el.getAsString().toLowerCase(Locale.ROOT);
                if (!acceptCustomId(id, "bundled contents manifest")) continue;
                if (CUSTOMS.add(id)) added++;
            }
            return added;
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled contents manifest {}: {}", BUNDLED_MANIFEST_RESOURCE, e.toString());
            return 0;
        }
    }

    private static int loadConfigDir() {
        Path dir = CarriageContentsStore.directory();
        if (!Files.isDirectory(dir)) return 0;

        int added = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.nbt")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".nbt")) continue;
                String basename = name.substring(0, name.length() - 4).toLowerCase(Locale.ROOT);
                if (CarriageContents.isReservedBuiltinName(basename)) continue;
                if (!acceptCustomId(basename, "config dir " + file.getFileName())) continue;
                if (CUSTOMS.add(basename)) added++;
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to scan contents directory {}: {}", dir, e.toString());
        }
        return added;
    }

    private static boolean acceptCustomId(String id, String origin) {
        if (CarriageContents.isReservedBuiltinName(id)) {
            LOGGER.warn("[DungeonTrain] Ignoring custom contents '{}' from {} — collides with built-in name", id, origin);
            return false;
        }
        if (!CarriageContents.NAME_PATTERN.matcher(id).matches()) {
            LOGGER.warn("[DungeonTrain] Ignoring custom contents '{}' from {} — invalid name", id, origin);
            return false;
        }
        return true;
    }

    public static synchronized void clear() {
        CUSTOMS.clear();
        ZERO_WARNED = false;
    }

    public static synchronized List<String> customIds() {
        return Collections.unmodifiableList(new ArrayList<>(CUSTOMS));
    }

    /**
     * Helper: the interior block-position where the {@code default} built-in
     * places its stone pressure plate — the floor centre of the interior
     * volume. Floor is at {@code dy=0} in the interior coord space (the shell
     * floor is one layer below, outside the interior template).
     */
    public static BlockPos defaultPressurePlatePos(CarriageDims dims) {
        return new BlockPos(
            (dims.length() - 2) / 2,
            0,
            (dims.width() - 2) / 2
        );
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        reload();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }
}
