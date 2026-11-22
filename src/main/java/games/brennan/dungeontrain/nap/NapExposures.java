package games.brennan.dungeontrain.nap;

import com.mojang.logging.LogUtils;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.level.storage.ExposureData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The pixels of this life's photos, carried by a nap into the world it wakes in.
 *
 * <p>Exposure keeps each photo's pixels as world SavedData ({@code data/exposures/<id>.dat}, written at
 * the next world save). A nap deletes the napped world, so without this every camera frame and every
 * photograph the woken player holds would point at an exposure the new world never had ("photo lost").
 * The ids are found by scanning the napped player tag (photographs anywhere in the inventory or ender
 * chest) and the run extras (the life's camera frames) for strings that name a stored exposure.</p>
 *
 * <p>Read and written straight through the overworld's {@link DimensionDataStorage}, under the same
 * {@link #STORAGE_PREFIX} Exposure's {@code ExposureRepository} uses: it sees shots not yet saved to disk,
 * logs nothing for strings that are not exposures, and works before Exposure's server init (a wake can be
 * planned by the train bootstrap, ahead of {@code ServerStartedEvent}).</p>
 */
public final class NapExposures {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Exposure's SavedData name prefix: {@code ExposureRepository} stores id {@code x} as {@code "exposures/x"}. */
    static final String STORAGE_PREFIX = "exposures/";

    /** The run-extras key the carried exposures travel under ({@link NapSnapshot#runExtras}). */
    public static final String EXTRAS_KEY = "napExposures";

    /** At most this many exposures per nap. A disposable camera life keeps 24 frames. */
    static final int MAX_EXPOSURES = 64;

    /** At most this many pixel bytes per nap (before NBT compression). */
    static final long MAX_PIXEL_BYTES = 12L * 1024 * 1024;

    /** What an exposure id can look like: no path separators, no namespace colon. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.\\-]{1,128}");

    private NapExposures() {}

    /**
     * Every string in {@code tags} that could be an exposure id, in first-seen order, each once.
     * Pure, for tests; the caller decides which ones are really stored.
     */
    static List<String> candidateIds(CompoundTag... tags) {
        Set<String> out = new LinkedHashSet<>();
        for (CompoundTag t : tags) {
            if (t != null) collect(t, out);
        }
        return new ArrayList<>(out);
    }

    private static void collect(Tag tag, Set<String> out) {
        if (tag instanceof StringTag s) {
            String v = s.getAsString();
            if (ID.matcher(v).matches()) out.add(v);
        } else if (tag instanceof CompoundTag c) {
            for (String key : c.getAllKeys()) collect(c.get(key), out);
        } else if (tag instanceof ListTag l) {
            for (Tag e : l) collect(e, out);
        }
    }

    /**
     * The stored exposures named anywhere in {@code playerTag} or {@code runExtras}, as
     * {@code id -> ExposureData tag}, within {@link #MAX_EXPOSURES} and {@link #MAX_PIXEL_BYTES}.
     * Empty when there are none or anything fails: a nap never fails over its photos.
     */
    public static CompoundTag capture(MinecraftServer server, CompoundTag playerTag, CompoundTag runExtras) {
        CompoundTag out = new CompoundTag();
        try {
            DimensionDataStorage storage = server.overworld().getDataStorage();
            HolderLookup.Provider registries = server.registryAccess();
            long bytes = 0;
            int skipped = 0;
            for (String id : candidateIds(playerTag, runExtras)) {
                ExposureData data = stored(storage, id);
                if (data == null) continue;
                int size = data.getPixels() == null ? 0 : data.getPixels().length;
                if (out.size() >= MAX_EXPOSURES || bytes + size > MAX_PIXEL_BYTES) {
                    skipped++;
                    continue;
                }
                out.put(id, data.save(new CompoundTag(), registries));
                bytes += size;
            }
            if (skipped > 0) {
                LOGGER.warn("[DungeonTrain] Nap: {} photo(s) over the carry limit were left behind", skipped);
            }
            LOGGER.info("[DungeonTrain] Nap: carrying {} photo(s), {} KiB of pixels", out.size(), bytes / 1024);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Nap: photos not carried", e);
            return new CompoundTag();
        }
        return out;
    }

    /**
     * Put the carried exposures into this (fresh) world, before the woken player loads. An id this
     * world already holds is left as it is. Returns how many were written.
     */
    public static int restore(MinecraftServer server, CompoundTag carried) {
        if (carried == null || carried.isEmpty()) return 0;
        int written = 0;
        try {
            Files.createDirectories(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("exposures"));
            DimensionDataStorage storage = server.overworld().getDataStorage();
            HolderLookup.Provider registries = server.registryAccess();
            for (String id : carried.getAllKeys()) {
                if (!ID.matcher(id).matches() || stored(storage, id) != null) continue;
                try {
                    ExposureData data = ExposureData.load(carried.getCompound(id), registries);
                    storage.set(STORAGE_PREFIX + id, data);
                    data.setDirty();
                    written++;
                } catch (RuntimeException e) {
                    LOGGER.warn("[DungeonTrain] Nap: photo {} could not be restored", id, e);
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Nap: photos not restored", e);
        }
        LOGGER.info("[DungeonTrain] Nap: restored {} of {} carried photo(s)", written, carried.size());
        return written;
    }

    /**
     * {@code frames} without those whose exposure this world no longer has (a nap taken before
     * photos were carried). Frames that are not stored exposures (texture-backed) are kept.
     */
    public static List<Frame> withExposures(MinecraftServer server, List<Frame> frames) {
        if (frames.isEmpty() || server == null) return frames;
        try {
            DimensionDataStorage storage = server.overworld().getDataStorage();
            List<Frame> kept = new ArrayList<>(frames.size());
            for (Frame f : frames) {
                if (!f.identifier().isId() || stored(storage, f.identifier().id()) != null) kept.add(f);
            }
            if (kept.size() < frames.size()) {
                LOGGER.info("[DungeonTrain] {} camera frame(s) dropped: their photo is no longer in this world",
                    frames.size() - kept.size());
            }
            return List.copyOf(kept);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Camera frames not checked against stored photos", e);
            return frames;
        }
    }

    private static ExposureData stored(DimensionDataStorage storage, String id) {
        return storage.get(ExposureData.factory(), STORAGE_PREFIX + id);
    }
}
