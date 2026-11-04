package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import net.minecraft.Util;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every biome a player has ever ridden through, for Terra Omnia ({@link TerraOmnia}): kept for good, across
 * deaths and across worlds. Nothing here ever removes a biome — the set only grows. Unlike the per-life count
 * tiers ({@link games.brennan.dungeontrain.player.PlayerBiomeProgress}), a death does not touch it.
 *
 * <p>Lives at {@code <gameDir>/dungeontrain/stats/<uuid>-biomes.json}, beside {@link GlobalPlayerStats}, as its
 * own small store because that class's codec is at the 16-field cap. A new biome is rare, so each one is written
 * straight away — off the server thread, atomically — rather than waiting for logout.</p>
 */
public final class GlobalBiomeProgress {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_NAME_SUFFIX = "-biomes.json";

    private record Data(List<ResourceLocation> biomes) {
        static final Codec<Data> CODEC = RecordCodecBuilder.create(in -> in.group(
            ResourceLocation.CODEC.listOf().optionalFieldOf("biomes", List.of()).forGetter(Data::biomes)
        ).apply(in, Data::new));
    }

    /** Each cached player's biomes, as an immutable set replaced whole on every addition. */
    private static final Map<UUID, Set<ResourceLocation>> CACHE = new ConcurrentHashMap<>();

    private GlobalBiomeProgress() {}

    /** {@code <gameDir>/dungeontrain/stats/<uuid>-biomes.json}. */
    static Path file(UUID uuid) {
        return PlayerDataPaths.dir(PlayerDataPaths.STATS).resolve(uuid + FILE_NAME_SUFFIX);
    }

    /** Every biome {@code uuid} has ridden through, in any life or world. */
    public static Set<ResourceLocation> biomes(UUID uuid) {
        return CACHE.computeIfAbsent(uuid, GlobalBiomeProgress::load);
    }

    /**
     * Add {@code ids} to {@code uuid}'s biomes, saving if any are new.
     *
     * @return whether any were new
     */
    public static boolean addAll(UUID uuid, Collection<ResourceLocation> ids) {
        Set<ResourceLocation> have = biomes(uuid);
        if (have.containsAll(ids)) return false;
        Set<ResourceLocation> grown = CACHE.compute(uuid, (k, old) -> {
            Set<ResourceLocation> next = new LinkedHashSet<>(old != null ? old : load(k));
            next.addAll(ids);
            return Set.copyOf(next);
        });
        Util.ioPool().execute(() -> save(uuid, grown));
        return true;
    }

    /** Drop the cached copy (logout). Nothing is lost: every addition was already written. */
    public static void evict(UUID uuid) {
        CACHE.remove(uuid);
    }

    private static Set<ResourceLocation> load(UUID uuid) {
        Set<ResourceLocation> read = read(file(uuid));
        return read != null ? read : Set.of();
    }

    /** The biomes in {@code path}: empty when there is no file, {@code null} when it can't be read. */
    private static Set<ResourceLocation> read(Path path) {
        if (!Files.isRegularFile(path)) return Set.of();
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonElement element = JsonParser.parseReader(reader);
            var result = Data.CODEC.parse(JsonOps.INSTANCE, element);
            if (result.error().isPresent()) {
                LOGGER.error("[DungeonTrain] GlobalBiomeProgress: parse failed for {}: {}",
                    path, result.error().get().message());
                return null;
            }
            return result.result().map(d -> Set.copyOf(d.biomes())).orElse(Set.of());
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[DungeonTrain] GlobalBiomeProgress: couldn't read {}: {}", path, e.toString());
            return null;
        }
    }

    /** Move an unreadable file aside, so writing a fresh one never destroys what it held. */
    private static void keepUnreadable(Path path) {
        Path aside = path.resolveSibling(path.getFileName() + ".unreadable-" + System.currentTimeMillis());
        try {
            Files.move(path, aside);
            LOGGER.error("[DungeonTrain] GlobalBiomeProgress: kept unreadable {} as {}", path, aside);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] GlobalBiomeProgress: couldn't move unreadable {} aside: {}", path, e.toString());
        }
    }

    /**
     * Write {@code biomes} atomically. Writes are serialised, and a write never shrinks the file: the set
     * on disk is merged in first, so an older snapshot landing late can never drop a biome already saved, and
     * a file that can't be read is moved aside ({@link #keepUnreadable}) rather than written over.
     */
    private static synchronized void save(UUID uuid, Set<ResourceLocation> biomes) {
        Path path = file(uuid);
        Set<ResourceLocation> onDisk = read(path);
        if (onDisk == null) {
            keepUnreadable(path);
            onDisk = Set.of();
        }
        Set<ResourceLocation> merged = new LinkedHashSet<>(onDisk);
        merged.addAll(biomes);
        var result = Data.CODEC.encodeStart(JsonOps.INSTANCE, new Data(List.copyOf(merged)));
        if (result.error().isPresent()) {
            LOGGER.error("[DungeonTrain] GlobalBiomeProgress: encode failed: {}", result.error().get().message());
            return;
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(tmp)) {
                writer.write(result.result().orElseThrow().toString());
            }
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] GlobalBiomeProgress: couldn't write {}: {}", path, e.toString());
        }
    }
}
