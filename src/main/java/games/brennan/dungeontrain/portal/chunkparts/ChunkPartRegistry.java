package games.brennan.dungeontrain.portal.chunkparts;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.UserContentPaths;
import games.brennan.dungeontrain.util.BundledNbtScanner;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which chunk part names exist, per kind — bundled first, then config, then anything registered this
 * session (a part entered before its first save). The order is the editor's slot order, so it is
 * rebuilt the same way every launch: the bundled scan and the config listing both come back sorted.
 */
public final class ChunkPartRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The same name rule carriage parts use. */
    public static final Pattern NAME = Pattern.compile("^[a-z0-9_]{1,32}$");

    private static final Map<ChunkPartKind, LinkedHashSet<String>> NAMES = new EnumMap<>(ChunkPartKind.class);

    private ChunkPartRegistry() {}

    /** Rescan disk. */
    public static synchronized void reload() {
        NAMES.clear();
        int count = 0;
        for (ChunkPartKind kind : ChunkPartKind.values()) {
            Set<String> bundled = BundledNbtScanner.scanBasenames(
                ChunkPartRegistry.class, ChunkPartStore.resourcePrefix(kind), LOGGER);
            Set<String> config = UserContentPaths.listBasenamesAcrossSearchDirs(ChunkPartStore.subSlug(kind), ".nbt");
            for (String name : bundled) if (register(kind, name)) count++;
            for (String name : config) if (register(kind, name)) count++;
        }
        LOGGER.info("[DungeonTrain] Chunk part registry loaded — {} parts", count);
    }

    /** Add {@code name} to {@code kind}; false when it was already there or is not a valid name. */
    public static synchronized boolean register(ChunkPartKind kind, String name) {
        if (name == null || !NAME.matcher(name).matches()) return false;
        return NAMES.computeIfAbsent(kind, k -> new LinkedHashSet<>()).add(name);
    }

    /** {@code kind}'s names in slot order. */
    public static synchronized List<String> names(ChunkPartKind kind) {
        if (NAMES.isEmpty()) reload();
        LinkedHashSet<String> names = NAMES.get(kind);
        return names == null ? List.of() : List.copyOf(new ArrayList<>(names));
    }

    public static synchronized void clear() {
        NAMES.clear();
    }
}
