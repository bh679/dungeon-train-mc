package games.brennan.dungeontrain.portal.chunkframe;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.UserContentPaths;
import games.brennan.dungeontrain.util.BundledNbtScanner;
import org.slf4j.Logger;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Which frame names exist — bundled first, then config, then anything registered this session (a
 * frame entered before its first save). The order is the editor's slot order, so it is rebuilt the
 * same way every launch: the bundled scan and the config listing both come back sorted.
 */
public final class ChunkFrameRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The same name rule carriage parts use. */
    public static final Pattern NAME = Pattern.compile("^[a-z0-9_]{1,32}$");

    private static LinkedHashSet<String> names;

    private ChunkFrameRegistry() {}

    /** Rescan disk. */
    public static synchronized void reload() {
        names = new LinkedHashSet<>();
        for (String name : BundledNbtScanner.scanBasenames(ChunkFrameRegistry.class, ChunkFrameStore.RESOURCE_PREFIX, LOGGER)) {
            register(name);
        }
        for (String name : UserContentPaths.listBasenamesAcrossSearchDirs(ChunkFrameStore.SUBDIR, ".nbt")) {
            register(name);
        }
        LOGGER.info("[DungeonTrain] Chunk frame registry loaded — {} frames", names.size());
    }

    /** Add {@code name}; false when it was already there or is not a valid name. */
    public static synchronized boolean register(String name) {
        if (names == null) reload();
        if (name == null || !NAME.matcher(name).matches()) return false;
        return names.add(name);
    }

    /** Every frame name, in slot order. */
    public static synchronized List<String> names() {
        if (names == null) reload();
        return List.copyOf(names);
    }

    public static synchronized void clear() {
        names = null;
    }
}
